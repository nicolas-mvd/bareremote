import Foundation
import Security

/// A persistent, installation-specific client certificate and its Keychain key.
/// The private RSA key is generated in Keychain and is never exported.
public final class ClientIdentity {
    public let identity: SecIdentity
    public let certificate: SecCertificate

    private static let productionNamespace = "uy.nico.bareremote.macos.client.v1"
    private static let lock = NSLock()

    private init(identity: SecIdentity, certificate: SecCertificate) {
        self.identity = identity
        self.certificate = certificate
    }

    public static func loadOrCreate() throws -> ClientIdentity {
        try loadOrCreate(namespace: productionNamespace)
    }

    /// A separate namespace is useful for isolated local fixtures. The namespace
    /// is both the certificate label and the private key's application tag.
    public static func loadOrCreate(namespace: String) throws -> ClientIdentity {
        guard !namespace.isEmpty, namespace.utf8.count <= 200 else {
            throw IdentityError.invalidNamespace
        }
        lock.lock()
        defer { lock.unlock() }

        if let certificate = try storedCertificate(namespace: namespace) {
            return try makeIdentity(certificate: certificate)
        }

        let key = try storedPrivateKey(namespace: namespace) ?? createPrivateKey(namespace: namespace)
        // Recover an interrupted insertion before considering a new certificate.
        // The certificate can exist under its default subject label if the
        // following metadata update failed or the process stopped between calls.
        if let certificate = try storedCertificate(for: key) {
            try setCertificateLabel(certificate, namespace: namespace)
            return try makeIdentity(certificate: certificate)
        }
        let certificateData = try selfSignedCertificate(key: key)
        guard let certificate = SecCertificateCreateWithData(nil, certificateData as CFData) else {
            throw IdentityError.invalidCertificate
        }
        let status = SecItemAdd([
            kSecClass: kSecClassCertificate,
            kSecAttrLabel: namespace,
            kSecValueRef: certificate
        ] as CFDictionary, nil)
        guard status == errSecSuccess else { throw IdentityError.keychain(status) }
        // The legacy macOS Keychain derives a certificate's initial label from
        // its subject even when SecItemAdd supplies a label. Set our lookup
        // label explicitly after insertion, before this identity is returned.
        try setCertificateLabel(certificate, namespace: namespace)
        return try makeIdentity(certificate: certificate)
    }

    private static func setCertificateLabel(_ certificate: SecCertificate, namespace: String) throws {
        let labelStatus = SecItemUpdate([
            kSecClass: kSecClassCertificate,
            kSecValueRef: certificate
        ] as CFDictionary, [kSecAttrLabel: namespace] as CFDictionary)
        guard labelStatus == errSecSuccess else {
            throw IdentityError.keychain(labelStatus)
        }
    }

    /// Removes only this namespace's local key and certificate. Production
    /// Forget TV does not call this: it retains the installation's identity.
    public static func delete(namespace: String) throws {
        guard namespace != productionNamespace, !namespace.isEmpty else {
            throw IdentityError.invalidNamespace
        }
        lock.lock()
        defer { lock.unlock() }
        for query: [CFString: Any] in [
            [kSecClass: kSecClassCertificate, kSecAttrLabel: namespace],
            [kSecClass: kSecClassKey, kSecAttrApplicationTag: Data(namespace.utf8),
             kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPrivate]
        ] {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw IdentityError.keychain(status)
            }
        }
    }

    private static func storedCertificate(namespace: String) throws -> SecCertificate? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassCertificate, kSecAttrLabel: namespace,
            kSecReturnRef: true, kSecMatchLimit: kSecMatchLimitOne
        ] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw IdentityError.keychain(status) }
        guard let result, CFGetTypeID(result) == SecCertificateGetTypeID() else {
            throw IdentityError.invalidCertificate
        }
        return (result as! SecCertificate)
    }

    private static func storedPrivateKey(namespace: String) throws -> SecKey? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassKey, kSecAttrApplicationTag: Data(namespace.utf8),
            kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
            kSecReturnRef: true, kSecMatchLimit: kSecMatchLimitOne
        ] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw IdentityError.keychain(status) }
        guard let result, CFGetTypeID(result) == SecKeyGetTypeID() else {
            throw IdentityError.missingPrivateKey
        }
        return (result as! SecKey)
    }

    private static func storedCertificate(for key: SecKey) throws -> SecCertificate? {
        guard let attributes = SecKeyCopyAttributes(key) as? [String: Any],
              let hash = attributes[kSecAttrApplicationLabel as String] as? Data else {
            throw IdentityError.missingPrivateKey
        }
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassCertificate, kSecAttrPublicKeyHash: hash,
            kSecReturnRef: true, kSecMatchLimit: kSecMatchLimitAll
        ] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw IdentityError.keychain(status) }
        guard let certificates = result as? [SecCertificate], certificates.count == 1 else {
            // Choosing among different subjects would silently change which
            // identity a TV recognizes. Require explicit repair instead.
            throw IdentityError.ambiguousCertificate
        }
        return certificates[0]
    }

    private static func createPrivateKey(namespace: String) throws -> SecKey {
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits: 2048,
            // macOS's legacy Keychain reads extractability only from the
            // top-level generation dictionary, unlike permanence and tags.
            kSecAttrIsExtractable: false,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: Data(namespace.utf8),
                kSecAttrLabel: namespace
            ]
        ] as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw IdentityError.keyGeneration
        }
        return key
    }

    private static func makeIdentity(certificate: SecCertificate) throws -> ClientIdentity {
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, certificate, &identity)
        guard status == errSecSuccess, let identity else {
            throw IdentityError.keychain(status == errSecSuccess ? errSecItemNotFound : status)
        }
        return ClientIdentity(identity: identity, certificate: certificate)
    }

    private static func selfSignedCertificate(key: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let publicKey = SecKeyCopyPublicKey(key),
              let publicBytes = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            if let error { throw error.takeRetainedValue() }
            throw IdentityError.keyGeneration
        }

        // The TV service deduplicates clients by subject, so unique RSA keys
        // alone are insufficient. This UUID is persisted in the certificate.
        let name = DER.sequence(DER.set(DER.sequence(
            DER.oid([2, 5, 4, 3]) + DER.tag(0x0c, Data("BareRemote macOS \(UUID().uuidString)".utf8))
        )))
        let signatureAlgorithm = DER.sequence(DER.oid([1, 2, 840, 113549, 1, 1, 11]) + DER.null)
        let publicAlgorithm = DER.sequence(DER.oid([1, 2, 840, 113549, 1, 1, 1]) + DER.null)
        let publicInfo = DER.sequence(publicAlgorithm + DER.bitString(publicBytes))
        var serial = Data(count: 20)
        let randomStatus = serial.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw IdentityError.keychain(randomStatus) }
        serial[0] &= 0x7f
        if serial.allSatisfy({ $0 == 0 }) { serial[serial.count - 1] = 1 }

        let now = Date()
        let validity = DER.sequence(DER.time(now.addingTimeInterval(-86400)) +
                                    DER.time(now.addingTimeInterval(30 * 365.25 * 86400)))
        let extensions = DER.tag(0xa3, DER.sequence(
            DER.sequence(DER.oid([2, 5, 29, 19]) + DER.boolean(true) + DER.octetString(DER.sequence(Data()))) +
            DER.sequence(DER.oid([2, 5, 29, 15]) + DER.boolean(true) + DER.octetString(DER.tag(0x03, Data([5, 0xa0])))) +
            DER.sequence(DER.oid([2, 5, 29, 37]) + DER.octetString(DER.sequence(DER.oid([1, 3, 6, 1, 5, 5, 7, 3, 2]))))
        ))
        let body = DER.sequence(DER.tag(0xa0, DER.integer(Data([2]))) + DER.integer(serial) +
                                signatureAlgorithm + name + validity + name + publicInfo + extensions)
        guard let signature = SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256,
                                                    body as CFData, &error) as Data? else {
            if let error { throw error.takeRetainedValue() }
            throw IdentityError.keyGeneration
        }
        return DER.sequence(body + signatureAlgorithm + DER.bitString(signature))
    }

    public enum IdentityError: LocalizedError {
        case invalidNamespace, invalidCertificate, missingPrivateKey, keyGeneration, ambiguousCertificate
        case keychain(OSStatus)

        public var errorDescription: String? {
            switch self {
            case .invalidNamespace: return "The client identity namespace is invalid."
            case .invalidCertificate: return "The stored client certificate is invalid."
            case .missingPrivateKey: return "The client certificate's Keychain key is missing."
            case .keyGeneration: return "The client identity could not be generated."
            case .ambiguousCertificate: return "The client key has multiple certificates. Its saved identity needs repair."
            case .keychain(let status):
                return "Keychain: \((SecCopyErrorMessageString(status, nil) as String?) ?? String(status))"
            }
        }
    }
}

/// The small DER subset required for a self-signed RSA X.509 certificate.
private enum DER {
    static let null = Data([0x05, 0x00])

    static func tag(_ type: UInt8, _ value: Data) -> Data {
        var length = Data()
        if value.count < 128 {
            length.append(UInt8(value.count))
        } else {
            var count = value.count
            while count > 0 {
                length.insert(UInt8(count & 0xff), at: 0)
                count >>= 8
            }
            length.insert(0x80 | UInt8(length.count), at: 0)
        }
        return Data([type]) + length + value
    }

    static func sequence(_ value: Data) -> Data { tag(0x30, value) }
    static func set(_ value: Data) -> Data { tag(0x31, value) }
    static func octetString(_ value: Data) -> Data { tag(0x04, value) }
    static func bitString(_ value: Data) -> Data { tag(0x03, Data([0]) + value) }
    static func boolean(_ value: Bool) -> Data { tag(0x01, Data([value ? 0xff : 0])) }

    static func integer(_ value: Data) -> Data {
        var bytes = value
        while bytes.count > 1 && bytes.first == 0 { bytes.removeFirst() }
        if let first = bytes.first, first & 0x80 != 0 { bytes.insert(0, at: 0) }
        return tag(0x02, bytes)
    }

    static func oid(_ components: [UInt64]) -> Data {
        var bytes = Data([UInt8(components[0] * 40 + components[1])])
        for component in components.dropFirst(2) {
            var value = component
            var encoded = [UInt8(value & 0x7f)]
            value >>= 7
            while value > 0 {
                encoded.insert(UInt8(value & 0x7f) | 0x80, at: 0)
                value >>= 7
            }
            bytes.append(contentsOf: encoded)
        }
        return tag(0x06, bytes)
    }

    static func time(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let year = calendar.component(.year, from: date)
        let utc = (1950..<2050).contains(year)
        formatter.dateFormat = utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'"
        return tag(utc ? 0x17 : 0x18, Data(formatter.string(from: date).utf8))
    }
}
