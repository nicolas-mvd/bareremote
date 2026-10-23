import Foundation

/// The small command vocabulary exposed to the desktop widget. URLs never
/// carry a TV address, pairing material, an arbitrary Android key or app link.
public enum RemoteShortcutAction: Equatable, Sendable {
    case key(Int)
    case sync
    case inputs
    case toggle
    case open

    public init(url: URL) throws {
        self = try RemoteShortcutCommand(url: url).action
    }

    /// A static WidgetKit Link should omit the identifier: every intentional
    /// tap is a new command. An AppIntent may supply a new UUID per execution
    /// if its delivery needs to be recognized across multiple routing hooks.
    public func url(deliveryIdentifier: UUID? = nil) throws -> URL {
        let path: String
        var query: [String] = []
        switch self {
        case .key(let code):
            guard RemoteShortcutCommand.approvedKeys.contains(code) else {
                throw RemoteShortcutCommand.InvalidURL()
            }
            path = "key"
            query.append("code=\(code)")
        case .sync: path = "sync"
        case .inputs: path = "inputs"
        case .toggle: path = "toggle"
        case .open: path = "open"
        }
        if let deliveryIdentifier { query.append("id=\(deliveryIdentifier.uuidString.lowercased())") }
        let suffix = query.isEmpty ? "" : "?" + query.joined(separator: "&")
        // Every interpolated component is a fixed path, approved integer or UUID.
        return URL(string: "bareremote://widget/\(path)\(suffix)")!
    }
}

/// Validates the complete local widget command before any TV work is queued.
public struct RemoteShortcutCommand: Equatable, Sendable {
    public let action: RemoteShortcutAction
    public let deliveryIdentifier: UUID?

    static let approvedKeys: Set<Int> = [19, 20, 21, 22, 23, 4, 3, 24, 25, 164, 26, 85]

    public init(url: URL) throws {
        let value = url.absoluteString
        guard url.baseURL == nil, value.utf8.count <= 256,
              value.utf8.allSatisfy({ $0 > 0x20 && $0 < 0x7f && $0 != 0x25 }),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme == "bareremote", parts.percentEncodedHost == "widget",
              parts.user == nil, parts.password == nil, parts.port == nil,
              parts.fragment == nil else { throw InvalidURL() }

        var arguments: [String: String] = [:]
        if let query = parts.percentEncodedQuery {
            for argument in query.split(separator: "&", omittingEmptySubsequences: false) {
                let pair = argument.split(separator: "=", omittingEmptySubsequences: false)
                guard pair.count == 2, !pair[1].isEmpty,
                      pair[0] == "code" || pair[0] == "id",
                      arguments[String(pair[0])] == nil else { throw InvalidURL() }
                arguments[String(pair[0])] = String(pair[1])
            }
        }

        if let identifier = arguments.removeValue(forKey: "id") {
            guard identifier.utf8.count == 36, let uuid = UUID(uuidString: identifier),
                  uuid.uuidString.lowercased() == identifier.lowercased() else { throw InvalidURL() }
            deliveryIdentifier = uuid
        } else { deliveryIdentifier = nil }

        switch parts.percentEncodedPath {
        case "/key":
            guard let rawCode = arguments.removeValue(forKey: "code"),
                  let code = Int(rawCode), String(code) == rawCode,
                  Self.approvedKeys.contains(code) else { throw InvalidURL() }
            action = .key(code)
        case "/sync": action = .sync
        case "/inputs": action = .inputs
        case "/toggle": action = .toggle
        case "/open": action = .open
        default: throw InvalidURL()
        }
        guard arguments.isEmpty else { throw InvalidURL() }
    }

    public struct InvalidURL: LocalizedError {
        public var errorDescription: String? { "This BareRemote widget command is invalid." }
    }
}
