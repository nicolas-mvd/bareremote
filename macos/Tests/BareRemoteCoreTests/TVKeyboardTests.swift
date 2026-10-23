import Foundation
import XCTest
@testable import BareRemoteCore

final class TVKeyboardTests: XCTestCase {
    func testNegotiationAndExplicitShowWithZeroCounters() throws {
        var tracker = TVKeyboardTracker()
        XCTAssertFalse(tracker.snapshot.supported)
        try tracker.receive(show(counter: 0))
        XCTAssertFalse(tracker.snapshot.active)
        tracker.configure(supported: true)
        try tracker.receive(batch(ime: 0, field: 0))
        XCTAssertFalse(tracker.snapshot.active, "Counters alone do not imply an editor")
        try tracker.receive(show(counter: 0))
        XCTAssertTrue(tracker.snapshot.active)
        XCTAssertTrue(tracker.snapshot.canSendText, "Proto3 omitted zero counters are valid")
        XCTAssertEqual(tracker.snapshot.showRequestID, 1)
        XCTAssertNotNil(tracker.snapshot.context)
    }

    func testFocusAndCountersCanArriveInEitherOrder() throws {
        for countersFirst in [false, true] {
            var tracker = TVKeyboardTracker()
            tracker.configure(supported: true)
            if countersFirst { try tracker.receive(batch(ime: 8, field: 17)) }
            try tracker.receive(show(counter: 17))
            XCTAssertEqual(tracker.snapshot.canSendText, countersFirst)
            if !countersFirst { try tracker.receive(batch(ime: 8, field: 17)) }
            XCTAssertTrue(tracker.snapshot.canSendText)
            XCTAssertEqual(tracker.snapshot.context?.imeCounter, 8)
            XCTAssertEqual(tracker.snapshot.context?.fieldCounter, 17)
        }
    }

    func testAppNotificationDoesNotShowKeyboardAndFieldLossClearsToken() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(inject(app: 3, field: 9))
        try tracker.receive(batch(ime: 3, field: 9))
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.showRequestID, 0, "A current-app notification is not a show request")
        let context = try XCTUnwrap(tracker.snapshot.context)
        try tracker.receive(inject(app: 3, field: nil))
        XCTAssertFalse(tracker.snapshot.active)
        XCTAssertNil(tracker.snapshot.context)
        XCTAssertThrowsError(try tracker.validate(context))
        try tracker.receive(batch(ime: 3, field: 9))
        XCTAssertFalse(tracker.snapshot.active)
    }

    func testRepeatedShowDoesNotUndoRemoteChoiceAndNewFocusDoes() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(show(counter: 4))
        let first = tracker.snapshot
        try tracker.receive(show(counter: 4))
        XCTAssertEqual(tracker.snapshot.showRequestID, first.showRequestID)
        XCTAssertEqual(tracker.snapshot.focusID, first.focusID)
        try tracker.receive(show(counter: 5))
        XCTAssertGreaterThan(tracker.snapshot.focusID, first.focusID)
        XCTAssertGreaterThan(tracker.snapshot.showRequestID, first.showRequestID)
    }

    func testCoalescedStateCannotCarryAnOldShowRequestIntoANewField() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(inject(app: 1, field: 4))
        try tracker.receive(show(counter: 4))
        XCTAssertGreaterThan(tracker.snapshot.showRequestID, 0)
        try tracker.receive(inject(app: 1, field: nil))
        try tracker.receive(inject(app: 1, field: 5))
        XCTAssertTrue(tracker.snapshot.active)
        XCTAssertEqual(tracker.snapshot.showRequestID, 0, "A coalesced callback must not auto-open an unrequested editor")
    }

    func testStaleTVFieldAndCounterEventsCannotRestoreOldTarget() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(inject(app: 10, field: 4))
        try tracker.receive(batch(ime: 10, field: 4))
        try tracker.receive(show(counter: 4))
        let old = try XCTUnwrap(tracker.snapshot.context)
        try tracker.receive(batch(ime: 10, field: 5))
        XCTAssertFalse(tracker.snapshot.canSendText, "A new batch cannot authorize the previous field")
        try tracker.receive(show(counter: 5))
        let fresh = try XCTUnwrap(tracker.snapshot.context)
        let freshState = tracker.snapshot
        XCTAssertThrowsError(try tracker.validate(old))
        try tracker.validate(fresh)
        try tracker.receive(show(counter: 4))
        try tracker.receive(batch(ime: 10, field: 4))
        try tracker.receive(inject(app: 9, field: 99))
        XCTAssertEqual(tracker.snapshot, freshState)
    }

    func testAppChangeInvalidatesOldCountersAndAllowsNewFieldNumbering() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(inject(app: 1, field: 40))
        try tracker.receive(batch(ime: 1, field: 40))
        let old = try XCTUnwrap(tracker.snapshot.context)
        try tracker.receive(inject(app: 2, field: 0))
        XCTAssertFalse(tracker.snapshot.canSendText)
        XCTAssertThrowsError(try tracker.validate(old))
        try tracker.receive(batch(ime: 2, field: 0))
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.context?.appCounter, 2)
    }

    func testAcknowledgmentCounterAdvancesWithinSameCapturedField() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(show(counter: 9))
        try tracker.receive(batch(ime: 7, field: 9))
        let capturedWithInput = try XCTUnwrap(tracker.snapshot.context)
        let focus = tracker.snapshot.focusID
        let show = tracker.snapshot.showRequestID
        try tracker.receive(batch(ime: 8, field: 9))
        let current = try tracker.currentContext(matching: capturedWithInput)
        XCTAssertEqual(current.imeCounter, 8)
        XCTAssertEqual(tracker.snapshot.focusID, focus)
        XCTAssertEqual(tracker.snapshot.showRequestID, show)
        let wire = try Proto.parse(TVKeyboardTracker.textMessage("next", context: current).build()).message(21)
        XCTAssertEqual(try wire.integer(1), 8)
        XCTAssertEqual(try wire.integer(2), 9)
        try tracker.receive(batch(ime: 9, field: 10))
        XCTAssertThrowsError(try tracker.currentContext(matching: capturedWithInput))
    }

    func testTokensCannotCrossConnectionsAndCloseClearsTransientState() throws {
        var first = TVKeyboardTracker()
        var second = TVKeyboardTracker()
        for index in [0, 1] {
            if index == 0 {
                first.configure(supported: true)
                try first.receive(show(counter: 1))
                try first.receive(batch(ime: 1, field: 1))
            } else {
                second.configure(supported: true)
                try second.receive(show(counter: 1))
                try second.receive(batch(ime: 1, field: 1))
            }
        }
        let firstToken = try XCTUnwrap(first.snapshot.context)
        XCTAssertThrowsError(try second.validate(firstToken))
        first.close()
        XCTAssertFalse(first.snapshot.supported)
        XCTAssertFalse(first.snapshot.active)
        XCTAssertNil(first.snapshot.context)
        XCTAssertEqual(first.snapshot.fieldLabel, "")
        XCTAssertNil(first.snapshot.selectionStart)
    }

    func testUnicodeCommitMatchesIndependentWireVectorAndUsesUTF16() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(show(counter: 9))
        try tracker.receive(batch(ime: 7, field: 9))
        let context = try XCTUnwrap(tracker.snapshot.context)
        // 4 UTF-16 units: A (1), emoji (2), accented e (1). No text logs.
        let frame = try TVKeyboardTracker.textMessage("A😀é", context: context).build()
        let vector: [UInt8] = [0xaa, 0x01, 0x17, 0x08, 0x07, 0x10, 0x09, 0x1a, 0x11,
                               0x08, 0x01, 0x12, 0x0d, 0x08, 0x03, 0x10, 0x03, 0x1a,
                               0x07, 0x41, 0xf0, 0x9f, 0x98, 0x80, 0xc3, 0xa9]
        XCTAssertEqual(frame, Data(vector))
        let batch = try Proto.parse(frame).message(21)
        let object = try batch.message(3).message(2)
        XCTAssertEqual(try object.integer(1), 3)
        XCTAssertEqual(try object.integer(2), 3)
        XCTAssertEqual(try object.bytes(3), Data("A😀é".utf8))
    }

    func testCommitLimitsAndAllowedKeys() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(show(counter: 1))
        try tracker.receive(batch(ime: 1, field: 1))
        let context = try XCTUnwrap(tracker.snapshot.context)
        for invalid in ["", String(repeating: "a", count: 2049), String(repeating: "😀", count: 1025)] {
            XCTAssertThrowsError(try TVKeyboardTracker.textMessage(invalid, context: context))
        }
        XCTAssertNoThrow(try TVKeyboardTracker.textMessage(String(repeating: "😀", count: 1024), context: context))
        XCTAssertTrue(TVKeyboardTracker.allowedKeys.isSuperset(of: [19, 20, 21, 22, 61, 66, 67, 112]))
        XCTAssertFalse(TVKeyboardTracker.allowedKeys.contains(26))
    }

    func testZeroScalarCommitUsesCanonicalProto3Defaults() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        try tracker.receive(show(counter: 0))
        try tracker.receive(batch(ime: 0, field: 0))
        let context = try XCTUnwrap(tracker.snapshot.context)
        let frame = try TVKeyboardTracker.textMessage("x", context: context).build()
        XCTAssertEqual(frame, Data([0xaa, 0x01, 0x09, 0x1a, 0x07, 0x08, 0x01, 0x12, 0x03, 0x1a, 0x01, 0x78]))
    }

    func testMalformedUTF8SelectionCountersAndDuplicateFieldsAreRejected() throws {
        let badStatuses = [
            try Proto.Writer().number(1, 1).bytes(2, Data([0xff])),
            try Proto.Writer().number(1, 1).text(2, "a").number(3, 2),
            try Proto.Writer().number(1, UInt64.max),
            try Proto.Writer().number(1, 1).number(1, 2),
            try Proto.Writer().number(1, 1).text(2, "a").number(3, UInt64.max),
            try Proto.Writer().number(1, 1).text(6, String(repeating: "x", count: 513))
        ]
        for status in badStatuses {
            var tracker = TVKeyboardTracker()
            tracker.configure(supported: true)
            let message = try Proto.parse(Proto.Writer().message(22, Proto.Writer().message(2, status)).build())
            XCTAssertThrowsError(try tracker.receive(message))
        }
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        let duplicate = try Proto.parse(Proto.Writer().message(21, Proto.Writer().number(1, 1).number(1, 2)).build())
        XCTAssertThrowsError(try tracker.receive(duplicate))
    }

    func testSelectionUsesUTF16AndPrivateTVTextIsNotRetained() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        let status = try Proto.Writer().number(1, 1).text(2, "😀")
            .number(3, 2).number(4, 2).text(6, "Search\n\u{1}TV")
        try tracker.receive(Proto.parse(Proto.Writer().message(22, Proto.Writer().message(2, status)).build()))
        XCTAssertEqual(tracker.snapshot.selectionStart, 2)
        XCTAssertEqual(tracker.snapshot.selectionEnd, 2)
        XCTAssertEqual(tracker.snapshot.fieldLabel, "SearchTV")
        XCTAssertFalse(String(reflecting: tracker).contains("😀"))
        XCTAssertFalse(String(reflecting: tracker.snapshot).contains("😀"))
    }

    func testRedactedTVContentsCanStillReportABoundedCursor() throws {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        let status = try Proto.Writer().number(1, 1).number(3, 12).number(4, 12)
        try tracker.receive(Proto.parse(Proto.Writer().message(22, Proto.Writer().message(2, status)).build()))
        try tracker.receive(batch(ime: 1, field: 1))
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.selectionStart, 12)
    }

    private func show(counter: UInt64) throws -> Proto.Message {
        try Proto.parse(Proto.Writer().message(22, Proto.Writer()
            .message(2, Proto.Writer().number(1, counter))).build())
    }

    private func batch(ime: UInt64, field: UInt64) throws -> Proto.Message {
        try Proto.parse(Proto.Writer().message(21, Proto.Writer().number(1, ime).number(2, field)).build())
    }

    private func inject(app: UInt64, field: UInt64?) throws -> Proto.Message {
        let body = try Proto.Writer().message(1, Proto.Writer().number(1, app).text(12, "test.app"))
        if let field { try body.message(2, Proto.Writer().number(1, field)) }
        return try Proto.parse(Proto.Writer().message(20, body).build())
    }
}
