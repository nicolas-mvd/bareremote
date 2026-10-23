import Foundation

/// An opaque, connection-scoped target for a keyboard operation. Keep the token
/// captured with the user's input; never replace a stale token and retry input.
public struct TVKeyboardContext: Equatable, Sendable {
    let connection: UUID
    let focus: UInt64
    let appCounter: Int32?
    let imeCounter: Int32
    let fieldCounter: Int32
    let selectionStart: Int
    let selectionEnd: Int
}

/// Transient editor metadata only. TV text is never retained or exposed.
public struct TVKeyboardState: Equatable, Sendable {
    public let supported: Bool
    public let active: Bool
    public let canSendText: Bool
    public let focusID: UInt64
    /// The server's explicit input-window visibility, unknown until envelope 21.
    public let keyboardVisible: Bool?
    /// Advances only on the first explicit show for a focused editor.
    public let showRequestID: UInt64
    public let context: TVKeyboardContext?
    public let fieldLabel: String
    public let selectionStart: Int?
    public let selectionEnd: Int?
}

/// Remote v2 uses different client and server IME messages. In particular,
/// server 21 is visibility, not the client 21 batch-edit counter message.
/// Owned under TVRemote's state lock; no TV field contents are stored.
struct TVKeyboardTracker {
    static let maximumTextBytes = 4096
    static let maximumTextUTF16 = 2048
    static let allowedKeys: Set<Int> = [19, 20, 21, 22, 61, 66, 67, 112]
    private let connection = UUID()
    private(set) var supported = false
    private var appCounter: Int32?
    private var appPackage: String?
    private var field: Field?
    private var focusID: UInt64 = 0
    private var showRequestID: UInt64 = 0
    private var shownForFocus = false
    private var keyboardVisible: Bool?
    private var predictedCursor = false

    private struct Field {
        let counter: Int32
        let label: String
        var start: Int?
        var end: Int?
        var textLength: Int?
    }

    var snapshot: TVKeyboardState {
        let context: TVKeyboardContext?
        if supported, let appCounter, appCounter > 0, let field,
           let start = field.start, let end = field.end {
            context = TVKeyboardContext(connection: connection, focus: focusID,
                                        appCounter: appCounter, imeCounter: appCounter,
                                        fieldCounter: field.counter,
                                        selectionStart: start, selectionEnd: end)
        } else { context = nil }
        return TVKeyboardState(supported: supported, active: supported && appCounter != nil,
                               canSendText: context != nil, focusID: focusID,
                               keyboardVisible: keyboardVisible,
                               showRequestID: shownForFocus ? showRequestID : 0, context: context,
                               fieldLabel: field?.label ?? "", selectionStart: field?.start,
                               selectionEnd: field?.end)
    }

    mutating func configure(supported: Bool) {
        self.supported = supported
        clearFocus()
        keyboardVisible = nil
    }

    mutating func close() {
        supported = false
        clearFocus()
        keyboardVisible = nil
    }

    mutating func receive(_ message: Proto.Message) throws {
        guard supported else { return }
        if message.has(20) {
            let inject = try uniqueMessage(message, 20)
            guard inject.has(1) else { clearFocus(); return }
            let app = try uniqueMessage(inject, 1)
            let counter = try nonnegativeInt32(app, 1)
            let inputType = try nonnegativeInt32(app, 2)
            let package = try text(app, 12, maximumBytes: 512)
            // A non-editor app notification has counter/inputType zero, even
            // when it names the foreground package. No contents are required.
            guard counter > 0, inputType > 0 else { clearFocus(); return }
            if appCounter != counter || appPackage != package {
                clearFocus()
                appCounter = counter
                appPackage = package
            }
            if inject.has(2) {
                field = try Self.field(uniqueMessage(inject, 2))
                predictedCursor = false
            } else {
                // Some editors cannot extract text. Keep their identity, but
                // do not invent a revision or insertion position.
                field = nil
                predictedCursor = false
            }
            requestShowIfVisible()
        }
        if message.has(21) {
            let visibility = try uniqueMessage(message, 21)
            let raw = try scalar(visibility, 1)
            guard raw <= 1 else { throw TVProtocolError.malformed("Invalid TV keyboard visibility.") }
            _ = try nonnegativeInt32(visibility, 2) // InputMethodManager flags.
            keyboardVisible = raw == 1
            requestShowIfVisible()
        }
        if message.has(22) {
            let update = try uniqueMessage(message, 22)
            let raw = try scalar(update, 1)
            guard raw <= 1 else { throw TVProtocolError.malformed("Invalid TV keyboard acknowledgment.") }
            if update.has(2) {
                let next = try Self.field(uniqueMessage(update, 2))
                guard appCounter != nil else { return }
                if let field, next.counter < field.counter { return }
                // Own-edit acknowledgments can lag subsequent queued commits.
                // They advance the content revision without rewinding the
                // predicted cursor. External changes cancel captured input.
                if raw == 1, predictedCursor, let previous = field {
                    field = Field(counter: next.counter, label: next.label,
                                  start: previous.start, end: previous.end, textLength: previous.textLength)
                } else {
                    if let field, field.start != next.start || field.end != next.end || field.counter != next.counter {
                        focusID &+= 1
                    }
                    field = next
                    predictedCursor = false
                }
            }
        }
        if message.has(23) {
            let selection = try uniqueMessage(message, 23)
            let start = try Self.selection(signedInt32(selection, 2))
            let end = try Self.selection(signedInt32(selection, 3))
            if let previous = field {
                if previous.start != start || previous.end != end { focusID &+= 1 }
                field = Field(counter: previous.counter, label: previous.label, start: start, end: end, textLength: previous.textLength)
                predictedCursor = false
            }
        }
        if message.has(24) {
            _ = try uniqueMessage(message, 24)
            // The TV reports an external key while this client owns an edit.
            // Wait for authoritative cursor/content rather than reusing our
            // predicted position after another input source changed it.
            if var previous = field {
                focusID &+= 1
                previous.start = nil
                previous.end = nil
                field = previous
                predictedCursor = false
            }
        }
    }

    func validate(_ context: TVKeyboardContext) throws { _ = try currentContext(matching: context) }

    func currentContext(matching context: TVKeyboardContext, requireSelection: Bool = true) throws -> TVKeyboardContext {
        guard supported else { throw TVProtocolError.unsupported("This TV remote service does not support keyboard input.") }
        guard let appCounter, appCounter == context.appCounter,
              connection == context.connection, focusID == context.focus,
              let field else {
            throw TVProtocolError.unsupported("The TV text field changed. Select its text field and type again.")
        }
        if requireSelection, field.start == nil || field.end == nil {
            throw TVProtocolError.unsupported("Wait for the TV text cursor, then type again.")
        }
        let current = TVKeyboardContext(connection: connection, focus: focusID, appCounter: appCounter,
                                        imeCounter: appCounter, fieldCounter: field.counter,
                                        selectionStart: field.start ?? context.selectionStart,
                                        selectionEnd: field.end ?? context.selectionEnd)
        // A content revision is not an editor identity. Use the latest revision
        // and cursor for the same captured editor immediately before writing.
        return current
    }

    /// Reserve the cursor after one serialized commit, before its ACK arrives.
    /// Only lengths and positions are kept; never the committed text.
    mutating func committed(_ text: String, context: TVKeyboardContext) {
        guard context.connection == connection, context.focus == focusID, var field else { return }
        let position = min(context.selectionStart, context.selectionEnd) + text.utf16.count
        if let length = field.textLength {
            field.textLength = length - abs(context.selectionStart - context.selectionEnd) + text.utf16.count
        }
        field.start = position
        field.end = position
        self.field = field
        predictedCursor = true
    }

    mutating func keyWritten(_ key: Int) {
        guard var field else { return }
        // Actions may be no-ops and produce no metadata response. Changes made
        // by Enter/Tab still arrive through the ordinary editor/content events.
        if key == 66 || key == 61 { return }
        if let start = field.start, let end = field.end, start == end {
            if start == 0 && (key == 67 || key == 21) { return }
            if let length = field.textLength, start == length && (key == 112 || key == 22) { return }
        }
        field.start = nil
        field.end = nil
        self.field = field
        predictedCursor = false
    }

    /// The service maps this edit to setComposingRegion(start,end), followed by
    /// commitText(value,1). The positions describe the TV's current selection,
    /// not the length of the new chunk. UTF-16 positions match Android.
    static func textMessage(_ text: String, context: TVKeyboardContext) throws -> Proto.Writer {
        let count = text.utf16.count
        guard !text.isEmpty, text.utf8.count <= maximumTextBytes, count <= maximumTextUTF16,
              context.selectionStart >= 0, context.selectionEnd >= 0,
              max(context.selectionStart, context.selectionEnd) <= 32 * 1024,
              min(context.selectionStart, context.selectionEnd) + count <= 32 * 1024 else {
            throw TVProtocolError.unsupported("Type up to 2,048 characters at a time.")
        }
        let object = Proto.Writer()
        if context.selectionStart != 0 { try object.number(1, UInt64(context.selectionStart)) }
        if context.selectionEnd != 0 { try object.number(2, UInt64(context.selectionEnd)) }
        try object.text(3, text)
        let edit = try Proto.Writer().message(2, object)
        let batch = Proto.Writer()
        if context.imeCounter != 0 { try batch.number(1, UInt64(context.imeCounter)) }
        if context.fieldCounter != 0 { try batch.number(2, UInt64(context.fieldCounter)) }
        return try Proto.Writer().message(21, batch.message(3, edit))
    }

    private mutating func requestShowIfVisible() {
        if keyboardVisible == true, appCounter != nil, !shownForFocus {
            showRequestID &+= 1
            shownForFocus = true
        }
    }

    private mutating func clearFocus() {
        focusID &+= 1
        appCounter = nil
        appPackage = nil
        field = nil
        shownForFocus = false
        predictedCursor = false
    }

    private static func selection(_ value: Int32) throws -> Int? {
        guard value >= -1, value <= 32 * 1024 else {
            throw TVProtocolError.malformed("Invalid TV text selection.")
        }
        return value < 0 ? nil : Int(value)
    }

    private static func field(_ message: Proto.Message) throws -> Field {
        let counter = try nonnegativeInt32(message, 1)
        // Validate and release private TV contents immediately; no mirror exists.
        let contents = try text(message, 2, maximumBytes: 32 * 1024)
        let start = try selection(signedInt32(message, 3))
        let end = try selection(signedInt32(message, 4))
        let limit = contents.isEmpty ? 32 * 1024 : contents.utf16.count
        guard (start == nil && end == nil) ||
                (start != nil && end != nil && start! <= limit && end! <= limit) else {
            throw TVProtocolError.malformed("Invalid TV text selection.")
        }
        let label = try text(message, 6, maximumBytes: 512)
            .filter { !$0.isNewline && !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }
        let length = contents.utf16.count
        let knownLength = message.has(2) && length >= max(start ?? 0, end ?? 0) ? length : nil
        return Field(counter: counter, label: label, start: start, end: end, textLength: knownLength)
    }
}

private func scalar(_ message: Proto.Message, _ field: Int) throws -> UInt64 {
    guard message.fields.filter({ $0.number == field }).count <= 1 else {
        throw TVProtocolError.malformed("Ambiguous TV keyboard value.")
    }
    return try message.integer(field)
}

private func uniqueMessage(_ message: Proto.Message, _ field: Int) throws -> Proto.Message {
    guard message.fields.filter({ $0.number == field }).count == 1 else {
        throw TVProtocolError.malformed("Ambiguous TV keyboard message.")
    }
    return try message.message(field)
}

private func signedInt32(_ message: Proto.Message, _ field: Int) throws -> Int32 {
    guard message.fields.filter({ $0.number == field }).count <= 1 else {
        throw TVProtocolError.malformed("Ambiguous TV keyboard value.")
    }
    let value = try message.integer(field)
    guard value <= UInt64(UInt32.max) || value >= 0xffff_ffff_8000_0000 else {
        throw TVProtocolError.malformed("Invalid TV keyboard integer.")
    }
    return Int32(truncatingIfNeeded: value)
}

private func nonnegativeInt32(_ message: Proto.Message, _ field: Int) throws -> Int32 {
    let value = try signedInt32(message, field)
    guard value >= 0 else { throw TVProtocolError.malformed("Invalid TV keyboard counter.") }
    return value
}

private func text(_ message: Proto.Message, _ field: Int, maximumBytes: Int) throws -> String {
    guard message.fields.filter({ $0.number == field }).count <= 1 else {
        throw TVProtocolError.malformed("Ambiguous TV keyboard text.")
    }
    guard let data = try message.bytes(field) else { return "" }
    guard data.count <= maximumBytes, let value = String(data: data, encoding: .utf8) else {
        throw TVProtocolError.malformed("Invalid TV keyboard text.")
    }
    return value
}
