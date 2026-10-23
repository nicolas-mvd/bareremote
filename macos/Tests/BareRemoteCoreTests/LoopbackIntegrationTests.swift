import Foundation
import Security
import XCTest
@testable import BareRemoteCore

/// The Python runner provides an isolated Java FakeTv and its ephemeral ports/code.
/// Ordinary `swift test` runs skip this case and never contact a TV.
final class LoopbackIntegrationTests: XCTestCase {
    func testNativeKeychainMutualTLSAgainstIndependentJavaTV() throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["BAREREMOTE_TEST_LOOPBACK"] == "1" else {
            throw XCTSkip("Run python3 Tests/test_loopback.py to exercise the loopback Java TV")
        }
        let pairingPort = try XCTUnwrap(Int(environment["BAREREMOTE_TEST_PAIRING_PORT"] ?? ""))
        let remotePort = try XCTUnwrap(Int(environment["BAREREMOTE_TEST_REMOTE_PORT"] ?? ""))
        let codePath = try XCTUnwrap(environment["BAREREMOTE_TEST_CODE_FILE"])
        let certificatePath = try XCTUnwrap(environment["BAREREMOTE_TEST_CERTIFICATE_FILE"])
        let namespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        defer { try? ClientIdentity.delete(namespace: namespace) }
        let identity = try ClientIdentity.loadOrCreate(namespace: namespace)
        let pairing = try TVPairing.start(host: "127.0.0.1", port: pairingPort, identity: identity)
        defer { pairing.close() }
        let code = try waitForCode(at: codePath)
        let pin = try pairing.finish(code: code)
        XCTAssertEqual(pin, try Data(contentsOf: URL(fileURLWithPath: certificatePath)),
                       "Pairing must return the exact authenticated server certificate")
        pairing.close()

        let remote = try TVRemote.connect(host: "127.0.0.1", port: remotePort, pin: pin, identity: identity)
        defer { remote.close() }
        XCTAssertTrue(remote.isOpen)
        XCTAssertEqual(remote.localAddress, "127.0.0.1")
        try remote.sendKey(19)
        try remote.sendLongKey(66)
        try remote.sendAppLink("barehome://inputs")
        // FakeTv dispatches keys asynchronously and sends its first ping after 3 s.
        // Keep the same connection alive to verify both dispatch and the reader.
        Thread.sleep(forTimeInterval: 3.2)
        XCTAssertTrue(remote.isOpen)
        remote.close()
        XCTAssertFalse(remote.isOpen)

        let reloaded = try ClientIdentity.loadOrCreate(namespace: namespace)
        XCTAssertEqual(SecCertificateCopyData(identity.certificate) as Data,
                       SecCertificateCopyData(reloaded.certificate) as Data)
        let reconnected = try TVRemote.connect(host: "127.0.0.1", port: remotePort, pin: pin, identity: reloaded)
        defer { reconnected.close() }
        try reconnected.sendKey(20)
        Thread.sleep(forTimeInterval: 0.5)
        reconnected.close()

        var changedPin = pin
        changedPin[changedPin.startIndex] ^= 1
        let started = Date()
        XCTAssertThrowsError(try TVRemote.connect(host: "127.0.0.1", port: remotePort,
                                                pin: changedPin, identity: reloaded))
        XCTAssertLessThan(Date().timeIntervalSince(started), 7, "Pin rejection must be bounded")
        XCTAssertThrowsError(try TVRemote.connect(host: "127.0.0.1", port: remotePort,
                                                pin: pin, identity: reloaded, cancelled: { true }))
        XCTAssertThrowsError(try TVPairing.start(host: "127.0.0.1", port: pairingPort,
                                               identity: reloaded, cancelled: { true }))
    }

    private func waitForCode(at path: String) throws -> String {
        let deadline = Date().addingTimeInterval(3)
        let url = URL(fileURLWithPath: path)
        while Date() < deadline {
            if let code = try? String(contentsOf: url, encoding: .utf8), code.count == 6 {
                return code
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
        throw NSError(domain: "BareRemoteLoopback", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "The local fake TV did not supply its pairing code"])
    }
}
