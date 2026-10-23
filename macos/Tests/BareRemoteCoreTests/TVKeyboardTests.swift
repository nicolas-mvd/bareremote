import Foundation
import XCTest
@testable import BareRemoteCore

final class TVKeyboardTests: XCTestCase {
    func testActualServerEditorMetadataAllowsManualInputWithoutServerBatchCounters() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 91, start: 10, end: 10))
        XCTAssertTrue(tracker.snapshot.active)
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.context?.imeCounter, 7)
        XCTAssertEqual(tracker.snapshot.context?.fieldCounter, 91, "Content revision is independent of editor ID")
        XCTAssertNil(tracker.snapshot.keyboardVisible)
        XCTAssertEqual(tracker.snapshot.showRequestID, 0)
    }

    func testActualServerVisibilityWireVectorAutomaticallyRequestsKeyboard() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 91))
        // Server envelope 21 {1:true}. This is NOT a batch-edit counter pair.
        try tracker.receive(Proto.parse(Data([0xaa, 0x01, 0x02, 0x08, 0x01])))
        XCTAssertEqual(tracker.snapshot.keyboardVisible, true)
        XCTAssertEqual(tracker.snapshot.showRequestID, 1)
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.context?.imeCounter, 7)
        XCTAssertEqual(tracker.snapshot.context?.fieldCounter, 91)
    }

    func testVisibilityAndEditorCanArriveInEitherOrder() throws {
        for visibilityFirst in [false, true] {
            var tracker = configured()
            if visibilityFirst { try tracker.receive(visibility(true)) }
            try tracker.receive(editor(id: 7, revision: 9))
            if !visibilityFirst { try tracker.receive(visibility(true)) }
            XCTAssertEqual(tracker.snapshot.showRequestID, 1)
            XCTAssertTrue(tracker.snapshot.canSendText)
        }
    }

    func testHidePreservesManualInputAndRepeatedShowRespectsRemoteChoice() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9))
        try tracker.receive(visibility(true))
        let show = tracker.snapshot.showRequestID
        try tracker.receive(visibility(false))
        XCTAssertEqual(tracker.snapshot.keyboardVisible, false)
        XCTAssertTrue(tracker.snapshot.canSendText, "Visibility and editor validity are separate")
        try tracker.receive(visibility(true))
        XCTAssertEqual(tracker.snapshot.showRequestID, show)
        try tracker.receive(editor(id: 8, revision: 12))
        XCTAssertGreaterThan(tracker.snapshot.showRequestID, show)
    }

    func testAppOnlyNotificationClearsEditorAndCannotBeUsedAsCounterZeroFallback() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9))
        let captured = try XCTUnwrap(tracker.snapshot.context)
        try tracker.receive(editor(id: 0, revision: nil, inputType: 0))
        XCTAssertFalse(tracker.snapshot.active)
        XCTAssertFalse(tracker.snapshot.canSendText)
        XCTAssertThrowsError(try tracker.validate(captured))
        try tracker.receive(visibility(true))
        XCTAssertFalse(tracker.snapshot.active)
        XCTAssertEqual(tracker.snapshot.showRequestID, 0)
    }

    func testMissingExtractedTextKeepsEditorButDoesNotInventContentRevisionOrCursor() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: nil))
        XCTAssertTrue(tracker.snapshot.active)
        XCTAssertFalse(tracker.snapshot.canSendText)
        try tracker.receive(update(revision: 14, start: 12, end: 12, own: false))
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.context?.imeCounter, 7)
        XCTAssertEqual(tracker.snapshot.context?.fieldCounter, 14)
    }

    func testContentAcknowledgmentKeepsEditorIdentityAndDoesNotShowKeyboard() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9))
        let captured = try XCTUnwrap(tracker.snapshot.context)
        tracker.committed("A😀é", context: captured)
        try tracker.receive(update(revision: 11, start: 4, end: 4, own: true))
        let current = try tracker.currentContext(matching: captured)
        XCTAssertEqual(current.imeCounter, 7)
        XCTAssertEqual(current.fieldCounter, 11)
        XCTAssertEqual(current.selectionStart, 4)
        XCTAssertEqual(tracker.snapshot.focusID, captured.focus)
        XCTAssertEqual(tracker.snapshot.showRequestID, 0, "Server 22 is a content update, not a show request")
    }

    func testQueuedUnicodeCommitsAdvanceCursorAndLaggingOwnAcknowledgmentCannotRewindIt() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9, start: 10, end: 10))
        let captured = try XCTUnwrap(tracker.snapshot.context)
        tracker.committed("😀", context: captured)
        let second = try tracker.currentContext(matching: captured)
        XCTAssertEqual(second.selectionStart, 12)
        tracker.committed("é", context: second)
        try tracker.receive(update(revision: 10, start: 12, end: 12, own: true))
        XCTAssertEqual(try tracker.currentContext(matching: captured).selectionStart, 13)
        try tracker.receive(update(revision: 11, start: 13, end: 13, own: true))
        XCTAssertEqual(try tracker.currentContext(matching: captured).selectionStart, 13)
        XCTAssertFalse(String(reflecting: tracker).contains("😀"))
    }

    func testExternalContentChangeAndSelectionCancelCapturedInput() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9, start: 10, end: 10))
        let first = try XCTUnwrap(tracker.snapshot.context)
        try tracker.receive(update(revision: 11, start: 12, end: 12, own: false))
        XCTAssertThrowsError(try tracker.validate(first))
        let second = try XCTUnwrap(tracker.snapshot.context)
        // Actual server 23 selection uses fields 2 and 3, NOT 1 and 2.
        try tracker.receive(Proto.parse(Data([0xba, 0x01, 0x04, 0x10, 0x0b, 0x18, 0x0d])))
        XCTAssertEqual(tracker.snapshot.selectionStart, 11)
        XCTAssertEqual(tracker.snapshot.selectionEnd, 13)
        XCTAssertThrowsError(try tracker.validate(second))
    }

    func testNewEditorAndPackageChangesInvalidateTokensAndMayResetCounterNumbering() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 40, revision: 99))
        let old = try XCTUnwrap(tracker.snapshot.context)
        try tracker.receive(editor(id: 2, revision: 3, package: "other.app"))
        XCTAssertTrue(tracker.snapshot.canSendText)
        XCTAssertEqual(tracker.snapshot.context?.imeCounter, 2)
        XCTAssertEqual(tracker.snapshot.context?.fieldCounter, 3)
        XCTAssertThrowsError(try tracker.validate(old))
    }

    func testKeyboardKeysWaitForFreshCursorButStillAllowFurtherScopedKeys() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9, start: 10, end: 10))
        let captured = try XCTUnwrap(tracker.snapshot.context)
        tracker.keyWritten(67)
        XCTAssertFalse(tracker.snapshot.canSendText)
        XCTAssertThrowsError(try tracker.currentContext(matching: captured))
        XCTAssertNoThrow(try tracker.currentContext(matching: captured, requireSelection: false))
        try tracker.receive(update(revision: 11, start: 9, end: 9, own: true))
        XCTAssertEqual(tracker.snapshot.selectionStart, 9, "An ACK after a key supplies the new authoritative cursor")
        XCTAssertTrue(tracker.snapshot.canSendText)
    }

    func testNoOpKeysAndEditorActionsDoNotRequireAnAbsentAcknowledgment() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9))
        let captured = try XCTUnwrap(tracker.snapshot.context)
        for key in [67, 21, 66, 61] {
            tracker.keyWritten(key)
            XCTAssertTrue(tracker.snapshot.canSendText, "A no-op/action may produce no server metadata")
            XCTAssertNoThrow(try tracker.currentContext(matching: captured))
        }
        let committed = try Proto.parse(TVKeyboardTracker.textMessage("after Enter", context: tracker.currentContext(matching: captured)).build())
        XCTAssertEqual(try committed.message(21).message(3).message(2).integer(1), 0)
        let app = try Proto.Writer().number(1, 8).number(2, 1).text(12, "test.app")
        let status = try Proto.Writer().number(1, 12).text(2, "A😀").number(3, 3).number(4, 3)
        try tracker.receive(Proto.parse(Proto.Writer().message(20, Proto.Writer().message(1, app).message(2, status)).build()))
        for key in [112, 22] {
            tracker.keyWritten(key)
            XCTAssertTrue(tracker.snapshot.canSendText, "Known UTF-16 end positions are no-ops")
        }
    }

    func testExplicitEmptyFieldAtCursorZeroHasKnownNoOpEnd() throws {
        var tracker = configured()
        let app = try Proto.Writer().number(1, 7).number(2, 1).text(12, "test.app")
        let status = try Proto.Writer().number(1, 9).text(2, "")
        try tracker.receive(Proto.parse(Proto.Writer().message(20, Proto.Writer().message(1, app).message(2, status)).build()))
        for key in [112, 22] {
            tracker.keyWritten(key)
            XCTAssertTrue(tracker.snapshot.canSendText, "Explicit empty value and zero selection establish the end")
        }
    }

    func testRedactedContentsDoNotInventAFieldEndForNoOpDetection() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9, start: 3, end: 3))
        tracker.keyWritten(112)
        XCTAssertFalse(tracker.snapshot.canSendText, "Missing TV contents do not prove that cursor 3 is the end")
    }

    func testExternalKeyNotificationStopsUseOfPredictedCursor() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9))
        let captured = try XCTUnwrap(tracker.snapshot.context)
        tracker.committed("abc", context: captured)
        try tracker.receive(Proto.parse(Data([0xc2, 0x01, 0x00])))
        XCTAssertTrue(tracker.snapshot.active)
        XCTAssertFalse(tracker.snapshot.canSendText)
        XCTAssertThrowsError(try tracker.validate(captured))
    }

    func testUnicodeCommitMatchesDirectionalWireVectorAtExistingTVCursor() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 9, start: 10, end: 10))
        let context = try XCTUnwrap(tracker.snapshot.context)
        let frame = try TVKeyboardTracker.textMessage("A😀é", context: context).build()
        let vector: [UInt8] = [0xaa, 0x01, 0x15, 0x08, 0x07, 0x10, 0x09, 0x1a, 0x0f,
                               0x12, 0x0d, 0x08, 0x0a, 0x10, 0x0a, 0x1a, 0x07, 0x41,
                               0xf0, 0x9f, 0x98, 0x80, 0xc3, 0xa9]
        XCTAssertEqual(frame, Data(vector))
        let edit = try Proto.parse(frame).message(21).message(3)
        XCTAssertFalse(edit.has(1), "No invented insert enum: field 1 is a different edit oneof")
        let object = try edit.message(2)
        XCTAssertEqual(try object.integer(1), 10)
        XCTAssertEqual(try object.integer(2), 10)
        XCTAssertEqual(try object.bytes(3), Data("A😀é".utf8))
    }

    func testZeroRevisionAndCursorUseCanonicalProto3Defaults() throws {
        var tracker = configured()
        try tracker.receive(editor(id: 7, revision: 0))
        let context = try XCTUnwrap(tracker.snapshot.context)
        let frame = try TVKeyboardTracker.textMessage("x", context: context).build()
        XCTAssertEqual(frame, Data([0xaa, 0x01, 0x09, 0x08, 0x07, 0x1a, 0x05, 0x12, 0x03, 0x1a, 0x01, 0x78]))
    }

    func testCommitBoundsAndCrossConnectionTokens() throws {
        var first = configured(), second = configured()
        try first.receive(editor(id: 7, revision: 9))
        try second.receive(editor(id: 7, revision: 9))
        let context = try XCTUnwrap(first.snapshot.context)
        XCTAssertThrowsError(try second.validate(context))
        for invalid in ["", String(repeating: "a", count: 2049), String(repeating: "😀", count: 1025)] {
            XCTAssertThrowsError(try TVKeyboardTracker.textMessage(invalid, context: context))
        }
        XCTAssertNoThrow(try TVKeyboardTracker.textMessage(String(repeating: "😀", count: 1024), context: context))
        XCTAssertFalse(TVKeyboardTracker.allowedKeys.contains(26))
        first.close()
        XCTAssertFalse(first.snapshot.active)
        XCTAssertFalse(first.snapshot.supported)
        XCTAssertNil(first.snapshot.context)
    }

    func testMalformedTextCountersSelectionAndVisibilityAreRejected() throws {
        let statuses = [
            try Proto.Writer().number(1, 1).bytes(2, Data([0xff])),
            try Proto.Writer().number(1, 1).text(2, "a").number(3, 2),
            try Proto.Writer().number(1, UInt64.max),
            try Proto.Writer().number(1, 1).number(1, 2),
            try Proto.Writer().number(1, 1).text(2, "a").number(3, UInt64.max),
            try Proto.Writer().number(1, 1).text(6, String(repeating: "x", count: 513))
        ]
        for status in statuses {
            var tracker = configured()
            try tracker.receive(editor(id: 7, revision: 9))
            XCTAssertThrowsError(try tracker.receive(Proto.parse(Proto.Writer().message(22, Proto.Writer().message(2, status)).build())))
        }
        var tracker = configured()
        XCTAssertThrowsError(try tracker.receive(Proto.parse(Proto.Writer().message(21, Proto.Writer().number(1, 7)).build())))
    }

    func testPrivateTVTextIsNotRetainedAndRedactedCursorIsAccepted() throws {
        var tracker = configured()
        let status = try Proto.Writer().number(1, 9).text(2, "😀").number(3, 2).number(4, 2).text(6, "Search\n\u{1}TV")
        let app = try Proto.Writer().number(1, 7).number(2, 1).text(12, "test.app")
        try tracker.receive(Proto.parse(Proto.Writer().message(20, Proto.Writer().message(1, app).message(2, status)).build()))
        XCTAssertEqual(tracker.snapshot.selectionStart, 2)
        XCTAssertEqual(tracker.snapshot.fieldLabel, "SearchTV")
        XCTAssertFalse(String(reflecting: tracker).contains("😀"))
        try tracker.receive(update(revision: 11, start: 12, end: 12, own: false))
        XCTAssertEqual(tracker.snapshot.selectionStart, 12)
    }

    private func configured() -> TVKeyboardTracker {
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        return tracker
    }

    private func editor(id: UInt64, revision: UInt64?, start: UInt64 = 0, end: UInt64 = 0,
                        inputType: UInt64 = 1, package: String = "test.app") throws -> Proto.Message {
        let app = try Proto.Writer().number(1, id).number(2, inputType).text(12, package)
        let body = try Proto.Writer().message(1, app)
        if let revision { try body.message(2, status(revision: revision, start: start, end: end)) }
        return try Proto.parse(Proto.Writer().message(20, body).build())
    }

    private func visibility(_ visible: Bool) throws -> Proto.Message {
        try Proto.parse(Proto.Writer().message(21, Proto.Writer().number(1, visible ? 1 : 0)).build())
    }

    private func update(revision: UInt64, start: UInt64, end: UInt64, own: Bool) throws -> Proto.Message {
        try Proto.parse(Proto.Writer().message(22, Proto.Writer().number(1, own ? 1 : 0)
            .message(2, status(revision: revision, start: start, end: end))).build())
    }

    private func status(revision: UInt64, start: UInt64, end: UInt64) throws -> Proto.Writer {
        try Proto.Writer().number(1, revision).number(3, start).number(4, end)
    }
}
