import Foundation
import Combine
import BareRemoteCore

enum RemoteSurface: Hashable { case window, menu }

/// Shared cancellation owns every connection created by an operation, including late completions.
final class OperationGate: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    private var cleanup: [() -> Void] = []
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
    func own(_ close: @escaping () -> Void) {
        lock.lock()
        if stopped { lock.unlock(); close() } else { cleanup.append(close); lock.unlock() }
    }
    func cancel() {
        lock.lock(); stopped = true; let actions = cleanup; cleanup.removeAll(); lock.unlock()
        actions.forEach { $0() }
    }
    deinit { cancel() }
}

@MainActor final class RemoteModel: ObservableObject {
    @Published var saved: SavedTV? {
        didSet {
            if oldValue?.host != saved?.host || oldValue?.certificate != saved?.certificate {
                pictureState = "unknown"
            }
        }
    }
    @Published var host = ""
    @Published var tvName = ""
    @Published var code = ""
    @Published var status = "Add your TV to get started"
    @Published var detail = ""
    @Published var needsLocalNetworkAccess = false
    @Published var busy = false
    @Published var connected = false
    @Published var awaitingCode = false
    @Published var setupOpen = false
    @Published var pictureState = "unknown"
    @Published var pictureBusy = false
    @Published var dogMode = "unknown"
    @Published var dogBusy = false
    @Published var dogStatus = "Check status to read Dog Mode from your TV."
    private var dogSequence = 0
    private let dogReplies = DispatchQueue(label: "uy.nico.bareremote.dog", qos: .userInitiated)
    @Published var keyboardMode = false
    @Published private(set) var keyboardState: TVKeyboardState?
    @Published var keyboardNotice = ""
    var keyboardWindowRequested: (() -> Void)?
    private var keyboardShowRequest: UInt64 = 0
    private var keyboardRemoteOverride: UInt64?
    private var keyboardInputGate = OperationGate()
    private let worker = DispatchQueue(label: "uy.nico.bareremote.commands", qos: .userInitiated)
    private let replies = DispatchQueue(label: "uy.nico.bareremote.picture", qos: .userInitiated)
    private var gate = OperationGate()
    private var generation = 0
    private var pairing: TVPairing?
    private var remote: TVRemote?
    private var pending = 0
    private var repeatTimer: Timer?
    private var heldCode: Int?
    @Published private var surfaces: Set<RemoteSurface> = []
    private var idleWork: DispatchWorkItem?
    private var sessionLeaseUntil: TimeInterval = 0
    private var shortcutQueue: [(action: RemoteShortcutAction, deadline: TimeInterval)] = []

    init() {
        do { saved = try SavedTV.load(); host = saved?.host ?? ""; tvName = saved?.name ?? "" }
        catch { detail = error.localizedDescription }
    }
    var canControl: Bool { connected && !busy && !setupOpen }
    var inputGeneration: Int { generation }
    var displayName: String { saved?.name.isEmpty == false ? saved!.name : "Android TV" }
    var modeSymbol: String { pictureState == "day" ? "sun.max" : pictureState == "night" ? "moon" : "questionmark" }
    var modeLabel: String { pictureState == "day" ? "Day last confirmed" : pictureState == "night" ? "Night last confirmed" : "Picture mode unconfirmed" }
    var canType: Bool { keyboardMode && !surfaces.isEmpty && canControl && keyboardState?.canSendText == true }
    var keyboardFocusIdentifier: String { "\(generation):\(keyboardState?.focusID ?? 0)" }
    var keyboardHint: String {
        if !connected { return busy ? "Connecting to your TV…" : "Connect to your TV to type." }
        if keyboardState?.supported != true { return "This TV remote service does not offer keyboard input." }
        if keyboardState?.active != true { return "Select a text field on your TV." }
        if !canType { return "Waiting for the TV keyboard…" }
        return "Type or paste here. Your text goes directly to the TV."
    }

    func showKeyboardMode() {
        stopHolding()
        if saved == nil { openSetup(); return }
        if !connected && !busy { sync() }
        keyboardMode = true
        keyboardRemoteOverride = nil
        keyboardNotice = ""
    }
    func showRemoteMode() {
        stopHolding()
        invalidateKeyboardInput()
        keyboardRemoteOverride = keyboardState?.editorID
        keyboardMode = false
        keyboardNotice = ""
    }
    func openKeyboardWindow() {
        keyboardWindowRequested?()
        showKeyboardMode()
    }
    func receiveKeyboard(_ state: TVKeyboardState, revision: Int) {
        guard generation == revision else { return }
        let previous = keyboardState
        keyboardState = state
        let becameHidden = state.keyboardVisible == false && previous?.keyboardVisible != false
        if previous?.focusID != state.focusID { keyboardNotice = ""; invalidateKeyboardInput() }
        if !state.active, previous?.active == true {
            keyboardMode = false
            keyboardRemoteOverride = nil
            keyboardNotice = ""
        } else if becameHidden {
            stopHolding()
            invalidateKeyboardInput()
            keyboardMode = false
            keyboardNotice = ""
        }
        if state.showRequestID > keyboardShowRequest {
            keyboardShowRequest = state.showRequestID
            if state.active, state.keyboardVisible == true, keyboardRemoteOverride != state.editorID {
                stopHolding()
                keyboardMode = true
            }
        }
    }
    @discardableResult func commitKeyboardText(_ text: String) -> Bool {
        guard !text.isEmpty else { return true }
        guard text.utf8.count <= 4096, text.utf16.count <= 2048 else {
            keyboardNotice = "Paste up to 2,048 characters at a time. Nothing was sent."
            return false
        }
        return keyboardCommand(text: text, key: nil)
    }
    func sendKeyboardKey(_ key: Int) { _ = keyboardCommand(text: nil, key: key) }
    private func keyboardCommand(text: String?, key: Int?) -> Bool {
        guard canType, let remote, let context = keyboardState?.context else {
            keyboardNotice = keyboardHint
            return false
        }
        guard pending < 32 else { keyboardNotice = "Keyboard is busy. This input was not sent."; return false }
        pending += 1
        keyboardNotice = ""
        touchSessionLease()
        let revision = generation, token = gate, input = keyboardInputGate
        let deadline = ProcessInfo.processInfo.systemUptime + 7
        worker.async {
            guard !token.cancelled else { return }
            guard !input.cancelled else {
                DispatchQueue.main.async { if self.generation == revision { self.pending = max(0, self.pending - 1) } }
                return
            }
            do {
                if let text { try remote.sendText(text, context: context, deadline: deadline) }
                else if let key { try remote.sendKeyboardKey(key, context: context, deadline: deadline) }
                DispatchQueue.main.async {
                    guard self.generation == revision else { return }
                    self.pending = max(0, self.pending - 1)
                }
            } catch {
                if case TVProtocolError.unsupported = error {
                    DispatchQueue.main.async {
                        guard self.generation == revision else { return }
                        self.pending = max(0, self.pending - 1)
                        self.keyboardNotice = error.localizedDescription + " This input was not sent."
                    }
                } else { self.commandFailed(error, revision: revision) }
            }
        }
        return true
    }
    private func invalidateKeyboardInput() { keyboardInputGate.cancel(); keyboardInputGate = OperationGate() }

    func appeared() {
        if saved == nil { setupOpen = true }
        else if !setupOpen && !connected && !busy { sync() }
    }
    func surfaceOpened(_ surface: RemoteSurface) {
        surfaces.insert(surface)
        idleWork?.cancel(); idleWork = nil
        if surface == .window { appeared() }
        else if saved != nil && !setupOpen && !connected && !busy { sync() }
    }
    func surfaceClosed(_ surface: RemoteSurface) {
        surfaces.remove(surface)
        stopHolding()
        if surfaces.isEmpty { invalidateKeyboardInput(); scheduleIdleDisconnect() }
    }
    private func touchSessionLease() {
        sessionLeaseUntil = ProcessInfo.processInfo.systemUptime + 60
        if surfaces.isEmpty { scheduleIdleDisconnect() }
    }
    private func scheduleIdleDisconnect() {
        idleWork?.cancel()
        let delay = max(0.75, sessionLeaseUntil - ProcessInfo.processInfo.systemUptime)
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.surfaces.isEmpty else { return }
            if self.sessionLeaseUntil > ProcessInfo.processInfo.systemUptime { self.scheduleIdleDisconnect() }
            else { self.disconnect() }
        }
        idleWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }
    /// Desktop links can start a single bounded session without showing a window.
    /// Every received link is one user action; failed writes are never replayed.
    func performShortcut(_ action: RemoteShortcutAction) {
        guard action != .open, saved != nil, !setupOpen, !awaitingCode else { return }
        if action == .sync { sync(); touchSessionLease(); return }
        if !connected && !busy { sync() }
        touchSessionLease()
        guard shortcutQueue.count < 32 else { detail = "Too many queued widget controls. Try again after the current action."; return }
        shortcutQueue.append((action, ProcessInfo.processInfo.systemUptime + 7))
        flushShortcuts()
    }
    private func flushShortcuts() {
        guard canControl else { return }
        let queued = shortcutQueue
        shortcutQueue.removeAll()
        for item in queued {
            guard ProcessInfo.processInfo.systemUptime < item.deadline else {
                detail = "A widget control expired before the TV was ready. It was not sent."
                continue
            }
            switch item.action {
            case .key(let code): sendKey(code, deadline: item.deadline)
            case .inputs: inputPicker(deadline: item.deadline)
            case .toggle:
                if !pictureBusy { refreshPicture(toggle: true, deadline: item.deadline) }
                else { detail = "Picture confirmation is already in progress. Sync before trying again." }
            case .sync, .open: break
            }
        }
    }
    private func reset() {
        stopHolding(); generation += 1; gate.cancel(); gate = OperationGate()
        idleWork?.cancel(); idleWork = nil; sessionLeaseUntil = 0; shortcutQueue.removeAll()
        pairing = nil; remote = nil; connected = false; awaitingCode = false
        busy = false; pending = 0; pictureBusy = false
        dogSequence += 1; dogMode = "unknown"; dogBusy = false
        dogStatus = "Check status to confirm Dog Mode on your TV."
        needsLocalNetworkAccess = false
        keyboardState = nil; keyboardMode = false; keyboardNotice = ""
        keyboardShowRequest = 0; keyboardRemoteOverride = nil
        invalidateKeyboardInput()
    }
    func disconnect() { reset(); status = saved == nil ? "Add your TV to get started" : "Disconnected" }
    func openSetup() {
        disconnect(); detail = ""; host = saved?.host ?? host; tvName = saved?.name ?? tvName; setupOpen = true
        if saved != nil { sync() }
    }
    func closeSetup() {
        reset(); setupOpen = false; code = ""
        if saved != nil { sync() }
        else { status = "Add your TV to get started"; detail = "" }
    }
    func cancelPairing() { reset(); code = ""; status = "Pairing cancelled"; detail = "" }
    func select(_ tv: DiscoveredTV) { host = tv.host; tvName = tv.name }

    func pair() {
        let address = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard validateAddress(address) else { return }
        reset(); busy = true; status = "Requesting TV code…"; detail = ""; code = ""
        let token = gate, revision = generation
        worker.async {
            do {
                guard !token.cancelled else { return }
                let identity = try ClientIdentity.loadOrCreate()
                let session = try TVPairing.start(host: address, identity: identity, cancelled: { token.cancelled })
                token.own { session.close() }
                DispatchQueue.main.async {
                    guard self.generation == revision, !token.cancelled else { return }
                    self.pairing = session; self.host = address; self.busy = false
                    self.awaitingCode = true; self.status = "Enter the code shown on your TV"
                }
            } catch { self.report(error, revision: revision) }
        }
    }
    func finishPairing() {
        guard let pairing, awaitingCode, !busy else { return }
        busy = true; detail = ""; status = "Verifying code…"
        let value = code.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = host, name = tvName, token = gate, revision = generation
        worker.async {
            do {
                guard !token.cancelled else { return }
                let pin = try pairing.finish(code: value)
                DispatchQueue.main.async {
                    guard self.generation == revision, !token.cancelled else { return }
                    do {
                        let tv = SavedTV(host: address, name: name, certificate: pin)
                        try tv.save()
                        self.saved = tv; self.awaitingCode = false; self.code = ""
                        self.setupOpen = false; self.sync()
                    } catch {
                        self.reset(); self.code = ""; self.detail = error.localizedDescription
                        self.status = "Could not save pairing · pair again"
                    }
                }
            } catch { self.report(error, revision: revision) }
        }
    }
    func sync() {
        guard let saved else { openSetup(); return }
        connect(to: saved.host, name: saved.name, saveAddress: false, recoverAddress: true,
                keepSetupOpen: setupOpen)
    }
    func reconnect() { connect(to: host.trimmingCharacters(in: .whitespacesAndNewlines), name: tvName, saveAddress: true) }
    private func connect(to address: String, name: String, saveAddress: Bool, recoverAddress: Bool = false,
                         keepSetupOpen: Bool = false, dogAfterConnect: String? = nil) {
        guard let saved else { return }
        guard validateAddress(address) else { return }
        reset(); busy = true; status = "Connecting…"; detail = ""
        if keepSetupOpen { touchSessionLease() }
        if dogAfterConnect != nil {
            dogBusy = true
            dogStatus = dogAfterConnect == "setup" ? "Connecting to open Dog Mode setup…" : "Connecting to check Dog Mode…"
            // Settings can open during the menu-to-window handoff, before a surface
            // owns this connection. Keep that explicit bounded operation alive.
            touchSessionLease()
        }
        let token = gate, revision = generation
        // This private browser never changes Setup's explicit scan or trusts
        // its labels. The gate stops it even if a main-thread start is delayed.
        let recoveryDiscovery = TVDiscovery()
        let scan: () -> TVRecoveryCandidates = {
            let candidates = TVRecoveryCandidates {
                DispatchQueue.main.async { recoveryDiscovery.stop() }
            }
            token.own { candidates.stop() }
            DispatchQueue.main.async {
                guard self.generation == revision, !token.cancelled else { candidates.stop(); return }
                self.status = "Looking for your TV…"
                recoveryDiscovery.start(duration: TVConnectionRecovery.scanTimeout,
                                        found: { candidates.offer($0.host) },
                                        finished: { candidates.finish() })
            }
            return candidates
        }
        worker.async {
            do {
                guard !token.cancelled else { return }
                let identity = try ClientIdentity.loadOrCreate()
                let result = try TVConnectionRecovery.connect(host: address, recover: recoverAddress,
                                                               cancelled: { token.cancelled },
                                                               open: { $0.isOpen }, close: { $0.close() },
                                                               connect: { candidate, timeout in
                    let session = try TVRemote.connect(host: candidate, pin: saved.certificate,
                                                       identity: identity, timeout: timeout,
                                                       cancelled: { token.cancelled })
                    token.own { session.close() }
                    return session
                }, scan: scan)
                let session = result.session, connectedAddress = result.host
                DispatchQueue.main.async {
                    guard self.generation == revision, !token.cancelled else { return }
                    do {
                        guard session.isOpen else { throw TVProtocolError.closed }
                        if saveAddress || connectedAddress != saved.host {
                            let updated = SavedTV(host: connectedAddress, name: name, certificate: saved.certificate)
                            try updated.save(); self.saved = updated
                        }
                        self.host = connectedAddress; self.tvName = name
                        session.onDisconnect = { [weak self] in
                            DispatchQueue.main.async {
                                guard let self, self.generation == revision else { return }
                                let dogPending = self.dogBusy
                                self.reset(); self.status = "Disconnected · press Sync to reconnect"
                                if dogPending {
                                    self.dogStatus = "The TV connection closed before Dog Mode was confirmed. Reconnect and check status; the request was not repeated."
                                }
                            }
                        }
                        self.remote = session; self.connected = true; self.busy = false
                        session.onKeyboardStateChanged = { [weak self] state in
                            DispatchQueue.main.async { self?.receiveKeyboard(state, revision: revision) }
                        }
                        self.setupOpen = keepSetupOpen; self.status = "Connected"
                        self.flushShortcuts()
                        if let dogAfterConnect { self.dogBusy = false; self.requestDogMode(dogAfterConnect) }
                        if self.surfaces.isEmpty { self.scheduleIdleDisconnect() }
                    } catch {
                        session.close(); self.busy = false; self.dogBusy = false; self.dogMode = "unknown"
                        self.dogStatus = "Could not verify the TV connection. Reconnect and check status again."
                        self.status = "Address was not saved"; self.detail = error.localizedDescription
                    }
                }
            } catch { self.report(error, revision: revision) }
        }
    }
    func forget() {
        do {
            try SavedTV.forget(); reset(); saved = nil; host = ""; tvName = ""; code = ""
            status = "TV forgotten on this Mac"; detail = ""; setupOpen = true
        } catch { detail = error.localizedDescription }
    }
    private func validateAddress(_ address: String) -> Bool {
        let parts = address.split(separator: ".", omittingEmptySubsequences: false)
        let valid = parts.count == 4 && parts.allSatisfy {
            guard let number = UInt8($0) else { return false }
            return String(number) == $0
        }
        if !valid { status = "Check the TV address"; detail = "Enter a numeric IPv4 address, such as 192.168.1.20." }
        return valid
    }
    func sendKey(_ key: Int, long: Bool = false, repeating: Bool = false, deadline: TimeInterval? = nil) {
        guard canControl, let remote, pending < 32, !repeating || pending == 0 else { return }
        touchSessionLease()
        pending += 1
        let revision = generation, token = gate
        worker.async {
            guard !token.cancelled else { return }
            if let deadline, ProcessInfo.processInfo.systemUptime >= deadline {
                self.shortcutExpired(revision: revision, picture: false); return
            }
            do {
                if long { try remote.sendLongKey(key) } else { try remote.sendKey(key) }
                DispatchQueue.main.async { if self.generation == revision { self.pending = max(0, self.pending - 1) } }
            } catch { self.commandFailed(error, revision: revision) }
        }
    }
    func inputPicker(deadline: TimeInterval? = nil) {
        guard canControl, let remote, pending < 32 else { return }
        touchSessionLease()
        pending += 1
        let revision = generation, token = gate
        worker.async {
            guard !token.cancelled else { return }
            if let deadline, ProcessInfo.processInfo.systemUptime >= deadline {
                self.shortcutExpired(revision: revision, picture: false); return
            }
            do {
                try remote.sendAppLink("barehome://inputs")
                DispatchQueue.main.async { if self.generation == revision { self.pending = max(0, self.pending - 1) } }
            } catch { self.commandFailed(error, revision: revision) }
        }
    }
    func openDogSetup() {
        guard !dogBusy, !busy, !awaitingCode, let saved else { return }
        if !connected {
            connect(to: saved.host, name: saved.name, saveAddress: false, recoverAddress: true,
                    keepSetupOpen: setupOpen, dogAfterConnect: "setup")
            return
        }
        sendDogSetup()
    }
    private func sendDogSetup() {
        guard connected, !busy, !dogBusy, let remote else { return }
        touchSessionLease(); dogBusy = true; dogSequence += 1
        dogStatus = "Opening Dog Mode setup on your TV…"
        let revision = generation, sequence = dogSequence, token = gate
        let deadline = ProcessInfo.processInfo.systemUptime + 7
        worker.async {
            do {
                guard !token.cancelled else { return }
                let remaining = deadline - ProcessInfo.processInfo.systemUptime
                guard remaining > 0.75 else { throw TVProtocolError.timedOut }
                try remote.sendAppLink("barehome://dog/setup", timeout: min(5, remaining - 0.75))
                try remote.awaitCommandDeliveryWindow()
                DispatchQueue.main.async {
                    guard self.generation == revision, self.dogSequence == sequence, !token.cancelled else { return }
                    self.dogBusy = false; self.dogMode = "unknown"
                    self.dogStatus = "Follow the setup prompt on your TV, then choose Check status."
                }
            } catch { self.dogFailed(error, revision: revision, sequence: sequence) }
        }
    }
    func refreshDogMode() {
        guard !dogBusy, !busy, !awaitingCode, let saved else { return }
        if !connected {
            connect(to: saved.host, name: saved.name, saveAddress: false, recoverAddress: true,
                    keepSetupOpen: setupOpen, dogAfterConnect: "state")
            return
        }
        requestDogMode("state")
    }
    func startDogMode() {
        guard dogMode == "off" else { dogStatus = "Check Dog Mode status before starting."; return }
        requestDogMode("start")
    }
    func stopDogMode() {
        guard ["active", "starting", "restore_required", "unknown", "error"].contains(dogMode) || DogStateWire.isStartupFailure(dogMode) else {
            dogStatus = "Check Dog Mode status before restoring settings."; return
        }
        guard !dogBusy, !busy, !awaitingCode, let saved else { return }
        if !connected {
            connect(to: saved.host, name: saved.name, saveAddress: false, recoverAddress: true,
                    keepSetupOpen: setupOpen, dogAfterConnect: "stop")
            return
        }
        requestDogMode("stop")
    }
    private func requestDogMode(_ action: String) {
        if action == "setup" { sendDogSetup(); return }
        guard connected, !busy, !dogBusy, let remote, let local = remote.localAddress, let saved else { return }
        touchSessionLease()
        dogBusy = true
        dogStatus = action == "state" ? "Checking Dog Mode on your TV…"
            : action == "start" ? "Starting Dog Mode…" : "Restoring TV settings…"
        dogSequence += 1
        let revision = generation, sequence = dogSequence, token = gate
        worker.async {
            do {
                guard !token.cancelled else { return }
                let request = try DogStateRequest(localAddress: local, peer: saved.host, timeout: action == "start" ? 35 : 8)
                token.own { request.close() }
                try remote.sendAppLink(request.requestURL(action: action))
                self.dogReplies.async {
                    do {
                        let state = try request.wait(waitForFinal: action == "start") { state in
                            DispatchQueue.main.async {
                                guard self.generation == revision, self.dogSequence == sequence, !token.cancelled else { return }
                                self.dogMode = state
                                self.dogStatus = "The TV is opening NS NOW and selecting CANAL 10. Waiting for confirmation…"
                            }
                        }
                        DispatchQueue.main.async {
                            guard self.generation == revision, self.dogSequence == sequence, !token.cancelled else { return }
                            self.dogMode = state; self.dogBusy = false
                            self.dogStatus = state == "off" ? "Dog Mode is off. TV energy settings are restored."
                                : state == "starting" ? "The TV is still starting Dog Mode. Check status or restore settings to cancel."
                                : state == "active" ? "Dog Mode is active on the TV. You can close BareRemote."
                                : state == "restore_required" ? "TV energy settings need restoring. Choose Restore settings & exit."
                                : state == "unavailable" ? "Open BareHome on the TV and complete its one-time Dog Mode setup."
                                : DogStateWire.startupFailureMessage(state) ?? "The TV could not finish Dog Mode. Check status before trying again."
                        }
                    } catch { self.dogFailed(error, revision: revision, sequence: sequence) }
                }
            } catch { self.dogFailed(error, revision: revision, sequence: sequence) }
        }
    }
    private nonisolated func dogFailed(_ error: Error, revision: Int, sequence: Int) {
        DispatchQueue.main.async {
            guard self.generation == revision, self.dogSequence == sequence else { return }
            self.dogMode = "unknown"; self.dogBusy = false
            self.dogStatus = "\(error.localizedDescription) Check status before trying again; the request was not repeated."
        }
    }
    func refreshPicture(toggle: Bool, deadline: TimeInterval? = nil) {
        guard connected, !pictureBusy, let remote, let local = remote.localAddress, let saved else { return }
        if toggle { touchSessionLease() }
        pictureBusy = true
        let revision = generation, token = gate
        worker.async {
            do {
                guard !token.cancelled else { return }
                if let deadline, ProcessInfo.processInfo.systemUptime >= deadline {
                    self.shortcutExpired(revision: revision, picture: true); return
                }
                let request = try PictureStateRequest(localAddress: local, peer: saved.host)
                token.own { request.close() }
                try remote.sendAppLink(request.requestURL(toggle: toggle))
                self.replies.async {
                    do {
                        let state = try request.wait()
                        DispatchQueue.main.async {
                            guard self.generation == revision, !token.cancelled else { return }
                            self.pictureState = state; self.pictureBusy = false
                        }
                    } catch { self.pictureFailed(error, revision: revision, toggle: toggle) }
                }
            } catch { self.pictureFailed(error, revision: revision, toggle: toggle) }
        }
    }
    private nonisolated func pictureFailed(_ error: Error, revision: Int, toggle: Bool) {
        DispatchQueue.main.async {
            guard self.generation == revision else { return }
            self.pictureState = "unknown"; self.pictureBusy = false
            if toggle { self.detail = error.localizedDescription }
        }
    }
    private nonisolated func shortcutExpired(revision: Int, picture: Bool) {
        DispatchQueue.main.async {
            guard self.generation == revision else { return }
            if picture { self.pictureBusy = false } else { self.pending = max(0, self.pending - 1) }
            self.detail = "A widget control expired before it could be sent. It was not retried."
        }
    }
    private nonisolated func report(_ error: Error, revision: Int) {
        DispatchQueue.main.async {
            guard self.generation == revision else { return }
            let recoverableCode: Bool
            switch error {
            case TVProtocolError.invalidCode, TVProtocolError.codeMismatch: recoverableCode = true
            default: recoverableCode = false
            }
            if self.awaitingCode && !recoverableCode { self.reset(); self.code = "" }
            self.needsLocalNetworkAccess = (error as? TVProtocolError).map {
                if case .localNetworkDenied = $0 { return true }; return false
            } ?? false
            self.busy = false
            if self.dogBusy {
                self.dogBusy = false; self.dogMode = "unknown"
                self.dogStatus = "Could not check Dog Mode. Reconnect and check status again."
            }
            self.status = self.needsLocalNetworkAccess ? "Local Network access is blocked" : "Connection needs attention"
            self.detail = error.localizedDescription
        }
    }
    private nonisolated func commandFailed(_ error: Error, revision: Int) {
        DispatchQueue.main.async {
            guard self.generation == revision else { return }
            self.reset(); self.status = "Disconnected · press Sync to reconnect"
            self.detail = "\(error.localizedDescription) The command was not retried."
        }
    }
    func startHolding(_ key: Int) {
        guard canControl else { return }
        stopHolding(); heldCode = key; sendKey(key)
        repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.heldCode == key else { return }
                self.sendKey(key, repeating: true)
                self.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.125, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated {
                        guard let self, self.heldCode == key, self.canControl else { self?.stopHolding(); return }
                        self.sendKey(key, repeating: true)
                    }
                }
            }
        }
    }
    func stopHolding() { repeatTimer?.invalidate(); repeatTimer = nil; heldCode = nil }
}
