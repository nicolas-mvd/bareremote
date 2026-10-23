import Foundation
import Security
import XCTest
@testable import BareRemoteCore

final class IdentityTests: XCTestCase {
    func testKeychainIdentityPersistsAndCanSignWithoutExportingPrivateKey() throws {
        let namespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        defer { try? ClientIdentity.delete(namespace: namespace) }
        let first = try ClientIdentity.loadOrCreate(namespace: namespace)
        let reloaded = try ClientIdentity.loadOrCreate(namespace: namespace)
        XCTAssertEqual(SecCertificateCopyData(first.certificate) as Data,
                       SecCertificateCopyData(reloaded.certificate) as Data)
        var privateKey: SecKey?
        XCTAssertEqual(SecIdentityCopyPrivateKey(reloaded.identity, &privateKey), errSecSuccess)
        let key = try XCTUnwrap(privateKey)
        let attributes = try XCTUnwrap(SecKeyCopyAttributes(key) as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrIsPermanent as String] as? Bool, true)
        XCTAssertEqual(attributes[kSecAttrIsExtractable as String] as? Bool, false)
        var exportError: Unmanaged<CFError>?
        let exported = SecKeyCopyExternalRepresentation(key, &exportError)
        XCTAssertTrue(exported == nil, "The Keychain must refuse private key export")
        let message = Data("BareRemote real Keychain signing test".utf8)
        var signingError: Unmanaged<CFError>?
        let signature = try XCTUnwrap(SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256,
                                                            message as CFData, &signingError) as Data?)
        let publicKey = try XCTUnwrap(SecCertificateCopyKey(reloaded.certificate))
        XCTAssertTrue(SecKeyVerifySignature(publicKey, .rsaSignatureMessagePKCS1v15SHA256,
                                            message as CFData, signature as CFData, nil))
    }

    func testSeparateInstallationsHaveDistinctPersistentSubjects() throws {
        let firstNamespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        let secondNamespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        defer {
            try? ClientIdentity.delete(namespace: firstNamespace)
            try? ClientIdentity.delete(namespace: secondNamespace)
        }
        let first = try ClientIdentity.loadOrCreate(namespace: firstNamespace)
        let second = try ClientIdentity.loadOrCreate(namespace: secondNamespace)
        let firstSubject = try XCTUnwrap(SecCertificateCopySubjectSummary(first.certificate) as String?)
        let secondSubject = try XCTUnwrap(SecCertificateCopySubjectSummary(second.certificate) as String?)
        XCTAssertTrue(firstSubject.hasPrefix("BareRemote macOS "))
        XCTAssertNotEqual(firstSubject, secondSubject,
                          "TVs that deduplicate by certificate subject must retain both Mac identities")
        let reloaded = try ClientIdentity.loadOrCreate(namespace: firstNamespace)
        XCTAssertEqual(firstSubject, SecCertificateCopySubjectSummary(reloaded.certificate) as String?)
    }

    func testCertificateLabelRecoveryRetainsTheOriginalIdentity() throws {
        let namespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        defer { try? ClientIdentity.delete(namespace: namespace) }
        let first = try ClientIdentity.loadOrCreate(namespace: namespace)
        XCTAssertEqual(SecItemUpdate([
            kSecClass: kSecClassCertificate, kSecValueRef: first.certificate
        ] as CFDictionary, [kSecAttrLabel: "BareRemote fixture alternate \(UUID().uuidString)"] as CFDictionary), errSecSuccess)
        let recovered = try ClientIdentity.loadOrCreate(namespace: namespace)
        XCTAssertEqual(SecCertificateCopyData(first.certificate) as Data,
                       SecCertificateCopyData(recovered.certificate) as Data,
                       "Recovering a certificate after an interrupted label update must retain TV trust")
    }
}
