import Foundation
import XCTest
@testable import BareRemoteCore

final class TransportIntegrationTests: XCTestCase {
    private var identity: ClientIdentity!
    private var namespace: String!
    private var pin = Data()
    private var rawPort = 0
    private var remotePort = 0

    override func setUpWithError() throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["BAREREMOTE_TEST_LOOPBACK"] == "1" else {
            throw XCTSkip("Run python3 Tests/test_loopback.py for TLS transport fault tests")
        }
        rawPort = try XCTUnwrap(Int(environment["BAREREMOTE_TEST_FAULT_PORT"] ?? ""))
        remotePort = try XCTUnwrap(Int(environment["BAREREMOTE_TEST_FAULT_REMOTE_PORT"] ?? ""))
        let certificatePath = try XCTUnwrap(environment["BAREREMOTE_TEST_CERTIFICATE_FILE"])
        pin = try Data(contentsOf: URL(fileURLWithPath: certificatePath))
        namespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        identity = try ClientIdentity.loadOrCreate(namespace: namespace)
    }

    override func tearDownWithError() throws {
        if let namespace { try ClientIdentity.delete(namespace: namespace) }
    }

    func testFragmentedAndCoalescedFramesOverActualTLS() throws {
        let transport = try open(mode: 1)
        defer { transport.close() }
        XCTAssertEqual(try transport.readFrame(deadline: TLSTransport.deadline(after: 2)), Data(repeating: 42, count: 300))
        XCTAssertEqual(try transport.readFrame(deadline: TLSTransport.deadline(after: 2)), Data([8, 1]))
    }

    func testMalformedNetworkFrameLengthsFailClosed() throws {
        for mode in [4, 5, 6] {
            let transport = try open(mode: mode)
            defer { transport.close() }
            XCTAssertThrowsError(try transport.readFrame(deadline: TLSTransport.deadline(after: 1)))
            XCTAssertFalse(transport.isOpen)
        }
    }

    func testPartialFrameHasOneBoundedDeadline() throws {
        let transport = try open(mode: 2)
        defer { transport.close() }
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try transport.readFrame(deadline: TLSTransport.deadline(after: 0.2)))
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 1)
        XCTAssertFalse(transport.isOpen)
    }

    func testClosePromptlyWakesPendingNetworkReader() throws {
        let transport = try open(mode: 3)
        defer { transport.close() }
        let completed = expectation(description: "Blocked read exits after close")
        DispatchQueue.global().async {
            do {
                _ = try transport.readFrame(deadline: TLSTransport.deadline(after: 4))
                XCTFail("A cancelled transport must not return a completed frame")
            } catch { }
            completed.fulfill()
        }
        Thread.sleep(forTimeInterval: 0.1)
        let began = ProcessInfo.processInfo.systemUptime
        transport.close()
        wait(for: [completed], timeout: 0.5)
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 0.5)
        XCTAssertFalse(transport.isOpen)
    }

    func testUnexpectedDisconnectNotifiesAndDisablesKeys() throws {
        let remote = try TVRemote.connect(host: "127.0.0.1", port: remotePort, pin: pin, identity: identity)
        defer { remote.close() }
        let disconnected = expectation(description: "Unexpected TLS shutdown is reported")
        disconnected.assertForOverFulfill = true
        remote.onDisconnect = { disconnected.fulfill() }
        wait(for: [disconnected], timeout: 2)
        XCTAssertFalse(remote.isOpen)
        XCTAssertThrowsError(try remote.sendKey(19))
    }

    func testExplicitCloseDoesNotReportUnexpectedDisconnect() throws {
        let remote = try TVRemote.connect(host: "127.0.0.1", port: remotePort, pin: pin, identity: identity)
        let disconnected = expectation(description: "Explicit close is intentional")
        disconnected.isInverted = true
        remote.onDisconnect = { disconnected.fulfill() }
        remote.close()
        XCTAssertFalse(remote.isOpen)
        wait(for: [disconnected], timeout: 0.6)
    }

    private func open(mode: Int) throws -> TLSTransport {
        let transport = try TLSTransport.connect(host: "127.0.0.1", port: rawPort, pin: pin,
                                                 identity: identity, deadline: TLSTransport.deadline(after: 2),
                                                 cancelled: { false })
        try transport.writeFrame(Proto.Writer().number(1, UInt64(mode)).build(), deadline: TLSTransport.deadline(after: 1))
        return transport
    }
}
