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
    @Published var saved: SavedTV?
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
    var modeLabel: String { pictureState == "day" ? "Day confirmed" : pictureState == "night" ? "Night confirmed" : "Picture mode unconfirmed" }
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
        busy = false; pending = 0; pictureBusy = false; pictureState = "unknown"
        needsLocalNetworkAccess = false
        keyboardState = nil; keyboardMode = false; keyboardNotice = ""
        keyboardShowRequest = 0; keyboardRemoteOverride = nil
        invalidateKeyboardInput()
    }
    func disconnect() { reset(); status = saved == nil ? "Add your TV to get started" : "Disconnected" }
    func openSetup() { disconnect(); detail = ""; host = saved?.host ?? host; tvName = saved?.name ?? tvName; setupOpen = true }
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
    func sync() { guard let saved else { openSetup(); return }; connect(to: saved.host, name: saved.name, saveAddress: false, recoverAddress: true) }
    func reconnect() { connect(to: host.trimmingCharacters(in: .whitespacesAndNewlines), name: tvName, saveAddress: true) }
    private func connect(to address: String, name: String, saveAddress: Bool, recoverAddress: Bool = false) {
        guard let saved else { return }
        guard validateAddress(address) else { return }
        reset(); busy = true; status = "Connecting…"; detail = ""
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
                                self.reset(); self.status = "Disconnected · press Sync to reconnect"
                            }
                        }
                        self.remote = session; self.connected = true; self.busy = false
                        session.onKeyboardStateChanged = { [weak self] state in
                            DispatchQueue.main.async { self?.receiveKeyboard(state, revision: revision) }
                        }
                        self.setupOpen = false; self.status = "Connected"
                        // A queued toggle owns its one reply; do not race it with a startup query.
                        let hasToggle = self.shortcutQueue.contains { $0.action == .toggle }
                        self.flushShortcuts()
                        if !hasToggle { self.refreshPicture(toggle: false) }
                        if self.surfaces.isEmpty { self.scheduleIdleDisconnect() }
                    } catch { session.close(); self.busy = false; self.status = "Address was not saved"; self.detail = error.localizedDescription }
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
