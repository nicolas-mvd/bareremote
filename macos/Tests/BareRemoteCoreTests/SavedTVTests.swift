import Foundation
import Security
import XCTest
@testable import BareRemoteCore

final class SavedTVTests: XCTestCase {
    func testPersistenceValidatesBeforeReplacingTrustAndForgetRetainsIdentity() throws {
        let namespace = "uy.nico.bareremote.tests.\(UUID().uuidString)"
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {
            try? ClientIdentity.delete(namespace: namespace)
            try? FileManager.default.removeItem(at: directory)
        }
        let identity = try ClientIdentity.loadOrCreate(namespace: namespace)
        let pin = SecCertificateCopyData(identity.certificate) as Data
        let path = directory.appendingPathComponent("tv.json")
        XCTAssertNil(try SavedTV.load(from: path))
        let saved = SavedTV(host: "192.168.1.40", name: "Living room", certificate: pin)
        try saved.save(to: path)
        XCTAssertEqual(try SavedTV.load(from: path), saved)
        let attributes = try FileManager.default.attributesOfItem(atPath: path.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        for host in ["tv.local", "127.0.0.1:6466", "192.168.1.999", "192.168.01.1", " 192.168.1.40"] {
            XCTAssertThrowsError(try SavedTV(host: host, name: "TV", certificate: pin).save(to: path))
            XCTAssertEqual(try SavedTV.load(from: path), saved, "Invalid host must retain saved trust")
        }
        XCTAssertThrowsError(try SavedTV(host: saved.host, name: "TV\nInjected", certificate: pin).save(to: path))
        XCTAssertThrowsError(try SavedTV(host: saved.host, name: "TV", certificate: Data([1, 2, 3])).save(to: path))
        XCTAssertEqual(try SavedTV.load(from: path), saved)
        try SavedTV.forget(at: path)
        try SavedTV.forget(at: path)
        XCTAssertNil(try SavedTV.load(from: path))
        let reloaded = try ClientIdentity.loadOrCreate(namespace: namespace)
        XCTAssertEqual(SecCertificateCopyData(reloaded.certificate) as Data, pin)
    }

    func testCorruptOrOversizedStorageFailsClosed() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: path) }
        for malformed in [Data("{}".utf8), Data("not JSON".utf8), Data(repeating: 0x20, count: 128 * 1024 + 1)] {
            try malformed.write(to: path)
            XCTAssertThrowsError(try SavedTV.load(from: path), "Corrupt trust must require a deliberate user repair")
        }
    }
}
