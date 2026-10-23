import Foundation
import Network
import Security

public enum TVProtocolError: LocalizedError {
    case invalidAddress
    case invalidCode
    case codeMismatch
    case identityChanged
    case timedOut
    case cancelled
    case closed
    case unsupported(String)
    case malformed(String)
    case connection(String)
    case localNetworkDenied

    public var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Enter the TV’s numeric IP address."
        case .invalidCode: return "Enter the six hexadecimal characters shown on your TV."
        case .codeMismatch: return "The code does not match this TV. Check all six characters."
        case .identityChanged: return "The TV’s identity changed. Pair again using a TV code."
        case .timedOut: return "The TV connection timed out."
        case .cancelled: return "The TV operation was cancelled."
        case .closed: return "The TV connection is closed."
        case .localNetworkDenied:
            return "Allow BareRemote in System Settings → Privacy & Security → Local Network, then try again."
        case .unsupported(let message), .malformed(let message), .connection(let message): return message
        }
    }
}

/// Establishment may wait for a usable path or the first local-network prompt.
/// Once TLS has become ready, a lost path ends this session instead of reconnecting it.
final class TLSConnectionReadiness: @unchecked Sendable {
    private let condition = NSCondition()
    private var becameReady = false
    private var closed = false
    private var failure: TVProtocolError?
    private var waitingError: NWError?
    private var waitingForLocalNetwork = false

    var isOpen: Bool {
        condition.lock(); defer { condition.unlock() }
        return becameReady && !closed
    }

    /// Returns true when the caller must cancel the underlying connection.
    @discardableResult
    func update(_ status: NWConnection.State, localNetworkDenied: Bool = false) -> Bool {
        condition.lock(); defer { condition.unlock() }
        // Cancellation, failure and expiry are terminal even if an already queued
        // ready callback arrives later.
        guard !closed else { return false }
        var cancel = false
        switch status {
        case .ready:
            becameReady = true
            waitingError = nil
            waitingForLocalNetwork = false
        case .waiting(let error):
            if becameReady {
                failure = Self.networkFailure(error, localNetworkDenied: localNetworkDenied)
                closed = true
                cancel = true
            } else {
                waitingError = error
                waitingForLocalNetwork = localNetworkDenied
            }
        case .preparing:
            // Preparation can run while the path is still prohibited. Keep
            // the current path's diagnosis, while discarding its old error.
            waitingError = nil
            waitingForLocalNetwork = localNetworkDenied
        case .failed(let error):
            failure = Self.networkFailure(error, localNetworkDenied: localNetworkDenied)
            closed = true
        case .cancelled:
            closed = true
        default: break
        }
        condition.broadcast()
        return cancel
    }

    func close() {
        condition.lock()
        closed = true
        condition.broadcast()
        condition.unlock()
    }

    func wait(deadline: UInt64, cancelled: () -> Bool, trustMismatch: () -> Bool) throws {
        condition.lock(); defer { condition.unlock() }
        while !becameReady || closed {
            if trustMismatch() { closed = true; throw TVProtocolError.identityChanged }
            if let failure { throw failure }
            if closed { throw TVProtocolError.closed }
            if cancelled() || Thread.current.isCancelled { closed = true; throw TVProtocolError.cancelled }
            guard DispatchTime.now().uptimeNanoseconds < deadline else {
                closed = true
                if waitingForLocalNetwork { throw TVProtocolError.localNetworkDenied }
                if let waitingError {
                    throw Self.networkFailure(waitingError, localNetworkDenied: false)
                }
                throw TVProtocolError.timedOut
            }
            _ = condition.wait(until: Date(timeIntervalSinceNow: 0.025))
        }
    }

    static func networkFailure(_ error: NWError, localNetworkDenied: Bool) -> TVProtocolError {
        if localNetworkDenied { return .localNetworkDenied }
        return .connection("Could not connect to the TV: \(error.localizedDescription)")
    }
}

/// Blocking operations are worker-only. Network callbacks always run on a separate queue.
/// Closing cancels the TCP/TLS connection immediately and releases pending operations.
final class TLSTransport: @unchecked Sendable {
    private final class TrustCapture {
        let lock = NSLock()
        var certificate: SecCertificate?
        var mismatch = false
    }

    private final class Completion<Value> {
        let condition = NSCondition()
        var result: Result<Value, Error>?
        func complete(_ result: Result<Value, Error>) {
            condition.lock()
            self.result = result
            condition.broadcast()
            condition.unlock()
        }
    }

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let readiness = TLSConnectionReadiness()
    private let trust: TrustCapture
    private let cancellation: () -> Bool

    private init(connection: NWConnection, queue: DispatchQueue, trust: TrustCapture, cancelled: @escaping () -> Bool) {
        self.connection = connection
        self.queue = queue
        self.trust = trust
        self.cancellation = cancelled
        connection.stateUpdateHandler = { [weak self] status in
            guard let self else { return }
            let denied = self.connection.currentPath?.unsatisfiedReason == .localNetworkDenied
            if self.readiness.update(status, localNetworkDenied: denied) { self.connection.cancel() }
        }
    }

    static func deadline(after seconds: TimeInterval) -> UInt64 {
        DispatchTime.now().uptimeNanoseconds + UInt64(seconds * 1_000_000_000)
    }

    static func connect(host: String, port: Int, pin: Data?, identity: ClientIdentity,
                        deadline: UInt64, cancelled: @escaping () -> Bool) throws -> TLSTransport {
        guard !host.isEmpty, host.utf8.count <= 253, (1...65535).contains(port) else { throw TVProtocolError.invalidAddress }
        let endpointHost: NWEndpoint.Host
        if let address = IPv4Address(host) { endpointHost = .ipv4(address) }
        else if let address = IPv6Address(host) { endpointHost = .ipv6(address) }
        else { throw TVProtocolError.invalidAddress }
        guard pin == nil || !(pin?.isEmpty ?? true) else { throw TVProtocolError.identityChanged }
        guard !cancelled(), !Thread.current.isCancelled else { throw TVProtocolError.cancelled }
        let tls = NWProtocolTLS.Options()
        guard let localIdentity = sec_identity_create(identity.identity) else {
            throw TVProtocolError.connection("The Mac pairing identity could not be loaded.")
        }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        // Every connection must present its leaf certificate for an explicit pin check.
        sec_protocol_options_set_tls_resumption_enabled(tls.securityProtocolOptions, false)
        sec_protocol_options_set_tls_tickets_enabled(tls.securityProtocolOptions, false)
        let queue = DispatchQueue(label: "uy.nico.bareremote.tls.\(UUID().uuidString)")
        let trust = TrustCapture()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, peerTrust, complete in
            let value = sec_trust_copy_ref(peerTrust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(value) as? [SecCertificate], let leaf = chain.first else {
                complete(false)
                return
            }
            let der = SecCertificateCopyData(leaf) as Data
            let matches = pin.map { constantTimeEqual($0, der) } ?? true
            trust.lock.lock()
            trust.certificate = leaf
            trust.mismatch = !matches
            trust.lock.unlock()
            // Only explicit first pairing passes nil. Every normal connection checks the exact leaf DER.
            complete(matches)
        }, queue)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: tls, tcp: tcp)
        let connection = NWConnection(host: endpointHost, port: NWEndpoint.Port(rawValue: UInt16(port))!, using: parameters)
        let transport = TLSTransport(connection: connection, queue: queue, trust: trust, cancelled: cancelled)
        connection.start(queue: queue)
        do {
            try transport.awaitReady(deadline: deadline)
            // Defense in depth before any application protocol processing.
            if let pin, !constantTimeEqual(pin, SecCertificateCopyData(try transport.peerCertificate()) as Data) {
                throw TVProtocolError.identityChanged
            }
            return transport
        } catch {
            transport.close()
            throw error
        }
    }

    var isOpen: Bool {
        readiness.isOpen
    }

    var localAddress: String? {
        guard case .hostPort(let host, _) = connection.currentPath?.localEndpoint else { return nil }
        switch host {
        case .ipv4(let address): return "\(address)"
        case .ipv6(let address): return "\(address)"
        default: return nil
        }
    }

    func peerCertificate() throws -> SecCertificate {
        trust.lock.lock(); defer { trust.lock.unlock() }
        guard let certificate = trust.certificate else { throw TVProtocolError.connection("The TV did not provide a certificate.") }
        return certificate
    }

    func readFrame(deadline: UInt64) throws -> Data {
        do {
            var prefix = [UInt8]()
            for _ in 0..<10 {
                let byte = try read(count: 1, deadline: deadline)[0]
                prefix.append(byte)
                if byte & 0x80 == 0 {
                    var offset = 0
                    let count = try Proto.readVarint(prefix, offset: &offset)
                    guard count > 0, count <= UInt64(Proto.maxFrame) else { throw TVProtocolError.malformed("Invalid frame length") }
                    return try read(count: Int(count), deadline: deadline)
                }
            }
            throw TVProtocolError.malformed("Varint overflow")
        } catch { close(); throw error }
    }

    func writeFrame(_ data: Data, deadline: UInt64, ignoreCancellation: Bool = false) throws {
        let framed = try Proto.frame(data)
        try check(deadline: deadline, ignoreCancellation: ignoreCancellation)
        let result = Completion<Void>()
        connection.send(content: framed, completion: .contentProcessed { error in
            if let error { result.complete(.failure(error)) }
            else { result.complete(.success(())) }
        })
        do { try wait(result, deadline: deadline, ignoreCancellation: ignoreCancellation) }
        catch { close(); throw error }
    }

    func close() {
        readiness.close()
        connection.cancel()
    }

    deinit { connection.cancel() }

    private func awaitReady(deadline: UInt64) throws {
        try readiness.wait(deadline: deadline, cancelled: cancellation) {
            self.trust.lock.lock(); defer { self.trust.lock.unlock() }
            return self.trust.mismatch
        }
    }

    private func read(count: Int, deadline: UInt64) throws -> Data {
        var bytes = Data()
        while bytes.count < count {
            try check(deadline: deadline)
            let result = Completion<Data>()
            connection.receive(minimumIncompleteLength: 1, maximumLength: count - bytes.count) { data, _, complete, error in
                if let data, !data.isEmpty { result.complete(.success(data)) }
                else if let error { result.complete(.failure(error)) }
                else if complete { result.complete(.failure(TVProtocolError.closed)) }
                else { result.complete(.failure(TVProtocolError.connection("The TV returned an empty network response."))) }
            }
            bytes.append(try wait(result, deadline: deadline))
        }
        return bytes
    }

    private func wait<Value>(_ completion: Completion<Value>, deadline: UInt64, ignoreCancellation: Bool = false) throws -> Value {
        completion.condition.lock()
        defer { completion.condition.unlock() }
        while completion.result == nil {
            try check(deadline: deadline, ignoreCancellation: ignoreCancellation)
            _ = completion.condition.wait(until: Date(timeIntervalSinceNow: 0.025))
        }
        return try completion.result!.get()
    }

    private func check(deadline: UInt64, ignoreCancellation: Bool = false) throws {
        if !ignoreCancellation && (cancellation() || Thread.current.isCancelled) { throw TVProtocolError.cancelled }
        guard isOpen else { throw TVProtocolError.closed }
        guard DispatchTime.now().uptimeNanoseconds < deadline else { throw TVProtocolError.timedOut }
    }
}

func constantTimeEqual(_ first: Data, _ second: Data) -> Bool {
    guard first.count == second.count else { return false }
    return zip(first, second).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
}
