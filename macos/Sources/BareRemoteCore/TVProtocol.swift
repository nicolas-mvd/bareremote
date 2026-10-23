import CryptoKit
import Foundation
import Security

/// A TV-code pairing session. Its returned certificate may be saved only after finish succeeds.
public final class TVPairing: @unchecked Sendable {
    private let transport: TLSTransport
    private let clientCertificate: SecCertificate
    private let serverCertificate: SecCertificate
    private let operationLock = NSLock()
    private var finished = false

    private init(transport: TLSTransport, identity: ClientIdentity) throws {
        self.transport = transport
        self.clientCertificate = identity.certificate
        self.serverCertificate = try transport.peerCertificate()
    }

    public static func start(host: String, port: Int = 6467, identity: ClientIdentity,
                             cancelled: @escaping () -> Bool = { false }) throws -> TVPairing {
        let deadline = TLSTransport.deadline(after: 6)
        let transport = try TLSTransport.connect(host: host, port: port, pin: nil, identity: identity,
                                                 deadline: deadline, cancelled: cancelled)
        do {
            let pairing = try TVPairing(transport: transport, identity: identity)
            try pairing.send(10, body: Proto.Writer().text(1, "atvremote").text(2, "BareRemote Mac"), deadline: deadline)
            _ = try pairing.expect(11, deadline: deadline)
            let encoding = try Proto.Writer().number(1, 3).number(2, 6)
            try pairing.send(20, body: Proto.Writer().message(1, encoding).number(3, 1), deadline: deadline)
            _ = try pairing.expect(20, deadline: deadline)
            try pairing.send(30, body: Proto.Writer().message(1, encoding).number(2, 1), deadline: deadline)
            _ = try pairing.expect(31, deadline: deadline)
            guard !cancelled(), !Thread.current.isCancelled else { throw TVProtocolError.cancelled }
            return pairing
        } catch { transport.close(); throw error }
    }

    /// A locally mistyped code leaves this session open so the user can correct it.
    /// A transmitted secret always ends the session, whether acknowledged or rejected.
    public func finish(code: String) throws -> Data {
        operationLock.lock(); defer { operationLock.unlock() }
        guard !finished, transport.isOpen else { throw TVProtocolError.closed }
        let client = try Self.rsaComponents(clientCertificate)
        let server = try Self.rsaComponents(serverCertificate)
        var secret = try Self.secret(clientModulus: client.modulus, clientExponent: client.exponent,
                                     serverModulus: server.modulus, serverExponent: server.exponent, code: code)
        defer {
            secret.resetBytes(in: 0..<secret.count)
            close()
        }
        let deadline = TLSTransport.deadline(after: 6)
        try send(40, body: Proto.Writer().bytes(1, secret), deadline: deadline)
        let acknowledgment = try expect(41, deadline: deadline)
        if let echo = try acknowledgment.bytes(1), !echo.isEmpty, !constantTimeEqual(echo, secret) {
            throw TVProtocolError.malformed("The TV did not acknowledge the pairing secret.")
        }
        finished = true
        return SecCertificateCopyData(serverCertificate) as Data
    }

    public func close() { transport.close() }
    deinit { transport.close() }

    private func send(_ field: Int, body: Proto.Writer, deadline: UInt64) throws {
        try transport.writeFrame(Proto.Writer().number(1, 2).number(2, 200).message(field, body).build(), deadline: deadline)
    }

    private func expect(_ field: Int, deadline: UInt64) throws -> Proto.Message {
        let response = try Proto.parse(transport.readFrame(deadline: deadline))
        guard try response.integer(1) == 2, try response.integer(2) == 200 else {
            throw TVProtocolError.malformed("The TV rejected pairing. Start again and check the code.")
        }
        return try response.message(field)
    }

    /// Google's Polo challenge: unsigned RSA values from both certificates, then two nonce bytes.
    static func secret(clientModulus: Data, clientExponent: Data, serverModulus: Data,
                       serverExponent: Data, code: String) throws -> Data {
        let codeBytes = [UInt8](code.utf8)
        guard codeBytes.count == 6 else { throw TVProtocolError.invalidCode }
        func nibble(_ byte: UInt8) throws -> UInt8 {
            switch byte {
            case 48...57: return byte - 48
            case 65...70: return byte - 65 + 10
            case 97...102: return byte - 97 + 10
            default: throw TVProtocolError.invalidCode
            }
        }
        var decoded: [UInt8] = []
        for offset in stride(from: 0, to: 6, by: 2) {
            decoded.append(try nibble(codeBytes[offset]) << 4 | nibble(codeBytes[offset + 1]))
        }
        var hash = SHA256()
        for value in [clientModulus, clientExponent, serverModulus, serverExponent] {
            let unsigned = value.drop(while: { $0 == 0 })
            guard !unsigned.isEmpty else { throw TVProtocolError.malformed("TV pairing requires positive RSA values.") }
            hash.update(data: unsigned)
        }
        hash.update(data: Data(decoded[1...2]))
        var digest = Data(hash.finalize())
        guard digest[0] == decoded[0] else {
            digest.resetBytes(in: 0..<digest.count)
            throw TVProtocolError.codeMismatch
        }
        return digest
    }

    private static func rsaComponents(_ certificate: SecCertificate) throws -> (modulus: Data, exponent: Data) {
        guard let key = SecCertificateCopyKey(certificate),
              let attributes = SecKeyCopyAttributes(key) as? [String: Any],
              let keyType = attributes[kSecAttrKeyType as String] as? String,
              keyType == (kSecAttrKeyTypeRSA as String) else {
            throw TVProtocolError.unsupported("TV pairing requires RSA certificates.")
        }
        var error: Unmanaged<CFError>?
        guard let representation = SecKeyCopyExternalRepresentation(key, &error) else {
            throw TVProtocolError.connection("Could not read the TV’s RSA public key.")
        }
        // Security exports an RSA public key as the PKCS#1 RSAPublicKey sequence.
        var reader = RSAReader(bytes: [UInt8](representation as Data))
        let sequence = try reader.value(tag: 0x30)
        guard reader.atEnd else { throw TVProtocolError.malformed("Invalid RSA public key") }
        var integers = RSAReader(bytes: sequence)
        let modulus = try integers.positiveInteger()
        let exponent = try integers.positiveInteger()
        guard integers.atEnd else { throw TVProtocolError.malformed("Invalid RSA public key") }
        return (modulus, exponent)
    }

    private struct RSAReader {
        let bytes: [UInt8]
        var offset = 0
        var atEnd: Bool { offset == bytes.count }

        mutating func value(tag: UInt8) throws -> [UInt8] {
            guard offset + 2 <= bytes.count, bytes[offset] == tag else { throw TVProtocolError.malformed("Invalid RSA public key") }
            offset += 1
            var length = Int(bytes[offset]); offset += 1
            if length & 0x80 != 0 {
                let count = length & 0x7f
                guard count > 0, count <= 4, offset + count <= bytes.count else { throw TVProtocolError.malformed("Invalid RSA key length") }
                length = 0
                for _ in 0..<count { length = length << 8 | Int(bytes[offset]); offset += 1 }
            }
            guard length <= bytes.count - offset else { throw TVProtocolError.malformed("Truncated RSA public key") }
            let result = Array(bytes[offset..<(offset + length)])
            offset += length
            return result
        }

        mutating func positiveInteger() throws -> Data {
            let integer = try value(tag: 2)
            guard let first = integer.first, first & 0x80 == 0 else { throw TVProtocolError.malformed("Invalid RSA integer") }
            let unsigned = integer.drop(while: { $0 == 0 })
            guard !unsigned.isEmpty else { throw TVProtocolError.malformed("Invalid RSA integer") }
            return Data(unsigned)
        }
    }
}

/// One authenticated remote session. Commands are serialized and never retried.
public final class TVRemote: @unchecked Sendable {
    private static let requestedFeatures: UInt64 = 1 | 2 | 4 | 32 | 64 | 512
    private static let disconnectQueue = DispatchQueue(label: "uy.nico.bareremote.disconnect")
    private let transport: TLSTransport
    private let cancellation: () -> Bool
    private let outputLock = NSLock()
    private let commandLock = NSLock()
    private let state = NSCondition()
    private let keyboardQueue = DispatchQueue(label: "uy.nico.bareremote.keyboard")
    private var keyboard = TVKeyboardTracker()
    private var keyboardCallback: ((TVKeyboardState) -> Void)?
    private var keyboardNotificationPending = false
    private var features: UInt64 = 0
    private var configured = false
    private var closed = false
    private var unexpectedDisconnect = false
    private var disconnectCallback: (() -> Void)?
    private var lastCommandWrite: UInt64 = 0

    private init(transport: TLSTransport, cancelled: @escaping () -> Bool) {
        self.transport = transport
        self.cancellation = cancelled
    }

    /// Invoked off the UI thread on connection loss, including an idle/read deadline.
    /// Explicit close does not invoke this callback. Setting it after a failure invokes it once.
    public var onDisconnect: (() -> Void)? {
        get { state.lock(); defer { state.unlock() }; return disconnectCallback }
        set {
            state.lock()
            disconnectCallback = newValue
            let notify = unexpectedDisconnect
            state.unlock()
            if notify, let newValue {
                Self.disconnectQueue.async(execute: DispatchWorkItem(block: newValue))
            }
        }
    }

    public static func connect(host: String, port: Int = 6466, pin: Data, identity: ClientIdentity,
                               cancelled: @escaping () -> Bool = { false }) throws -> TVRemote {
        guard !pin.isEmpty else { throw TVProtocolError.unsupported("Pair with the TV first.") }
        let deadline = TLSTransport.deadline(after: 5)
        let transport = try TLSTransport.connect(host: host, port: port, pin: pin, identity: identity,
                                                 deadline: deadline, cancelled: cancelled)
        let remote = TVRemote(transport: transport, cancelled: cancelled)
        do {
            var started = false
            for _ in 0..<64 {
                if try remote.handle(Proto.parse(transport.readFrame(deadline: deadline)), deadline: deadline) { started = true; break }
            }
            guard started else { throw TVProtocolError.malformed("Too many TV handshake messages") }
            let reader = Thread { [weak remote] in
                do {
                    while remote?.isOpen == true {
                        // Do not retain the remote across a blocking read. Dropping the
                        // last owner must allow deinit to cancel an otherwise idle session.
                        let frame = try transport.readFrame(deadline: TLSTransport.deadline(after: 16))
                        guard let remote else { return }
                        _ = try remote.handle(Proto.parse(frame))
                    }
                } catch { remote?.connectionFailed() }
            }
            reader.name = "BareRemote-TV"
            reader.start()
            // RemoteStart can precede registration of the TV's virtual input device.
            try remote.waitUntil(min(deadline, TLSTransport.deadline(after: 0.250)), operationDeadline: deadline)
            return remote
        } catch { remote.close(); throw error }
    }

    public var isOpen: Bool {
        state.lock(); let open = !closed; state.unlock()
        return open && transport.isOpen
    }

    public var localAddress: String? { transport.localAddress }

    public var keyboardState: TVKeyboardState {
        state.lock(); defer { state.unlock() }
        return keyboard.snapshot
    }

    /// Invoked serially off the UI thread with the latest transient state,
    /// including an initial snapshot and a cleared snapshot on close. Updates
    /// coalesce under load; showRequestID preserves the explicit show event.
    public var onKeyboardStateChanged: ((TVKeyboardState) -> Void)? {
        get { state.lock(); defer { state.unlock() }; return keyboardCallback }
        set {
            state.lock()
            keyboardCallback = newValue
            scheduleKeyboardNotificationLocked()
            state.unlock()
        }
    }

    /// Commits one native text input chunk at the TV's current cursor/selection.
    /// It never replaces the entire field or retries an uncertain write.
    public func sendText(_ text: String, context: TVKeyboardContext,
                         deadline: TimeInterval? = nil) throws {
        let operationDeadline = try keyboardDeadline(deadline)
        // Validate bounds before joining the serialized writer; the message is
        // encoded again with the latest content revision and cursor under the state lock.
        _ = try TVKeyboardTracker.textMessage(text, context: context)
        try sendKeyboard(context: context, deadline: operationDeadline, committedText: text) { current in
            try TVKeyboardTracker.textMessage(text, context: current)
        }
    }

    /// Cursor, delete, Tab and Enter commands scoped to a captured TV field.
    public func sendKeyboardKey(_ code: Int, context: TVKeyboardContext,
                                deadline: TimeInterval? = nil) throws {
        guard TVKeyboardTracker.allowedKeys.contains(code) else {
            throw TVProtocolError.unsupported("Unsupported keyboard key.")
        }
        let operationDeadline = try keyboardDeadline(deadline)
        let message = try Proto.Writer().message(10, Proto.Writer().number(1, UInt64(code)).number(2, 3))
        try sendKeyboard(context: context, deadline: operationDeadline, keyboardKey: code) { _ in message }
    }

    public func sendKey(_ code: Int) throws {
        commandLock.lock(); defer { commandLock.unlock() }
        try sendKey(code, direction: 3)
    }

    public func sendLongKey(_ code: Int) throws {
        commandLock.lock(); defer { commandLock.unlock() }
        try sendKey(code, direction: 1)
        var holdFailure: Error?
        do {
            let releaseAt = TLSTransport.deadline(after: 0.650)
            try waitUntil(releaseAt, operationDeadline: TLSTransport.deadline(after: 1))
        } catch { holdFailure = error }
        // Always attempt a bounded release, even if cancellation ended the hold early.
        try sendKey(code, direction: 2, ignoreCancellation: true)
        if let holdFailure { throw holdFailure }
    }

    public func sendAppLink(_ uri: String) throws {
        try BareHomeLink.validate(uri)
        commandLock.lock(); defer { commandLock.unlock() }
        state.lock(); let supportsLinks = features & 512 != 0; state.unlock()
        guard supportsLinks else { throw TVProtocolError.unsupported("This TV remote service does not support BareHome commands.") }
        try send(Proto.Writer().message(90, Proto.Writer().text(1, uri)))
        recordCommand()
    }

    /// Keeps a one-shot session alive for TV input dispatch. It is a grace period, not an ACK.
    public func awaitCommandDeliveryWindow() throws {
        state.lock(); let last = lastCommandWrite; state.unlock()
        guard last > 0 else { throw TVProtocolError.malformed("No TV command has been sent.") }
        try waitUntil(last + 750_000_000, operationDeadline: TLSTransport.deadline(after: 1))
    }

    public func close() {
        state.lock()
        guard !closed else { state.unlock(); transport.close(); return }
        closed = true
        keyboard.close()
        scheduleKeyboardNotificationLocked()
        state.broadcast()
        state.unlock()
        transport.close()
    }

    deinit { transport.close() }

    private func sendKey(_ code: Int, direction: UInt64, ignoreCancellation: Bool = false) throws {
        guard (1...304).contains(code) else { throw TVProtocolError.unsupported("Unsupported Android key code.") }
        try send(Proto.Writer().message(10, Proto.Writer().number(1, UInt64(code)).number(2, direction)), ignoreCancellation: ignoreCancellation)
        recordCommand()
    }

    private func recordCommand() {
        state.lock()
        lastCommandWrite = DispatchTime.now().uptimeNanoseconds
        state.broadcast()
        state.unlock()
    }

    private func handle(_ message: Proto.Message, deadline: UInt64? = nil) throws -> Bool {
        if message.has(3) { throw TVProtocolError.connection("The TV reported a remote protocol error.") }
        if message.has(1) {
            let negotiated = try message.message(1).integer(1) & Self.requestedFeatures
            guard negotiated & 2 != 0 else { throw TVProtocolError.unsupported("The TV remote service does not support keys.") }
            let device = try Proto.Writer().number(3, 1).text(4, "1").text(5, "uy.nico.bareremote.macos").text(6, "0.3.1")
            try send(Proto.Writer().message(1, Proto.Writer().number(1, negotiated).message(2, device)), deadline: deadline)
            state.lock()
            features = negotiated
            configured = true
            keyboard.configure(supported: negotiated & 4 != 0)
            scheduleKeyboardNotificationLocked()
            state.unlock()
        }
        if message.has(2) {
            state.lock(); let ready = configured; let features = self.features; state.unlock()
            guard ready else { throw TVProtocolError.malformed("TV remote configuration is missing.") }
            _ = try message.message(2)
            try send(Proto.Writer().message(2, Proto.Writer().number(1, features)), deadline: deadline)
        }
        if message.has(8) {
            let ping = try message.message(8).integer(1)
            try send(Proto.Writer().message(9, Proto.Writer().number(1, ping)), deadline: deadline)
        }
        if (20...24).contains(where: message.has) {
            state.lock()
            do {
                let before = keyboard.snapshot
                try keyboard.receive(message)
                if before != keyboard.snapshot { scheduleKeyboardNotificationLocked() }
                state.unlock()
            } catch {
                state.unlock()
                throw error
            }
        }
        if message.has(40) {
            state.lock(); let ready = configured; state.unlock()
            guard ready else { throw TVProtocolError.malformed("TV remote configuration is missing.") }
            _ = try message.message(40)
            // started=false is standby; the connection is ready to receive a wake key.
            return true
        }
        return false
    }

    private func send(_ message: Proto.Writer, ignoreCancellation: Bool = false, deadline: UInt64? = nil) throws {
        let data = try message.build()
        outputLock.lock(); defer { outputLock.unlock() }
        guard isOpen else { throw TVProtocolError.closed }
        do {
            try transport.writeFrame(data, deadline: deadline ?? TLSTransport.deadline(after: 5), ignoreCancellation: ignoreCancellation)
        } catch { connectionFailed(); throw error }
    }

    private func waitUntil(_ target: UInt64, operationDeadline: UInt64) throws {
        state.lock(); defer { state.unlock() }
        while true {
            guard !closed, transport.isOpen else { throw TVProtocolError.closed }
            guard !cancellation(), !Thread.current.isCancelled else { throw TVProtocolError.cancelled }
            let now = DispatchTime.now().uptimeNanoseconds
            guard now < operationDeadline else { throw TVProtocolError.timedOut }
            if now >= target { return }
            _ = state.wait(until: Date(timeIntervalSinceNow: min(0.025, Double(target - now) / 1_000_000_000)))
        }
    }

    private func connectionFailed() {
        state.lock()
        let callback = !closed ? disconnectCallback : nil
        if !closed {
            unexpectedDisconnect = true
            keyboard.close()
            scheduleKeyboardNotificationLocked()
        }
        closed = true
        state.broadcast()
        state.unlock()
        transport.close()
        if let callback {
            Self.disconnectQueue.async(execute: DispatchWorkItem(block: callback))
        }
    }

    private func keyboardDeadline(_ requested: TimeInterval?) throws -> UInt64 {
        let remaining = (requested ?? (ProcessInfo.processInfo.systemUptime + 5)) - ProcessInfo.processInfo.systemUptime
        guard remaining.isFinite, remaining > 0 else { throw TVProtocolError.timedOut }
        return TLSTransport.deadline(after: min(5, remaining))
    }

    private func sendKeyboard(context: TVKeyboardContext, deadline: UInt64, committedText: String? = nil,
                              keyboardKey: Int? = nil,
                              makeMessage: (TVKeyboardContext) throws -> Proto.Writer) throws {
        commandLock.lock(); defer { commandLock.unlock() }
        outputLock.lock(); defer { outputLock.unlock() }
        guard isOpen else { throw TVProtocolError.closed }
        guard !cancellation(), !Thread.current.isCancelled else { throw TVProtocolError.cancelled }
        guard DispatchTime.now().uptimeNanoseconds < deadline else { throw TVProtocolError.timedOut }
        state.lock()
        let data: Data
        do {
            let current = try keyboard.currentContext(matching: context, requireSelection: committedText != nil)
            data = try makeMessage(current).build()
            guard !cancellation(), !Thread.current.isCancelled else { throw TVProtocolError.cancelled }
            guard DispatchTime.now().uptimeNanoseconds < deadline else { throw TVProtocolError.timedOut }
            if let committedText { keyboard.committed(committedText, context: current) }
            else if let keyboardKey { keyboard.keyWritten(keyboardKey); scheduleKeyboardNotificationLocked() }
            state.unlock()
        } catch { state.unlock(); throw error }
        do { try transport.writeFrame(data, deadline: deadline) }
        catch { connectionFailed(); throw error }
        recordCommand()
    }

    /// Caller holds state. At most one delivery is queued, so an IME flood cannot
    /// build an unbounded queue of snapshots or retain private field contents.
    private func scheduleKeyboardNotificationLocked() {
        guard keyboardCallback != nil, !keyboardNotificationPending else { return }
        keyboardNotificationPending = true
        keyboardQueue.async { [weak self] in
            guard let self else { return }
            self.state.lock()
            let callback = self.keyboardCallback
            let snapshot = self.keyboard.snapshot
            self.keyboardNotificationPending = false
            self.state.unlock()
            callback?(snapshot)
        }
    }
}
