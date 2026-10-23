import Foundation
import XCTest
@testable import BareRemote
@testable import BareRemoteCore

final class TVConnectionRecoveryTests: XCTestCase {
    private final class Session {
        var open = true
        var closes = 0
        func close() { open = false; closes += 1 }
    }
    private let old = "192.168.1.9"
    private let moved = "192.168.1.25"

    private func candidates(_ hosts: [String]) -> TVRecoveryCandidates {
        let result = TVRecoveryCandidates()
        hosts.forEach { result.offer($0) }
        result.finish()
        return result
    }

    func testSavedAddressSuccessDoesNotBrowseOrTryAnotherConnection() throws {
        let session = Session()
        var attempts: [String] = [], scans = 0
        let result = try TVConnectionRecovery.connect(host: old, recover: true, cancelled: { false },
                                                       open: { (session: Session) in session.open }, close: { $0.close() }, connect: { host, timeout in
            attempts.append(host)
            XCTAssertEqual(timeout, 3)
            return session
        }, scan: { scans += 1; return self.candidates([self.moved]) })
        XCTAssertEqual(result.host, old)
        XCTAssertTrue(result.session === session)
        XCTAssertEqual(attempts, [old])
        XCTAssertEqual(scans, 0)
        XCTAssertEqual(session.closes, 0)
    }

    func testChangedAddressSkipsWrongCertificateAndUsesOriginalPin() throws {
        // Two TVs can advertise identical names. Only the exact saved pin used
        // by the connector can authorize the returned address.
        let wrong = "192.168.1.20"
        let savedPin = Data([1, 2, 3])
        let pins = [wrong: Data([9, 9, 9]), moved: savedPin]
        let session = Session()
        var attempts: [String] = [], scans = 0, stopped = 0
        let result = try TVConnectionRecovery.connect(host: old, recover: true, cancelled: { false },
                                                       open: { (session: Session) in session.open }, close: { $0.close() }, connect: { host, timeout in
            attempts.append(host)
            if host == self.old { throw TVProtocolError.timedOut }
            XCTAssertLessThanOrEqual(timeout, 2)
            guard pins[host] == savedPin else { throw TVProtocolError.identityChanged }
            return session
        }, scan: {
            scans += 1
            let found = TVRecoveryCandidates { stopped += 1 }
            [self.old, wrong, wrong, self.moved].forEach { found.offer($0) }
            found.finish()
            return found
        })
        XCTAssertEqual(result.host, moved)
        XCTAssertTrue(result.session === session)
        XCTAssertEqual(attempts, [old, wrong, moved])
        XCTAssertEqual(scans, 1)
        XCTAssertEqual(stopped, 1)
    }

    func testNoMatchPreservesOriginalAddressAndCapsVerificationAtFour() {
        var savedAddress = old
        var attempts: [String] = [], scans = 0
        XCTAssertThrowsError(try {
            let result: TVConnectionRecovery.Result<Session> = try TVConnectionRecovery.connect(
                host: old, recover: true, cancelled: { false }, open: { (session: Session) in session.open }, close: { $0.close() },
                connect: { host, _ in attempts.append(host); throw TVProtocolError.identityChanged },
                scan: {
                    scans += 1
                    return self.candidates((20...27).map { "192.168.1.\($0)" })
                })
            savedAddress = result.host
        }()) { error in
            XCTAssertTrue(error.localizedDescription.contains("quick network scan"))
        }
        XCTAssertEqual(savedAddress, old)
        XCTAssertEqual(scans, 1)
        XCTAssertEqual(attempts, [old] + (20...23).map { "192.168.1.\($0)" })
    }

    func testDiscoveryWithoutResultsEndsWithoutRetryingOriginalAddress() {
        var attempts: [String] = []
        XCTAssertThrowsError(try TVConnectionRecovery.connect(
            host: old, recover: true, cancelled: { false }, open: { (session: Session) in session.open },
            close: { $0.close() }, connect: { host, _ in
                attempts.append(host); throw TVProtocolError.connection("Offline")
            }, scan: { self.candidates([]) }))
        XCTAssertEqual(attempts, [old])
    }

    func testExplicitSetupAddressCheckDoesNotBrowseOnFailure() {
        var scans = 0
        XCTAssertThrowsError(try TVConnectionRecovery.connect(
            host: moved, recover: false, cancelled: { false }, open: { (session: Session) in session.open },
            close: { $0.close() }, connect: { _, _ in throw TVProtocolError.identityChanged },
            scan: { scans += 1; return self.candidates([]) })) { error in
                guard case TVProtocolError.identityChanged = error else { return XCTFail("Unexpected error: \(error)") }
            }
        XCTAssertEqual(scans, 0)
    }

    func testPermissionDenialDoesNotSpendTimeBrowsing() {
        var scans = 0
        XCTAssertThrowsError(try TVConnectionRecovery.connect(
            host: old, recover: true, cancelled: { false }, open: { (session: Session) in session.open },
            close: { $0.close() }, connect: { _, _ in throw TVProtocolError.localNetworkDenied },
            scan: { scans += 1; return self.candidates([]) })) { error in
                guard case TVProtocolError.localNetworkDenied = error else { return XCTFail("Unexpected error: \(error)") }
            }
        XCTAssertEqual(scans, 0)
    }

    func testCancellationClosesLateAuthenticatedSessionAndDoesNotCommitAddress() {
        var stopped = false, savedAddress = old
        let session = Session()
        XCTAssertThrowsError(try {
            let result = try TVConnectionRecovery.connect(host: old, recover: true, cancelled: { stopped },
                                                          open: { (session: Session) in session.open }, close: { $0.close() }, connect: { host, _ in
                if host == self.old { throw TVProtocolError.timedOut }
                stopped = true // A close/Sync races the candidate's ready callback.
                return session
            }, scan: { self.candidates([self.moved]) })
            savedAddress = result.host
        }()) { error in
            guard case TVProtocolError.cancelled = error else { return XCTFail("Unexpected error: \(error)") }
        }
        XCTAssertEqual(session.closes, 1)
        XCTAssertFalse(session.open)
        XCTAssertEqual(savedAddress, old)
    }

    func testCancellationWakesPendingDiscoveryAndRejectsLateResult() {
        let scanning = expectation(description: "Worker is waiting for Bonjour")
        let done = expectation(description: "Cancelled scan releases worker")
        let token = OperationGate()
        let found = TVRecoveryCandidates()
        token.own { found.stop() }
        DispatchQueue.global().async {
            do {
                let _: TVConnectionRecovery.Result<Session> = try TVConnectionRecovery.connect(
                    host: self.old, recover: true, cancelled: { token.cancelled },
                    open: { (session: Session) in session.open }, close: { $0.close() },
                    connect: { _, _ in throw TVProtocolError.timedOut }, scan: { scanning.fulfill(); return found })
                XCTFail("A cancelled scan cannot connect")
            } catch {
                guard case TVProtocolError.cancelled = error else { return XCTFail("Unexpected error: \(error)") }
            }
            done.fulfill()
        }
        wait(for: [scanning], timeout: 0.5)
        token.cancel()
        found.offer(moved)
        wait(for: [done], timeout: 0.5)
    }

    func testOuterDeadlineDoesNotRestartForEachCandidate() {
        var clock: TimeInterval = 100, timeouts: [TimeInterval] = []
        let late = Session()
        XCTAssertThrowsError(try TVConnectionRecovery.connect(
            host: old, recover: true, cancelled: { false }, now: { clock },
            open: { (session: Session) in session.open }, close: { $0.close() }, connect: { host, timeout in
                timeouts.append(timeout)
                clock += timeout
                if timeout < 2 { return late }
                throw TVProtocolError.timedOut
            }, scan: { self.candidates((20...27).map { "192.168.1.\($0)" }) }))
        XCTAssertEqual(timeouts, [3, 2, 2, 2, 1])
        XCTAssertEqual(clock, 110)
        XCTAssertEqual(late.closes, 1)
    }

    func testCandidateThatClosesBeforeReadyCannotCommitItsAddress() throws {
        let closed = Session(), ready = Session()
        closed.open = false
        let result = try TVConnectionRecovery.connect(
            host: old, recover: true, cancelled: { false }, open: { (session: Session) in session.open },
            close: { $0.close() }, connect: { host, _ in
                if host == self.old { throw TVProtocolError.timedOut }
                return host == "192.168.1.20" ? closed : ready
            }, scan: { self.candidates(["192.168.1.20", self.moved]) })
        XCTAssertEqual(closed.closes, 1)
        XCTAssertEqual(result.host, moved)
        XCTAssertTrue(result.session === ready)
    }

    func testNoResultWaitHonorsItsScanDeadline() {
        let started = ProcessInfo.processInfo.systemUptime
        let found = TVRecoveryCandidates()
        XCTAssertNil(try found.next(until: started + 0.04, cancelled: { false },
                                   now: { ProcessInfo.processInfo.systemUptime }))
        XCTAssertGreaterThanOrEqual(ProcessInfo.processInfo.systemUptime - started, 0.03)
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - started, 0.4)
    }

    func testDiscoveryRejectsDuplicateAndMalformedAddresses() throws {
        let found = candidates(["192.168.1.25", "192.168.1.25", "tv.local", "192.168.001.3", "192.168.1.26"])
        let now = ProcessInfo.processInfo.systemUptime
        XCTAssertEqual(try found.next(until: now, cancelled: { false }, now: { now }), "192.168.1.25")
        XCTAssertEqual(try found.next(until: now, cancelled: { false }, now: { now }), "192.168.1.26")
        XCTAssertNil(try found.next(until: now, cancelled: { false }, now: { now }))
    }
}
