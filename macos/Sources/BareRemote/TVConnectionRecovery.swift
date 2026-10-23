import BareRemoteCore
import Foundation

/// One connection attempt may browse once after its saved address fails. This
/// only establishes a session: it never sends, stores or retries a user action.
enum TVConnectionRecovery {
    static let initialTimeout: TimeInterval = 3
    static let scanTimeout: TimeInterval = 3
    static let candidateTimeout: TimeInterval = 2
    static let totalTimeout: TimeInterval = 10
    static let maximumCandidates = 4

    struct Result<Session> {
        let host: String
        let session: Session
    }

    /// `connect` must enforce the original saved certificate pin for every
    /// address. A Bonjour name is deliberately absent from this interface.
    static func connect<Session>(host: String, recover: Bool,
                                 cancelled: () -> Bool,
                                 now: () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
                                 open: (Session) -> Bool,
                                 close: (Session) -> Void,
                                 connect: (String, TimeInterval) throws -> Session,
                                 scan: () -> TVRecoveryCandidates) throws -> Result<Session> {
        let end = now() + totalTimeout
        func checked(_ address: String, timeout: TimeInterval) throws -> Result<Session> {
            guard !cancelled() else { throw TVProtocolError.cancelled }
            guard timeout > 0 else { throw TVProtocolError.timedOut }
            let session = try connect(address, timeout)
            guard !cancelled() else { close(session); throw TVProtocolError.cancelled }
            guard now() < end, open(session) else { close(session); throw TVProtocolError.closed }
            return Result(host: address, session: session)
        }
        do { return try checked(host, timeout: initialTimeout) }
        catch {
            if cancelled() { throw TVProtocolError.cancelled }
            if !recover { throw error }
            switch error {
            case TVProtocolError.cancelled, TVProtocolError.localNetworkDenied: throw error
            default: break
            }
        }
        guard now() < end else { throw TVProtocolError.timedOut }
        let candidates = scan()
        defer { candidates.stop() }
        let scanEnd = min(end, now() + scanTimeout)
        var attempted = Set([host])
        var verified = 0
        while verified < maximumCandidates, now() < end {
            guard !cancelled() else { throw TVProtocolError.cancelled }
            guard let address = try candidates.next(until: scanEnd, cancelled: cancelled, now: now) else { break }
            guard attempted.insert(address).inserted else { continue }
            verified += 1
            do { return try checked(address, timeout: min(candidateTimeout, end - now())) }
            catch {
                if cancelled() { throw TVProtocolError.cancelled }
                switch error {
                case TVProtocolError.cancelled, TVProtocolError.localNetworkDenied: throw error
                default: continue // Another advertised TV cannot replace the saved pin.
                }
            }
        }
        guard !cancelled() else { throw TVProtocolError.cancelled }
        throw TVProtocolError.connection("Could not find your paired TV after a quick network scan. Keep the TV on and on the same network, then try Sync again.")
    }
}

/// A small bridge from main-thread Bonjour callbacks to the serial connection
/// worker. Cancellation wakes the wait; late results are ignored after stop.
final class TVRecoveryCandidates: @unchecked Sendable {
    private let condition = NSCondition()
    private var hosts: [String] = []
    private var seen: Set<String> = []
    private var finished = false
    private var stopped = false
    private var onStop: (() -> Void)?

    init(onStop: (() -> Void)? = nil) { self.onStop = onStop }

    func offer(_ host: String) {
        condition.lock(); defer { condition.unlock() }
        guard !finished, !stopped, seen.count < 32,
              Self.validAddress(host), seen.insert(host).inserted else { return }
        hosts.append(host)
        condition.broadcast()
    }

    func finish() {
        condition.lock(); finished = true; condition.broadcast(); condition.unlock()
    }

    func stop() {
        condition.lock()
        stopped = true; finished = true; hosts.removeAll()
        let action = onStop; onStop = nil
        condition.broadcast(); condition.unlock()
        action?()
    }

    func next(until deadline: TimeInterval, cancelled: () -> Bool,
              now: () -> TimeInterval) throws -> String? {
        condition.lock(); defer { condition.unlock() }
        while true {
            guard !cancelled(), !stopped else { throw TVProtocolError.cancelled }
            // Already discovered candidates remain usable after browsing ends,
            // but the caller still caps verification by its outer deadline.
            if !hosts.isEmpty { return hosts.removeFirst() }
            let remaining = deadline - now()
            guard !finished, remaining > 0 else { return nil }
            condition.wait(until: Date(timeIntervalSinceNow: min(0.1, remaining)))
        }
    }

    private static func validAddress(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy {
            guard let number = UInt8($0) else { return false }
            return String(number) == $0
        }
    }
}
