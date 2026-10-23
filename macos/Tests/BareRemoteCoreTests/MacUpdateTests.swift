import XCTest
import Foundation
import CryptoKit
@testable import BareRemoteCore

final class MacUpdateTests: XCTestCase {
    private let asset = "https://github.com/nicolas-mvd/bareremote/releases/download/macos-v0.3.0/BareRemote-macOS.zip"
    private var json: String { """
        {"versionCode":11,"versionName":"0.3.0","zipUrl":"\(asset)","sha256":"\(String(repeating: "a", count: 64))","size":1234}
        """ }
    func testStrictMetadataAndVersionBounds() throws {
        let release = try MacUpdatePolicy.parse(Data(json.utf8))
        XCTAssertEqual(release.versionCode, 11); XCTAssertEqual(release.size, 1234)
        let invalid = [json + "{}", json.replacingOccurrences(of: "\"size\":1234", with: "\"size\":1234,\"size\":1234"),
            json.replacingOccurrences(of: "\"size\":1234", with: "\"unknown\":1234"),
            json.replacingOccurrences(of: ":11,", with: ":011,"), json.replacingOccurrences(of: ":11,", with: ":0,"),
            json.replacingOccurrences(of: ":11,", with: ":2147483648,"), json.replacingOccurrences(of: ":11,", with: ":11.0,"),
            json.replacingOccurrences(of: ":1234", with: ":33554433"), json.replacingOccurrences(of: ":1234", with: ":-1"),
            json.replacingOccurrences(of: "0.3.0", with: "0.3.0\\n"), json.replacingOccurrences(of: String(repeating: "a", count: 64), with: String(repeating: "A", count: 64))]
        for value in invalid { XCTAssertThrowsError(try MacUpdatePolicy.parse(Data(value.utf8)), value) }
        XCTAssertThrowsError(try MacUpdatePolicy.parse(Data(repeating: 32, count: 16385)))
        XCTAssertThrowsError(try MacUpdatePolicy.parse(Data([255, 254])))
    }
    func testOnlyExactRepositoryAssetsAndSecureRedirectHosts() {
        XCTAssertTrue(MacUpdatePolicy.officialAsset(URL(string: asset)!))
        for value in [asset.replacingOccurrences(of: "https:", with: "http:"), asset + "?x=1", asset + "#x",
            asset.replacingOccurrences(of: "github.com", with: "evil@github.com"),
            asset.replacingOccurrences(of: "github.com", with: "github.com:443"),
            asset.replacingOccurrences(of: "nicolas-mvd", with: "other"),
            asset.replacingOccurrences(of: "BareRemote-macOS.zip", with: "other.zip"),
            asset.replacingOccurrences(of: "macos-v0.3.0", with: "%2e%2e"),
            asset.replacingOccurrences(of: "github.com", with: "github.com.evil.example")] {
            XCTAssertFalse(MacUpdatePolicy.officialAsset(URL(string: value)!), value)
        }
        for host in ["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"] {
            XCTAssertTrue(MacUpdatePolicy.downloadHost(URL(string: "https://\(host)/asset?token=abc")!))
        }
        for value in ["https://evil.example/a", "http://github.com/a", "https://github.com:443/a", "https://user@github.com/a", "https://github.com/a#fragment"] {
            XCTAssertFalse(MacUpdatePolicy.downloadHost(URL(string: value)!))
        }
    }
    func testZIPPreflightRejectsTraversalLinksAliasesOverlapsAndBombs() throws {
        let base = [ZipEntry("BareRemote.app/Contents/Info.plist"), ZipEntry("BareRemote.app/Contents/MacOS/BareRemote")]
        XCTAssertNoThrow(try MacUpdateArchive.validate(zip(base)))
        for name in ["../escape", "/tmp/escape", "BareRemote.app/../escape", "BareRemote.app/a\\b", "BareRemote.app/a:b", "BareRemote.app//x", "other.app/file"] {
            XCTAssertThrowsError(try MacUpdateArchive.validate(zip(base + [ZipEntry(name)])), name)
        }
        XCTAssertThrowsError(try MacUpdateArchive.validate(zip(base + [ZipEntry(base[0].name.uppercased())])))
        XCTAssertThrowsError(try MacUpdateArchive.validate(zip(base + [ZipEntry("BareRemote.app/link", mode: 0xa1ff)])))
        XCTAssertThrowsError(try MacUpdateArchive.validate(zip(base + [ZipEntry("BareRemote.app/bomb", expanded: 128 * 1024 * 1024)])))
        var mismatched = zip(base); mismatched[30] = 90
        XCTAssertThrowsError(try MacUpdateArchive.validate(mismatched))
        var truncated = zip(base); truncated.removeLast()
        XCTAssertThrowsError(try MacUpdateArchive.validate(truncated))
    }
    func testDeflateDecoderRejectsFalseExpandedSizeWithoutWritingBomb() throws {
        let payload = Data(base64Encoded: "7cExAQAAAMKgbOtfyhoeQAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACvBg==")!
        let archive = zip([ZipEntry("BareRemote.app/Contents/Info.plist"), ZipEntry("BareRemote.app/Contents/MacOS/BareRemote"),
            ZipEntry("BareRemote.app/bomb", expanded: 1, payload: payload, method: 8, crc: 3554254475)])
        XCTAssertNoThrow(try MacUpdateArchive.validate(archive)) // Headers falsely claim one byte.
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("BareRemote-bomb-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        XCTAssertThrowsError(try MacUpdateArchive.extract(archive, to: root))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("BareRemote.app/bomb").path))
    }
    func testActualBuiltZIPHasAcceptedLayoutWhenAvailable() throws {
        let source = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let path = source.appendingPathComponent("build/macos/BareRemote-macOS.zip")
        guard FileManager.default.fileExists(atPath: path.path) else { throw XCTSkip("Build the signed macOS archive first") }
        let archive = try Data(contentsOf: path)
        XCTAssertNoThrow(try MacUpdateArchive.validate(archive))
        let extracted = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("BareRemote-extract-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: extracted, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: extracted) }
        XCTAssertNoThrow(try MacUpdateArchive.extract(archive, to: extracted))
        XCTAssertTrue(FileManager.default.fileExists(atPath: extracted.appendingPathComponent("BareRemote.app/Contents/MacOS/BareRemote").path))
    }
    func testDownloadBoundsCancellationAndTotalDeadline() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [UpdateStubProtocol.self]
        let address = URL(string: asset)!
        let data = try await UpdateHTTPTransfer.fetch(address, download: true, expectedSize: 4, maximum: 10, deadline: 1, configuration: config)
        XCTAssertEqual(data, Data([1, 2, 3, 4]))
        do {
            _ = try await UpdateHTTPTransfer.fetch(address, download: true, expectedSize: 2, maximum: 2, deadline: 1, configuration: config)
            XCTFail("Oversize response accepted")
        } catch { XCTAssertTrue(error is MacUpdateError) }
        let hanging = URL(string: "https://github.com/hang")!
        let started = Date()
        do { _ = try await UpdateHTTPTransfer.fetch(hanging, download: true, expectedSize: nil, maximum: 10, deadline: 0.1, configuration: config); XCTFail("No deadline") }
        catch { XCTAssertEqual(error as? MacUpdateError, .stopped) }
        XCTAssertLessThan(Date().timeIntervalSince(started), 1)
        let task = Task { try await UpdateHTTPTransfer.fetch(hanging, download: true, expectedSize: nil, maximum: 10, deadline: 5, configuration: config) }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancellation ignored") }
        catch { XCTAssertEqual(error as? MacUpdateError, .stopped) }
    }
    func testSignedCandidateTrustVersionTamperAndStaging() throws {
        guard let identity = ProcessInfo.processInfo.environment["BAREREMOTE_UPDATE_SIGN_IDENTITY"], !identity.isEmpty else {
            throw XCTSkip("Set BAREREMOTE_UPDATE_SIGN_IDENTITY to exercise an Apple-signed local fixture")
        }
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("BareRemote-update-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let installedRoot = root.appendingPathComponent("installed"), candidateRoot = root.appendingPathComponent("candidate")
        try FileManager.default.createDirectory(at: installedRoot, withIntermediateDirectories: false)
        try FileManager.default.createDirectory(at: candidateRoot, withIntermediateDirectories: false)
        let installed = try fixture(installedRoot, version: 1, identity: identity)
        let candidate = try fixture(candidateRoot, version: 11, identity: identity)
        let release = try MacUpdatePolicy.parse(Data(json.utf8))
        XCTAssertNoThrow(try MacUpdateVerifier.verify(candidate: candidate, installed: installed, release: release))
        XCTAssertThrowsError(try MacUpdateVerifier.verify(candidate: installed, installed: candidate, release: release))
        let prepared = PreparedMacUpdate(directory: candidateRoot, app: candidate, release: release)
        let plan = try MacUpdateInstaller.prepare(prepared, replacing: installed)
        XCTAssertTrue(FileManager.default.fileExists(atPath: plan.directory.appendingPathComponent("BareRemote.app/Contents/MacOS/BareRemote").path))
        // Staging must leave the existing app and its version intact.
        XCTAssertEqual(try MacUpdateVerifier.installedVersion(at: installed), 1)
        try Data("tampered".utf8).write(to: candidate.appendingPathComponent("Contents/MacOS/BareRemote"))
        XCTAssertThrowsError(try MacUpdateVerifier.verify(candidate: candidate, installed: installed, release: release))
        let adHoc = try fixture(root.appendingPathComponent("adhoc"), version: 11, identity: "-")
        XCTAssertThrowsError(try MacUpdateVerifier.verify(candidate: adHoc, installed: installed, release: release))
    }
    func testPostQuitHelperReplacesAndRollsBackInIsolatedFixtures() throws {
        guard let identity = ProcessInfo.processInfo.environment["BAREREMOTE_UPDATE_SIGN_IDENTITY"], !identity.isEmpty else {
            throw XCTSkip("Set BAREREMOTE_UPDATE_SIGN_IDENTITY to exercise the installation helper")
        }
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("BareRemote-helper-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let release = try MacUpdatePolicy.parse(Data(json.utf8))
        for rollback in [false, true] {
            let area = root.appendingPathComponent(rollback ? "rollback" : "install")
            let current = try fixture(area.appendingPathComponent("installed"), version: 1, identity: identity)
            let candidateDirectory = area.appendingPathComponent("candidate")
            let next = try fixture(candidateDirectory, version: 11, identity: identity)
            let prepared = PreparedMacUpdate(directory: candidateDirectory, app: next, release: release)
            let plan = try MacUpdateInstaller.prepare(prepared, replacing: current)
            let scriptURL = plan.directory.appendingPathComponent("install.sh")
            // Exercise the production wait/verify/rename/rollback code. Stub only
            // app launching so tests cannot open a duplicate registered app.
            var script = try String(contentsOf: scriptURL).replacingOccurrences(of: "/usr/bin/open", with: "/usr/bin/true")
            if rollback { script = script.replacingOccurrences(of: #"/bin/mv "$candidate" "$destination""#, with: "/usr/bin/false") }
            try script.write(to: scriptURL, atomically: true, encoding: .utf8)
            try plan.launch(parentProcessIdentifier: Int32.max)
            let until = Date().addingTimeInterval(5)
            let result = plan.directory.appendingPathComponent("result.txt")
            while Date() < until {
                if rollback ? FileManager.default.fileExists(atPath: result.path) : !FileManager.default.fileExists(atPath: plan.directory.path) { break }
                Thread.sleep(forTimeInterval: 0.02)
            }
            XCTAssertEqual(try MacUpdateVerifier.installedVersion(at: current), rollback ? 1 : 11, (try? String(contentsOf: plan.directory.appendingPathComponent("install.log"))) ?? "no helper log")
            XCTAssertEqual(FileManager.default.fileExists(atPath: result.path), rollback)
        }
    }
    func testCompleteDownloadVerificationPipelineWithSignedArchive() async throws {
        guard let identity = ProcessInfo.processInfo.environment["BAREREMOTE_UPDATE_SIGN_IDENTITY"], !identity.isEmpty else {
            throw XCTSkip("Set BAREREMOTE_UPDATE_SIGN_IDENTITY to exercise the full signed download pipeline")
        }
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("BareRemote-pipeline-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let installed = try fixture(root.appendingPathComponent("installed"), version: 1, identity: identity)
        let candidate = try fixture(root.appendingPathComponent("candidate"), version: 11, identity: identity)
        let archive = root.appendingPathComponent("fixture.zip")
        try UpdateProcess.run("/usr/bin/ditto", ["-c", "-k", "--sequesterRsrc", "--keepParent", candidate.path, archive.path], timeout: 10)
        let bytes = try Data(contentsOf: archive)
        PipelineStubProtocol.setPayload(bytes)
        let release = try MacUpdateRelease(versionCode: 11, versionName: "0.3.0", zipURL: URL(string: asset)!,
            sha256: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), size: bytes.count)
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [PipelineStubProtocol.self]
        let service = MacUpdateService(configuration: configuration)
        let prepared = try await service.download(release, installedApp: installed)
        XCTAssertEqual(try MacUpdateVerifier.installedVersion(at: prepared.app), 11)
        XCTAssertNoThrow(try MacUpdateVerifier.verify(candidate: prepared.app, installed: installed, release: release))
        let wrongHash = try MacUpdateRelease(versionCode: 11, versionName: "0.3.0", zipURL: URL(string: asset)!, sha256: String(repeating: "0", count: 64), size: bytes.count)
        do { _ = try await service.download(wrongHash, installedApp: installed); XCTFail("Wrong checksum accepted") }
        catch { XCTAssertEqual(error as? MacUpdateError, .wrongChecksum) }
        XCTAssertEqual(try MacUpdateVerifier.installedVersion(at: installed), 1)
    }
    private func fixture(_ parent: URL, version: Int, identity: String) throws -> URL {
        let app = parent.appendingPathComponent("BareRemote.app")
        let macos = app.appendingPathComponent("Contents/MacOS")
        try FileManager.default.createDirectory(at: macos, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: URL(fileURLWithPath: "/usr/bin/true"), to: macos.appendingPathComponent("BareRemote"))
        let info = ["CFBundleIdentifier": MacUpdatePolicy.bundleIdentifier, "CFBundleExecutable": "BareRemote", "CFBundlePackageType": "APPL", "CFBundleVersion": String(version), "CFBundleShortVersionString": "0.3.0"]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0).write(to: app.appendingPathComponent("Contents/Info.plist"))
        try UpdateProcess.run("/usr/bin/codesign", ["--force", "--sign", identity, app.path], timeout: 10)
        return app
    }
}

private struct ZipEntry {
    let name: String, mode: Int, expanded: Int, payload: Data, method: Int, crc: Int
    init(_ name: String, mode: Int = 0x81a4, expanded: Int = 1, payload: Data = Data([42]), method: Int = 0, crc: Int = 163128923) {
        self.name = name; self.mode = mode; self.expanded = expanded; self.payload = payload; self.method = method; self.crc = crc
    }
}
private func zip(_ entries: [ZipEntry]) -> Data {
    var data = Data(), central = Data()
    func n(_ value: Int, _ bytes: Int) -> Data { Data((0..<bytes).map { UInt8((value >> ($0 * 8)) & 255) }) }
    for entry in entries {
        let name = Data(entry.name.utf8), offset = data.count
        data += n(0x04034b50, 4) + n(20, 2) + n(0, 2) + n(entry.method, 2) + n(0, 4) + n(entry.crc, 4)
        data += n(entry.payload.count, 4) + n(entry.expanded, 4) + n(name.count, 2) + n(0, 2) + name + entry.payload
        central += n(0x02014b50, 4) + n(0x0314, 2) + n(20, 2) + n(0, 2) + n(entry.method, 2) + n(0, 4) + n(entry.crc, 4)
        central += n(entry.payload.count, 4) + n(entry.expanded, 4) + n(name.count, 2) + n(0, 2) + n(0, 2) + n(0, 2) + n(0, 2) + n(entry.mode << 16, 4) + n(offset, 4) + name
    }
    let offset = data.count; data += central
    data += n(0x06054b50, 4) + n(0, 2) + n(0, 2) + n(entries.count, 2) + n(entries.count, 2) + n(central.count, 4) + n(offset, 4) + n(0, 2)
    return data
}
private final class UpdateStubProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url!.path == "/hang" { return }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "4"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data([1, 2, 3, 4]))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private final class PipelineStubProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var payload = Data()
    static func setPayload(_ data: Data) { lock.lock(); payload = data; lock.unlock() }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let bytes = Self.payload; Self.lock.unlock()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": String(bytes.count)])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: bytes)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
