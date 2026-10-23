import XCTest
@testable import BareRemote
@testable import BareRemoteCore

@MainActor final class KeyboardPresentationTests: XCTestCase {
    func testHideShowReopensSameEditorAndManualRemoteSurvivesCursorChanges() throws {
        let model = RemoteModel()
        var tracker = TVKeyboardTracker()
        tracker.configure(supported: true)
        func deliver() { model.receiveKeyboard(tracker.snapshot, revision: model.inputGeneration) }
        try tracker.receive(editor(7, revision: 9))
        deliver()
        try tracker.receive(visibility(true))
        deliver()
        XCTAssertTrue(model.keyboardMode)
        try tracker.receive(visibility(false))
        deliver()
        XCTAssertFalse(model.keyboardMode)
        try tracker.receive(visibility(true))
        deliver()
        XCTAssertTrue(model.keyboardMode, "A TV hide/show must reopen the same editor")

        model.showRemoteMode()
        XCTAssertFalse(model.keyboardMode)
        // A real content update changes the input token, but not the editor
        // identity to which the user's explicit Remote choice belongs.
        let content = try Proto.Writer().number(1, 11).number(3, 2).number(4, 2)
        try tracker.receive(Proto.parse(Proto.Writer().message(22, Proto.Writer().message(2, content)).build()))
        deliver()
        try tracker.receive(Proto.parse(Data([0xba, 0x01, 0x04, 0x10, 0x00, 0x18, 0x01])))
        deliver()
        try tracker.receive(visibility(true))
        deliver()
        XCTAssertFalse(model.keyboardMode, "Duplicate show and content updates must respect Remote")
        try tracker.receive(visibility(false))
        deliver()
        try tracker.receive(visibility(true))
        deliver()
        XCTAssertFalse(model.keyboardMode, "Same-editor visibility cycles must respect a manual Remote choice")

        try tracker.receive(editor(8, revision: 12))
        deliver()
        XCTAssertTrue(model.keyboardMode, "A different TV editor can request the keyboard again")
    }

    private func editor(_ id: UInt64, revision: UInt64) throws -> Proto.Message {
        let app = try Proto.Writer().number(1, id).number(2, 1).text(12, "local.test")
        let status = try Proto.Writer().number(1, revision)
        return try Proto.parse(Proto.Writer().message(20, Proto.Writer().message(1, app).message(2, status)).build())
    }

    private func visibility(_ visible: Bool) throws -> Proto.Message {
        try Proto.parse(Proto.Writer().message(21, Proto.Writer().number(1, visible ? 1 : 0)).build())
    }
}
