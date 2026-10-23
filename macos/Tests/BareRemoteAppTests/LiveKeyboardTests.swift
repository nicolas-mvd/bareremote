import AppKit
import XCTest
@testable import BareRemote

/// Exercise the real AppKit text client: composition must stay local until one
/// commit, and hidden/disabled clients must never forward late input.
@MainActor final class LiveKeyboardTests: XCTestCase {
    func testCompositionCommitsUnicodeExactlyOnceAndKeepsNoHistory() {
        _ = NSApplication.shared
        let view = TVTextInput.LiveInputView(frame: NSRect(x: 0, y: 0, width: 280, height: 100))
        view.isRichText = false; view.allowsUndo = false; view.isEditable = true
        var commits: [String] = []
        view.commit = { commits.append($0); return true }
        let unspecified = NSRange(location: NSNotFound, length: 0)
        view.setMarkedText("に", selectedRange: NSRange(location: 1, length: 0), replacementRange: unspecified)
        view.setMarkedText("日本", selectedRange: NSRange(location: 2, length: 0), replacementRange: unspecified)
        XCTAssertTrue(view.hasMarkedText())
        XCTAssertTrue(commits.isEmpty)
        view.insertText("日本😀é", replacementRange: unspecified)
        XCTAssertEqual(commits, ["日本😀é"])
        XCTAssertEqual(view.string, "")
        XCTAssertFalse(view.hasMarkedText())
        view.unmarkText()
        XCTAssertEqual(commits.count, 1)
        XCTAssertFalse(view.undoManager?.canUndo ?? false)
    }

    func testDisabledOrDismantledInputDoesNotSendLateComposition() {
        _ = NSApplication.shared
        let view = TVTextInput.LiveInputView(frame: NSRect(x: 0, y: 0, width: 280, height: 100))
        view.isRichText = false; view.allowsUndo = false; view.isEditable = true
        var commits = 0
        view.commit = { _ in commits += 1; return true }
        view.setMarkedText("かな", selectedRange: NSRange(location: 2, length: 0),
                           replacementRange: NSRange(location: NSNotFound, length: 0))
        XCTAssertEqual(commits, 0, "Composing text must stay local")
        TVTextInput.dismantleNSView(view, coordinator: ())
        view.insertText("late", replacementRange: NSRange(location: NSNotFound, length: 0))
        XCTAssertEqual(commits, 0)
        XCTAssertEqual(view.string, "")
        XCTAssertFalse(view.hasMarkedText())
        XCTAssertFalse(view.isEditable)
    }

    func testSameFocusEnableUpdatesPreserveCompositionUntilOneCommit() {
        _ = NSApplication.shared
        let view = TVTextInput.LiveInputView(frame: NSRect(x: 0, y: 0, width: 280, height: 100))
        view.isRichText = false; view.allowsUndo = false
        var commits: [String] = [], replacedCallbackCommits: [String] = []
        view.updateInput(focusIdentifier: "connection:field", enabled: true,
                         commit: { commits.append($0); return true }, keyboardKey: { _ in }, remoteMode: {})
        let unspecified = NSRange(location: NSNotFound, length: 0)
        view.setMarkedText("にほん", selectedRange: NSRange(location: 3, length: 0), replacementRange: unspecified)
        XCTAssertTrue(view.hasMarkedText())
        for _ in 0..<4 {
            // The same production update path handles counter ACKs and model
            // refreshes. They must not commit or discard a native composition.
            view.updateInput(focusIdentifier: "connection:field", enabled: true,
                             commit: { replacedCallbackCommits.append($0); return true },
                             keyboardKey: { _ in }, remoteMode: {})
            XCTAssertTrue(view.hasMarkedText())
            XCTAssertEqual(view.string, "にほん")
        }
        XCTAssertTrue(commits.isEmpty)
        XCTAssertTrue(replacedCallbackCommits.isEmpty)
        view.insertText("日本😀", replacementRange: unspecified)
        XCTAssertTrue(commits.isEmpty, "An old callback must not receive a later commit")
        XCTAssertEqual(replacedCallbackCommits, ["日本😀"])
        XCTAssertEqual(view.string, "")
        XCTAssertFalse(view.hasMarkedText())
        XCTAssertFalse(view.undoManager?.canUndo ?? false)
    }

    func testFocusChangeDiscardsCompositionBeforeInstallingNewCallbacks() {
        _ = NSApplication.shared
        let view = TVTextInput.LiveInputView(frame: NSRect(x: 0, y: 0, width: 280, height: 100))
        view.isRichText = false; view.allowsUndo = false
        var old: [String] = [], fresh: [String] = []
        view.updateInput(focusIdentifier: "first", enabled: true,
                         commit: { old.append($0); return true }, keyboardKey: { _ in }, remoteMode: {})
        let unspecified = NSRange(location: NSNotFound, length: 0)
        view.setMarkedText("かな", selectedRange: NSRange(location: 2, length: 0), replacementRange: unspecified)
        view.updateInput(focusIdentifier: "second", enabled: true,
                         commit: { fresh.append($0); return true }, keyboardKey: { _ in }, remoteMode: {})
        XCTAssertTrue(old.isEmpty)
        XCTAssertTrue(fresh.isEmpty, "Discarding the first field must not commit into its replacement")
        XCTAssertEqual(view.string, "")
        XCTAssertFalse(view.hasMarkedText())
        view.insertText("new", replacementRange: unspecified)
        XCTAssertEqual(fresh, ["new"])
    }

    func testPlainDeleteEnterArrowsAndEscapeAreScopedCommands() throws {
        _ = NSApplication.shared
        let view = TVTextInput.LiveInputView(frame: NSRect(x: 0, y: 0, width: 280, height: 100))
        view.isEditable = true
        var commands: [Int] = [], remotes = 0, commits = 0
        view.keyboardKey = { commands.append($0) }
        view.remoteMode = { remotes += 1 }
        view.commit = { _ in commits += 1; return true }
        for code: UInt16 in [51, 36, 123, 124, 125, 126, 53] {
            let event = try XCTUnwrap(NSEvent.keyEvent(with: .keyDown, location: .zero,
                modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil,
                characters: "", charactersIgnoringModifiers: "", isARepeat: false, keyCode: code))
            view.keyDown(with: event)
        }
        XCTAssertEqual(commands, [67, 66, 21, 22])
        XCTAssertEqual(remotes, 1)
        XCTAssertEqual(commits, 0)
    }
}
