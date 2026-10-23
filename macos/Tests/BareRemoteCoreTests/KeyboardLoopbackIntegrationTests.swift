import Foundation
import XCTest
@testable import BareRemoteCore

final class KeyboardLoopbackIntegrationTests: XCTestCase {
    func testNativeIMEFramesFieldScopeCounterAcknowledgmentsAndLifecycle() throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["BAREREMOTE_TEST_LOOPBACK"] == "1" else {
            throw XCTSkip("Run python3 Tests/test_loopback.py for pinned loopback IME tests")
        }
        let port = try XCTUnwrap(Int(environment["BAREREMOTE_TEST_IME_PORT"] ?? ""))
        let certificatePath = try XCTUnwrap(environment["BAREREMOTE_TEST_CERTIFICATE_FILE"])
        let pin = try Data(contentsOf: URL(fileURLWithPath: certificatePath))
        let namespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        defer { try? ClientIdentity.delete(namespace: namespace) }
        let identity = try ClientIdentity.loadOrCreate(namespace: namespace)
        let remote = try TVRemote.connect(host: "127.0.0.1", port: port, pin: pin, identity: identity)
        defer { remote.close() }
        try until { remote.keyboardState.canSendText }
        let initial = remote.keyboardState
        XCTAssertTrue(initial.supported)
        XCTAssertEqual(initial.showRequestID, 1)
        XCTAssertEqual(initial.fieldLabel, "Search")
        let captured = try XCTUnwrap(initial.context)

        let initialCallback = expectation(description: "Registering a callback delivers the existing focused field")
        let callbackLock = NSLock()
        var didNotify = false
        remote.onKeyboardStateChanged = { snapshot in
            callbackLock.lock(); defer { callbackLock.unlock() }
            if snapshot.canSendText && !didNotify {
                didNotify = true
                initialCallback.fulfill()
            }
        }
        wait(for: [initialCallback], timeout: 1)

        // These invalid requests must produce no network frame and no reconnect.
        XCTAssertThrowsError(try remote.sendText("expired", context: captured,
                                                deadline: ProcessInfo.processInfo.systemUptime - 1))
        XCTAssertThrowsError(try remote.sendText("", context: captured))
        XCTAssertThrowsError(try remote.sendKeyboardKey(26, context: captured))
        try remote.sendText("A😀é", context: captured)
        try until { remote.keyboardState.context?.fieldCounter == 10 && remote.keyboardState.selectionStart == 11 && remote.keyboardState.selectionEnd == 13 }
        XCTAssertGreaterThan(remote.keyboardState.focusID, initial.focusID)
        XCTAssertEqual(remote.keyboardState.showRequestID, initial.showRequestID)
        // The real selection message changed the range and cancelled the old
        // target. New input uses that selection and the latest content revision.
        let selectedRange = try XCTUnwrap(remote.keyboardState.context)
        try remote.sendText("β", context: selectedRange)
        try until { remote.keyboardState.context?.imeCounter == 8 }
        let next = try XCTUnwrap(remote.keyboardState.context)
        XCTAssertThrowsError(try remote.sendText("stale", context: captured))
        XCTAssertThrowsError(try remote.sendKeyboardKey(67, context: captured))
        for key in [67, 66, 21, 112, 61, 19, 20, 22] {
            try remote.sendKeyboardKey(key, context: next)
        }
        try until { !remote.keyboardState.active }
        XCTAssertThrowsError(try remote.sendText("lost", context: next))
        XCTAssertThrowsError(try remote.sendKeyboardKey(66, context: next))
        try remote.sendKey(24)
        let cleared = expectation(description: "Explicit close clears the transient keyboard snapshot")
        remote.onKeyboardStateChanged = { snapshot in
            if !snapshot.supported && !snapshot.active && snapshot.context == nil { cleared.fulfill() }
        }
        remote.close()
        wait(for: [cleared], timeout: 1)
        XCTAssertFalse(remote.isOpen)

        // A fresh connection lacking IME still supports ordinary remote keys.
        let unsupported = try TVRemote.connect(host: "127.0.0.1", port: port, pin: pin, identity: identity)
        XCTAssertFalse(unsupported.keyboardState.supported)
        XCTAssertThrowsError(try unsupported.sendText("old connection", context: captured))
        try unsupported.sendKey(19)
        unsupported.close()

        // A pinned server's malformed IME text must close the connection, rather
        // than publishing or retaining an invalid editor and accepting input.
        let malformed = try TVRemote.connect(host: "127.0.0.1", port: port, pin: pin, identity: identity)
        defer { malformed.close() }
        try until { !malformed.isOpen }
        XCTAssertFalse(malformed.keyboardState.active)
        XCTAssertNil(malformed.keyboardState.context)
    }

    private func until(_ condition: () -> Bool) throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while !condition() {
            guard ProcessInfo.processInfo.systemUptime < deadline else {
                throw NSError(domain: "BareRemoteKeyboardLoopback", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "The local IME fixture did not reach the expected state"])
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
    }
}
