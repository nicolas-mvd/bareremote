import Foundation

/// An opaque, connection-scoped target for a keyboard operation. Keep the token
/// captured with the user's input; never replace a stale token and retry input.
public struct TVKeyboardContext: Equatable, Sendable {
    let connection: UUID
    let focus: UInt64
    let appCounter: Int32?
    let imeCounter: Int32
    let fieldCounter: Int32
}

/// A transient IME snapshot. TV text is deliberately neither retained nor exposed:
/// a remote service may include passwords in otherwise ordinary field messages.
public struct TVKeyboardState: Equatable, Sendable {
    public let supported: Bool
    public let active: Bool
    public let canSendText: Bool
    public let focusID: UInt64
    /// Zero unless the TV explicitly requested the keyboard for this focus.
    /// A positive identifier advances once for each focus's first show request.
    /// Repeated show messages for the same field do not undo a manual Remote choice.
    public let showRequestID: UInt64
    public let context: TVKeyboardContext?
    public let fieldLabel: String
    public let selectionStart: Int?
    public let selectionEnd: Int?
}

/// Pure state machine, owned under TVRemote's state lock. Counters and focus are
/// tracked independently because a show request and its batch counters can arrive
/// in either order. Unmatched counters never authorize an input operation.
struct TVKeyboardTracker {
    static let maximumTextBytes = 4096
    static let maximumTextUTF16 = 2048
    static let allowedKeys: Set<Int> = [19, 20, 21, 22, 61, 66, 67, 112]
    private let connection = UUID()
    private(set) var supported = false
    private var appCounter: Int32?
    private var appPackage: String?
    private var field: Field?
    private var counters: Counters?
    private var latestCounters: Counters?
    private var latestFieldCounter: Int32?
    private var focusID: UInt64 = 0
    private var showRequestID: UInt64 = 0
    private var shownForFocus = false

    private struct Field {
        let counter: Int32
        let label: String
        let start: Int?
        let end: Int?
    }
    private struct Counters: Equatable {
        let ime: Int32
        let field: Int32
        func precedes(_ other: Counters) -> Bool {
            ime < other.ime || (ime == other.ime && field < other.field)
        }
    }

    var snapshot: TVKeyboardState {
        let context: TVKeyboardContext?
        if supported, let field, let counters, counters.field == field.counter {
            context = TVKeyboardContext(connection: connection, focus: focusID,
                                        appCounter: appCounter, imeCounter: counters.ime,
                                        fieldCounter: counters.field)
        } else { context = nil }
        return TVKeyboardState(supported: supported, active: supported && field != nil,
                               canSendText: context != nil, focusID: focusID,
                               showRequestID: shownForFocus ? showRequestID : 0, context: context,
                               fieldLabel: field?.label ?? "", selectionStart: field?.start,
                               selectionEnd: field?.end)
    }

    mutating func configure(supported: Bool) {
        self.supported = supported
        clearFocus()
        counters = nil
        latestCounters = nil
        latestFieldCounter = nil
        appCounter = nil
        appPackage = nil
    }

    mutating func close() {
        supported = false
        clearFocus()
        counters = nil
        latestCounters = nil
        latestFieldCounter = nil
        appCounter = nil
        appPackage = nil
    }

    mutating func receive(_ message: Proto.Message) throws {
        guard supported else { return }
        if message.has(20) {
            let inject = try uniqueMessage(message, 20)
            if inject.has(1) {
                let app = try uniqueMessage(inject, 1)
                let counter = try nonnegativeInt32(app, 1)
                let package = try text(app, 12, maximumBytes: 512)
                if let previous = appCounter, counter < previous { return }
                if appCounter != counter || appPackage != package {
                    clearFocus()
                    counters = nil
                    latestCounters = nil
                    latestFieldCounter = nil
                }
                appCounter = counter
                appPackage = package
            }
            if inject.has(2) {
                _ = try updateField(try Self.field(uniqueMessage(inject, 2)))
            } else {
                // App information without a field also signals loss of the editor.
                clearFocus()
                counters = nil
            }
        }
        if message.has(21) {
            let batch = try uniqueMessage(message, 21)
            let next = Counters(ime: try nonnegativeInt32(batch, 1),
                                field: try nonnegativeInt32(batch, 2))
            if let latestCounters, next.precedes(latestCounters) { return }
            latestCounters = next
            counters = next
        }
        if message.has(22) {
            let request = try uniqueMessage(message, 22)
            guard request.has(2) else {
                clearFocus()
                counters = nil
                return
            }
            guard try updateField(try Self.field(uniqueMessage(request, 2))) else { return }
            if !shownForFocus {
                showRequestID &+= 1
                shownForFocus = true
            }
        }
    }

    func validate(_ context: TVKeyboardContext) throws {
        _ = try currentContext(matching: context)
    }

    func currentContext(matching context: TVKeyboardContext) throws -> TVKeyboardContext {
        guard supported else { throw TVProtocolError.unsupported("This TV remote service does not support keyboard input.") }
        guard let current = snapshot.context,
              current.connection == context.connection, current.focus == context.focus,
              current.appCounter == context.appCounter, current.fieldCounter == context.fieldCounter else {
            throw TVProtocolError.unsupported("The TV text field changed. Select its text field and type again.")
        }
        // IME counters may acknowledge edits inside the same field. Use the latest
        // counter without re-targeting the field captured with the user's input.
        return current
    }

    /// A native IME commit, not a replacement of the TV field. Positions use
    /// Android/NSString UTF-16 units, following the established Remote v2 client
    /// convention of the final inserted unit's index. Selection edits are left to
    /// scoped Android keys instead of inventing unverified edit opcodes.
    static func textMessage(_ text: String, context: TVKeyboardContext) throws -> Proto.Writer {
        let count = text.utf16.count
        guard !text.isEmpty, text.utf8.count <= maximumTextBytes,
              count <= maximumTextUTF16 else {
            throw TVProtocolError.unsupported("Type up to 2,048 characters at a time.")
        }
        let position = UInt64(count - 1)
        let object = Proto.Writer()
        // Match proto3's canonical omission of zero scalar defaults.
        if position != 0 { try object.number(1, position).number(2, position) }
        try object.text(3, text)
        let edit = try Proto.Writer().number(1, 1).message(2, object)
        let batch = Proto.Writer()
        if context.imeCounter != 0 { try batch.number(1, UInt64(context.imeCounter)) }
        if context.fieldCounter != 0 { try batch.number(2, UInt64(context.fieldCounter)) }
        return try Proto.Writer().message(21, batch.message(3, edit))
    }

    private mutating func updateField(_ next: Field) throws -> Bool {
        if let latestFieldCounter, next.counter < latestFieldCounter { return false }
        if field?.counter != next.counter {
            focusID &+= 1
            shownForFocus = false
        }
        field = next
        latestFieldCounter = next.counter
        return true
    }

    private mutating func clearFocus() {
        if field != nil { focusID &+= 1 }
        field = nil
        shownForFocus = false
    }

    private static func field(_ message: Proto.Message) throws -> Field {
        let counter = try nonnegativeInt32(message, 1)
        // Inspect only bounds and selection; do not copy TV contents into state.
        let contents = try text(message, 2, maximumBytes: 32 * 1024)
        let start = try signedInt32(message, 3)
        let end = try signedInt32(message, 4)
        let selectionLimit = contents.isEmpty ? 32 * 1024 : contents.utf16.count
        // Services may omit/redact a password's contents while still reporting
        // its cursor. Do not infer an empty TV field from absent string data.
        guard (start == -1 && end == -1) ||
                (start >= 0 && end >= 0 && start <= selectionLimit && end <= selectionLimit) else {
            throw TVProtocolError.malformed("Invalid TV text selection.")
        }
        let label = try text(message, 6, maximumBytes: 512)
            .filter { !$0.isNewline && !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }
        return Field(counter: counter, label: label,
                     start: start < 0 ? nil : Int(start), end: end < 0 ? nil : Int(end))
    }
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
