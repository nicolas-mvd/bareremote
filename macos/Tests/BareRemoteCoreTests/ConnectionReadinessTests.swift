import Foundation
import Network
import XCTest
@testable import BareRemoteCore

final class ConnectionReadinessTests: XCTestCase {
    func testInitialWaitingCanPrepareAndBecomeReady() throws {
        let readiness = TLSConnectionReadiness()
        XCTAssertFalse(readiness.update(.waiting(.posix(.ENETDOWN)), localNetworkDenied: true))
        XCTAssertFalse(readiness.isOpen)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) {
            XCTAssertFalse(readiness.update(.preparing))
            XCTAssertFalse(readiness.update(.ready))
        }
        try readiness.wait(deadline: TLSTransport.deadline(after: 1), cancelled: { false }, trustMismatch: { false })
        XCTAssertTrue(readiness.isOpen)
    }

    func testInitialWaitingEndsPromptlyOnFailure() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)))
        readiness.update(.failed(.posix(.ECONNREFUSED)))
        XCTAssertThrowsError(try readiness.wait(deadline: TLSTransport.deadline(after: 4), cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.connection(let detail) = error else { return XCTFail("Unexpected error: \(error)") }
            XCTAssertTrue(detail.contains(NWError.posix(.ECONNREFUSED).localizedDescription))
        }
        readiness.update(.ready)
        XCTAssertFalse(readiness.isOpen)
    }

    func testWaitingAfterReadyEndsSessionWithoutRecovery() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.ready)
        XCTAssertTrue(readiness.isOpen)
        XCTAssertTrue(readiness.update(.waiting(.posix(.ENETDOWN))))
        XCTAssertFalse(readiness.isOpen)
        readiness.update(.preparing)
        readiness.update(.ready)
        XCTAssertFalse(readiness.isOpen, "A lost authenticated session must never silently recover")
        XCTAssertThrowsError(try readiness.wait(deadline: TLSTransport.deadline(after: 1), cancelled: { false }, trustMismatch: { false }))
    }

    func testPermissionDenialAtDeadlineHasSpecificAction() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)), localNetworkDenied: true)
        XCTAssertThrowsError(try readiness.wait(deadline: 0, cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.localNetworkDenied = error else { return XCTFail("Unexpected error: \(error)") }
            XCTAssertTrue(error.localizedDescription.contains("Privacy & Security"))
        }
        readiness.update(.ready)
        XCTAssertFalse(readiness.isOpen, "An expired request cannot become ready later")
    }

    func testNetworkDownAloneDoesNotClaimPermissionDenial() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)))
        XCTAssertThrowsError(try readiness.wait(deadline: 0, cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.connection(let detail) = error else { return XCTFail("Unexpected error: \(error)") }
            XCTAssertTrue(detail.contains(NWError.posix(.ENETDOWN).localizedDescription))
        }
    }

    func testInitialWaitingRetainsItsOriginalBoundedDeadline() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)))
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try readiness.wait(deadline: TLSTransport.deadline(after: 0.05), cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.connection = error else { return XCTFail("Unexpected error: \(error)") }
        }
        XCTAssertGreaterThanOrEqual(ProcessInfo.processInfo.systemUptime - began, 0.04)
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 0.5)
        readiness.update(.ready)
        XCTAssertFalse(readiness.isOpen)
    }

    func testPreparingClearsOldPermissionDiagnostic() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)), localNetworkDenied: true)
        readiness.update(.preparing)
        XCTAssertThrowsError(try readiness.wait(deadline: 0, cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.timedOut = error else { return XCTFail("Unexpected error: \(error)") }
        }
    }

    func testPreparingPreservesCurrentPermissionDenial() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)), localNetworkDenied: true)
        readiness.update(.preparing, localNetworkDenied: true)
        XCTAssertThrowsError(try readiness.wait(deadline: 0, cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.localNetworkDenied = error else { return XCTFail("Unexpected error: \(error)") }
        }
    }

    func testPreparingCanReportPermissionDenialWithoutWaitingError() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.preparing, localNetworkDenied: true)
        XCTAssertThrowsError(try readiness.wait(deadline: 0, cancelled: { false }, trustMismatch: { false })) { error in
            guard case TVProtocolError.localNetworkDenied = error else { return XCTFail("Unexpected error: \(error)") }
        }
    }

    func testWaitingCancellationIsTerminal() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)))
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try readiness.wait(deadline: TLSTransport.deadline(after: 4), cancelled: { true }, trustMismatch: { false })) { error in
            guard case TVProtocolError.cancelled = error else { return XCTFail("Unexpected error: \(error)") }
        }
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 0.5)
        readiness.update(.ready)
        XCTAssertFalse(readiness.isOpen)
    }

    func testCloseWakesWaitingConnection() {
        let readiness = TLSConnectionReadiness()
        readiness.update(.waiting(.posix(.ENETDOWN)))
        let completed = expectation(description: "Closed establishment exits")
        DispatchQueue.global().async {
            do {
                try readiness.wait(deadline: TLSTransport.deadline(after: 4), cancelled: { false }, trustMismatch: { false })
                XCTFail("A closed connection must not become ready")
            } catch {
                if case TVProtocolError.closed = error { }
                else { XCTFail("Unexpected error: \(error)") }
            }
            completed.fulfill()
        }
        readiness.close()
        wait(for: [completed], timeout: 0.5)
        readiness.update(.ready)
        XCTAssertFalse(readiness.isOpen)
    }
}
