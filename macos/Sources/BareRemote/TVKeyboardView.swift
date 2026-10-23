import AppKit
import SwiftUI

/// A native input-method client forwards committed chunks, never a copy of the
/// TV's field. Marked/composing text stays local until the input method commits.
struct TVKeyboardView: View {
    @ObservedObject var model: RemoteModel
    var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                Image(systemName: "keyboard").font(.system(size: 23)).foregroundStyle(QuickRemotePalette.accent)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Type on your TV").font(.system(size: compact ? 16 : 19, weight: .semibold))
                    if let label = model.keyboardState?.fieldLabel, !label.isEmpty {
                        Text(label).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            Text(model.keyboardHint).font(.system(size: 12)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            TVTextInput(model: model)
                // A retired input context must never be re-enabled for a different
                // TV field. Late native commits keep the old, disabled client.
                .id(model.keyboardFocusIdentifier)
                .frame(height: compact ? 96 : 126)
                .background(QuickRemotePalette.panel, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(QuickRemotePalette.accent.opacity(model.canType ? 0.5 : 0.15)))
            HStack(spacing: 8) {
                key("arrow.left", "Move TV cursor left", code: 21)
                key("arrow.right", "Move TV cursor right", code: 22)
                key("delete.left", "Delete previous character on TV", code: 67)
                Button { model.sendKeyboardKey(66) } label: {
                    Label("Enter", systemImage: "return").frame(maxWidth: .infinity).frame(height: 34)
                }.buttonStyle(.borderedProminent).tint(QuickRemotePalette.accent).foregroundStyle(QuickRemotePalette.ink)
                    .disabled(!model.canType).accessibilityLabel("Enter on TV")
            }
            if !model.keyboardNotice.isEmpty {
                Text(model.keyboardNotice).font(.system(size: 11)).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("Text is not saved. Press Esc to return to the remote.")
                .font(.system(size: 10)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button { model.showRemoteMode() } label: {
                Label("Back to remote", systemImage: "circle.grid.cross").frame(maxWidth: .infinity)
            }.buttonStyle(.bordered).controlSize(.large).accessibilityLabel("Back to remote mode")
        }
        .frame(maxWidth: .infinity, minHeight: compact ? 300 : 422, alignment: .top)
    }
    private func key(_ symbol: String, _ label: String, code: Int) -> some View {
        Button { model.sendKeyboardKey(code) } label: {
            Image(systemName: symbol).frame(width: 32, height: 34)
        }.buttonStyle(.bordered).disabled(!model.canType).help(label).accessibilityLabel(label)
    }
}

struct TVTextInput: NSViewRepresentable {
    @ObservedObject var model: RemoteModel

    func makeNSView(context: Context) -> LiveInputView {
        let view = LiveInputView(frame: .zero)
        view.isRichText = false
        view.allowsUndo = false
        view.drawsBackground = false
        view.textColor = NSColor(QuickRemotePalette.paper)
        view.insertionPointColor = NSColor(QuickRemotePalette.accent)
        view.font = .systemFont(ofSize: 15)
        view.textContainerInset = NSSize(width: 12, height: 12)
        view.textContainer?.lineFragmentPadding = 0
        view.isAutomaticSpellingCorrectionEnabled = false
        view.isAutomaticTextCompletionEnabled = false
        view.isContinuousSpellCheckingEnabled = false
        view.isGrammarCheckingEnabled = false
        view.isAutomaticQuoteSubstitutionEnabled = false
        view.isAutomaticDashSubstitutionEnabled = false
        view.isAutomaticTextReplacementEnabled = false
        view.setAccessibilityLabel("TV keyboard input")
        return view
    }
    func updateNSView(_ view: LiveInputView, context: Context) {
        view.updateInput(focusIdentifier: model.keyboardFocusIdentifier, enabled: model.canType,
                         commit: { model.commitKeyboardText($0) },
                         keyboardKey: { model.sendKeyboardKey($0) },
                         remoteMode: { model.showRemoteMode() })
        view.requestInitialFocus()
        view.needsDisplay = true
    }
    static func dismantleNSView(_ view: LiveInputView, coordinator: ()) {
        view.deactivateInput()
    }

    final class LiveInputView: NSTextView {
        var focusIdentifier = ""
        var needsInitialFocus = true
        var acceptingInput = true
        var commit: (String) -> Bool = { _ in false }
        var keyboardKey: (Int) -> Void = { _ in }
        var remoteMode: () -> Void = {}

        func updateInput(focusIdentifier: String, enabled: Bool,
                         commit: @escaping (String) -> Bool,
                         keyboardKey: @escaping (Int) -> Void,
                         remoteMode: @escaping () -> Void) {
            if self.focusIdentifier != focusIdentifier {
                deactivateInput()
                self.focusIdentifier = focusIdentifier
                needsInitialFocus = true
            }
            guard enabled else {
                if acceptingInput || isEditable || hasMarkedText() { deactivateInput() }
                return
            }
            // Install callbacks before enabling a fresh client. Do not touch
            // isEditable on ordinary ACK/model updates: its native setter can
            // commit marked text even when the desired value did not change.
            self.commit = commit
            self.keyboardKey = keyboardKey
            self.remoteMode = remoteMode
            if !isEditable {
                needsInitialFocus = true
                isEditable = true
            }
            acceptingInput = true
        }

        func deactivateInput() {
            // NSTextView.unmarkText can itself commit composition. Disable and
            // detach first so teardown/focus changes cannot type into a new field.
            acceptingInput = false
            commit = { _ in false }; keyboardKey = { _ in }; remoteMode = {}
            isEditable = false
            inputContext?.discardMarkedText()
            unmarkText(); string = ""
        }

        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); requestInitialFocus() }
        func requestInitialFocus() {
            guard needsInitialFocus, isEditable, let window else { return }
            needsInitialFocus = false
            DispatchQueue.main.async { [weak self, weak window] in
                guard let self, self.isEditable, self.window === window else { return }
                window?.makeFirstResponder(self)
            }
        }
        override func insertText(_ insertString: Any, replacementRange: NSRange) {
            let text = (insertString as? NSAttributedString)?.string ?? (insertString as? String ?? "")
            guard isEditable, acceptingInput else { return }
            if !text.isEmpty && !commit(text) { NSSound.beep() }
            // This is a commit stream. Never keep a local mirror or undo history
            // that might duplicate sent text or reveal a TV password.
            super.insertText("", replacementRange: NSRange(location: 0, length: (string as NSString).length))
            string = ""
            needsDisplay = true
        }
        override func keyDown(with event: NSEvent) {
            if event.keyCode == 53 { let back = remoteMode; deactivateInput(); back(); return }
            if !hasMarkedText(), event.modifierFlags.intersection([.command, .control, .option]).isEmpty {
                let keys: [UInt16: Int] = [36: 66, 76: 66, 51: 67, 117: 112, 123: 21, 124: 22, 48: 61]
                if let code = keys[event.keyCode] { if isEditable && acceptingInput { keyboardKey(code) }; return }
            }
            super.keyDown(with: event)
        }
        override func paste(_ sender: Any?) {
            guard let value = NSPasteboard.general.string(forType: .string) else { return }
            insertText(value, replacementRange: NSRange(location: NSNotFound, length: 0))
        }
        override func draw(_ dirtyRect: NSRect) {
            super.draw(dirtyRect)
            if string.isEmpty {
                let placeholder = isEditable ? "Type or paste here" : "Select a text field on your TV"
                (placeholder as NSString).draw(at: NSPoint(x: 12, y: 12), withAttributes: [
                    .font: NSFont.systemFont(ofSize: 13), .foregroundColor: NSColor.secondaryLabelColor
                ])
            }
        }
    }
}
