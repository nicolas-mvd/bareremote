import Darwin
import Foundation
import Security

/// The single locally paired TV. Its certificate is an exact DER pin.
public struct SavedTV: Codable, Equatable, Sendable {
    public var host: String
    public var name: String
    public var certificate: Data

    public init(host: String, name: String, certificate: Data) {
        self.host = host
        self.name = name
        self.certificate = certificate
    }

    /// Loads the saved pairing, or nil when none exists. Invalid storage fails
    /// closed instead of silently discarding the pin and initiating pairing.
    public static func load(from url: URL? = nil) throws -> SavedTV? {
        let location = try url ?? storageURL()
        let manager = FileManager.default
        guard manager.fileExists(atPath: location.path) else { return nil }
        let attributes = try manager.attributesOfItem(atPath: location.path)
        guard let size = attributes[.size] as? NSNumber, size.intValue <= 128 * 1024 else {
            throw StorageError.invalidFile
        }
        let saved = try JSONDecoder().decode(SavedTV.self, from: Data(contentsOf: location))
        try saved.validate()
        return saved
    }

    /// Atomically replaces the saved pairing only after validating all fields.
    public func save(to url: URL? = nil) throws {
        try validate()
        let location = try url ?? Self.storageURL()
        let manager = FileManager.default
        try manager.createDirectory(at: location.deletingLastPathComponent(),
                                    withIntermediateDirectories: true,
                                    attributes: [.posixPermissions: 0o700])
        let data = try JSONEncoder().encode(self)
        let temporary = location.deletingLastPathComponent()
            .appendingPathComponent(".tv-\(UUID().uuidString).tmp")
        defer { try? manager.removeItem(at: temporary) }
        try data.write(to: temporary, options: .withoutOverwriting)
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: temporary.path)
        // Configure the complete replacement before the atomic rename. There
        // must be no fallible step after committing a changed saved address.
        let status = temporary.withUnsafeFileSystemRepresentation { source in
            location.withUnsafeFileSystemRepresentation { destination in
                Darwin.rename(source!, destination!)
            }
        }
        guard status == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    }

    /// Removes only the saved TV. The installation's Keychain identity remains.
    public static func forget(at url: URL? = nil) throws {
        let location = try url ?? storageURL()
        do {
            try FileManager.default.removeItem(at: location)
        } catch CocoaError.fileNoSuchFile {
            // Forget is idempotent.
        }
    }

    private static func storageURL() throws -> URL {
        try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                    appropriateFor: nil, create: false)
            .appendingPathComponent("BareRemote", isDirectory: true)
            .appendingPathComponent("tv.json", isDirectory: false)
    }

    private func validate() throws {
        var address = in_addr()
        guard host.utf8.count <= 15, host.withCString({ inet_pton(AF_INET, $0, &address) }) == 1 else {
            throw StorageError.invalidHost
        }
        let components = host.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 4, components.allSatisfy({ component in
            guard let number = UInt8(component) else { return false }
            return String(number) == component
        }) else { throw StorageError.invalidHost }
        guard name.utf8.count <= 256, !name.contains(where: { $0.isNewline || $0.asciiValue.map { $0 < 32 } == true }) else {
            throw StorageError.invalidName
        }
        guard !certificate.isEmpty, certificate.count <= 64 * 1024,
              SecCertificateCreateWithData(nil, certificate as CFData) != nil else {
            throw StorageError.invalidCertificate
        }
    }

    public enum StorageError: LocalizedError {
        case invalidFile, invalidHost, invalidName, invalidCertificate

        public var errorDescription: String? {
            switch self {
            case .invalidFile: return "The saved TV file is invalid."
            case .invalidHost: return "Enter the TV's numeric IPv4 address."
            case .invalidName: return "The TV name is invalid."
            case .invalidCertificate: return "The saved TV certificate is invalid. Pair with a TV code again."
            }
        }
    }
}
