import Foundation

public struct MacUpdateRelease: Equatable, Sendable {
    public let versionCode: Int
    public let versionName: String
    public let zipURL: URL
    public let sha256: String
    public let size: Int
    public init(versionCode: Int, versionName: String, zipURL: URL, sha256: String, size: Int) throws {
        guard versionCode > 0, versionCode <= Int(Int32.max),
              versionName.range(of: "^[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}$", options: .regularExpression) != nil,
              sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              size > 0, size <= MacUpdatePolicy.maximumArchiveSize,
              MacUpdatePolicy.officialAsset(zipURL) else { throw MacUpdateError.invalidMetadata }
        self.versionCode = versionCode; self.versionName = versionName
        self.zipURL = zipURL; self.sha256 = sha256; self.size = size
    }
}

public enum MacUpdateError: LocalizedError, Equatable {
    case invalidMetadata, invalidURL, tooLarge, wrongSize, wrongChecksum, invalidArchive
    case invalidBundle, invalidSignature, notNewer, notWritable, stopped, network, commandFailed, installFailed
    public var errorDescription: String? {
        switch self {
        case .invalidMetadata: return "The update information could not be verified."
        case .invalidURL: return "The update address is not an official BareRemote release."
        case .tooLarge: return "The update exceeds the download limit."
        case .wrongSize, .wrongChecksum: return "The download did not match the published update. Nothing was installed."
        case .invalidArchive: return "The update archive contains unsupported or unsafe files. Nothing was installed."
        case .invalidBundle: return "The downloaded app has an unexpected identity or version. Nothing was installed."
        case .invalidSignature: return "The app signature does not match this installation. Nothing was installed."
        case .notNewer: return "This update is no longer newer than the installed app."
        case .notWritable: return "BareRemote cannot replace this app in its current location. Move it to a writable Applications folder, then try again."
        case .stopped: return "Update cancelled or timed out. Nothing was installed."
        case .network: return "Could not reach the update server. Check your connection and try again."
        case .commandFailed: return "The update could not be prepared. Nothing was installed."
        case .installFailed: return "The update could not be installed. Your previous app has been kept."
        }
    }
}

public enum MacUpdatePolicy {
    public static let metadataURL = URL(string: "https://raw.githubusercontent.com/nicolas-mvd/bareremote/main/macos-update.json")!
    public static let maximumMetadataSize = 16 * 1024
    public static let maximumArchiveSize = 32 * 1024 * 1024
    public static let maximumExpandedSize = 128 * 1024 * 1024
    public static let bundleIdentifier = "uy.nico.bareremote.macos"

    private static func secure(_ url: URL) -> Bool {
        url.absoluteString.utf8.count <= 8192 && url.scheme == "https" && url.user == nil && url.password == nil &&
        url.port == nil && url.fragment == nil && url.host != nil
    }
    public static func officialAsset(_ url: URL) -> Bool {
        guard secure(url), url.host == "github.com", url.query == nil else { return false }
        // Reject encoded path separators, encoded traversal and alternate asset names.
        return url.absoluteString.range(of: "^https://github\\.com/nicolas-mvd/bareremote/releases/download/[A-Za-z0-9][A-Za-z0-9._+-]{0,95}/BareRemote-macOS\\.zip$", options: .regularExpression) != nil
    }
    public static func downloadHost(_ url: URL) -> Bool {
        secure(url) && ["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"].contains(url.host ?? "")
    }
    public static func parse(_ data: Data) throws -> MacUpdateRelease {
        guard data.count <= maximumMetadataSize, let text = String(data: data, encoding: .utf8) else { throw MacUpdateError.invalidMetadata }
        var parser = UpdateJSONParser(text)
        let fields = try parser.parse()
        guard fields.count == 5, case .integer(let version)? = fields["versionCode"],
              case .string(let name)? = fields["versionName"], case .string(let address)? = fields["zipUrl"],
              case .string(let sha)? = fields["sha256"], case .integer(let size)? = fields["size"],
              let url = URL(string: address) else { throw MacUpdateError.invalidMetadata }
        return try MacUpdateRelease(versionCode: version, versionName: name, zipURL: url, sha256: sha, size: size)
    }
}

/// Exactly five scalar fields; JSONDecoder would silently accept duplicate keys.
private struct UpdateJSONParser {
    enum Value { case string(String), integer(Int) }
    let bytes: [UInt8]
    var at = 0
    init(_ text: String) { bytes = Array(text.utf8) }
    mutating func whitespace() { while at < bytes.count && [9, 10, 13, 32].contains(bytes[at]) { at += 1 } }
    mutating func take(_ byte: UInt8) -> Bool { whitespace(); guard at < bytes.count && bytes[at] == byte else { return false }; at += 1; return true }
    mutating func expect(_ byte: UInt8) throws { guard take(byte) else { throw MacUpdateError.invalidMetadata } }
    mutating func string() throws -> String {
        whitespace(); let start = at; try expect(34)
        var escaped = false
        while at < bytes.count {
            let b = bytes[at]; at += 1
            if b < 32 { throw MacUpdateError.invalidMetadata }
            if b == 34 && !escaped {
                let data = Data(bytes[start..<at])
                guard let result = try? JSONDecoder().decode(String.self, from: data) else { throw MacUpdateError.invalidMetadata }
                return result
            }
            if b == 92 && !escaped { escaped = true } else { escaped = false }
        }
        throw MacUpdateError.invalidMetadata
    }
    mutating func integer() throws -> Int {
        whitespace(); let start = at
        while at < bytes.count && (48...57).contains(bytes[at]) { at += 1 }
        guard start != at, at - start == 1 || bytes[start] != 48,
              let number = Int(String(decoding: bytes[start..<at], as: UTF8.self)) else { throw MacUpdateError.invalidMetadata }
        return number
    }
    mutating func parse() throws -> [String: Value] {
        try expect(123); var fields: [String: Value] = [:]
        while true {
            let key = try string(); try expect(58)
            guard fields[key] == nil else { throw MacUpdateError.invalidMetadata }
            switch key {
            case "versionCode", "size": fields[key] = .integer(try integer())
            case "versionName", "zipUrl", "sha256": fields[key] = .string(try string())
            default: throw MacUpdateError.invalidMetadata
            }
            if !take(44) { break }
        }
        try expect(125); whitespace(); guard at == bytes.count else { throw MacUpdateError.invalidMetadata }
        return fields
    }
}
