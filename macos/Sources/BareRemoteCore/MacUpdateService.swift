import Foundation
import CryptoKit
import Security

/// A single, explicitly requested operation. It has no timer or session while idle.
public final class MacUpdateService: @unchecked Sendable {
    private let configuration: URLSessionConfiguration?
    public init() { configuration = nil }
    init(configuration: URLSessionConfiguration) { self.configuration = configuration }
    public func check(installedVersion: Int) async throws -> MacUpdateRelease? {
        let data = try await UpdateHTTPTransfer.fetch(MacUpdatePolicy.metadataURL, download: false,
            expectedSize: nil, maximum: MacUpdatePolicy.maximumMetadataSize, deadline: 25, configuration: configuration)
        try Task.checkCancellation()
        let release = try MacUpdatePolicy.parse(data)
        return release.versionCode > installedVersion ? release : nil
    }
    public func download(_ release: MacUpdateRelease, installedApp: URL) async throws -> PreparedMacUpdate {
        let started = ProcessInfo.processInfo.systemUptime
        let data = try await UpdateHTTPTransfer.fetch(release.zipURL, download: true,
            expectedSize: release.size, maximum: MacUpdatePolicy.maximumArchiveSize, deadline: 90, configuration: configuration)
        let processing = Task.detached(priority: .utility) {
            try Task.checkCancellation()
            guard data.count == release.size else { throw MacUpdateError.wrongSize }
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard actual == release.sha256 else { throw MacUpdateError.wrongChecksum }
            try Task.checkCancellation()
            try MacUpdateArchive.validate(data)
            let directory = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("BareRemote-update-" + UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            var keep = false
            defer { if !keep { try? FileManager.default.removeItem(at: directory) } }
            let zip = directory.appendingPathComponent("release.zip")
            try data.write(to: zip, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: zip.path)
            let extracted = directory.appendingPathComponent("extracted", isDirectory: true)
            try FileManager.default.createDirectory(at: extracted, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            try MacUpdateArchive.extract(data, to: extracted)
            let app = extracted.appendingPathComponent("BareRemote.app", isDirectory: true)
            try MacUpdateVerifier.verify(candidate: app, installed: installedApp, release: release)
            try Task.checkCancellation()
            keep = true
            return PreparedMacUpdate(directory: directory, app: app, release: release)
        }
        let remaining = max(0, 90 - (ProcessInfo.processInfo.systemUptime - started))
        let deadline = DispatchWorkItem { processing.cancel() }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + remaining, execute: deadline)
        defer { deadline.cancel() }
        return try await withTaskCancellationHandler(operation: { try await processing.value }, onCancel: { processing.cancel() })
    }
}

public final class PreparedMacUpdate: @unchecked Sendable {
    public let directory: URL
    public let app: URL
    public let release: MacUpdateRelease
    init(directory: URL, app: URL, release: MacUpdateRelease) { self.directory = directory; self.app = app; self.release = release }
    deinit { try? FileManager.default.removeItem(at: directory) }
}

public enum MacUpdateVerifier {
    public static func installedVersion(at app: URL) throws -> Int { try info(app).version }
    static func info(_ app: URL) throws -> (version: Int, name: String) {
        let url = app.appendingPathComponent("Contents/Info.plist")
        guard let data = try? Data(contentsOf: url), data.count <= 64 * 1024,
              let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              plist["CFBundleIdentifier"] as? String == MacUpdatePolicy.bundleIdentifier,
              plist["CFBundleExecutable"] as? String == "BareRemote",
              let raw = plist["CFBundleVersion"] as? String,
              raw.range(of: "^[1-9][0-9]{0,9}$", options: .regularExpression) != nil,
              let version = Int(raw), let name = plist["CFBundleShortVersionString"] as? String else { throw MacUpdateError.invalidBundle }
        return (version, name)
    }
    public static func verify(candidate: URL, installed: URL, release: MacUpdateRelease) throws {
        try regularTree(candidate)
        let current = try info(installed), next = try info(candidate)
        guard next.version > current.version else { throw MacUpdateError.notNewer }
        guard next.version == release.versionCode, next.name == release.versionName else { throw MacUpdateError.invalidBundle }
        let old = try staticCode(installed), new = try staticCode(candidate)
        let flags = SecCSFlags(rawValue: kSecCSStrictValidate | kSecCSCheckAllArchitectures | kSecCSCheckNestedCode)
        guard SecStaticCodeCheckValidity(old, flags, nil) == errSecSuccess else { throw MacUpdateError.invalidSignature }
        var requirement: SecRequirement?
        guard SecCodeCopyDesignatedRequirement(old, [], &requirement) == errSecSuccess,
              let requirement, SecStaticCodeCheckValidity(new, flags, requirement) == errSecSuccess,
              let oldTeam = try team(old), oldTeam == (try team(new)) else { throw MacUpdateError.invalidSignature }
    }
    static func requirementString(installed: URL) throws -> String {
        let code = try staticCode(installed)
        var requirement: SecRequirement?, text: CFString?
        guard SecCodeCopyDesignatedRequirement(code, [], &requirement) == errSecSuccess,
              let requirement, SecRequirementCopyString(requirement, [], &text) == errSecSuccess,
              let text else { throw MacUpdateError.invalidSignature }
        guard let signingTeam = try team(code), signingTeam.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil else { throw MacUpdateError.invalidSignature }
        return "(\(text as String)) and certificate leaf[subject.OU] = \"\(signingTeam)\""
    }
    private static func staticCode(_ url: URL) throws -> SecStaticCode {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &code) == errSecSuccess, let code else { throw MacUpdateError.invalidSignature }
        return code
    }
    private static func team(_ code: SecStaticCode) throws -> String? {
        var information: CFDictionary?
        guard SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &information) == errSecSuccess,
              let dict = information as? [String: Any] else { throw MacUpdateError.invalidSignature }
        let value = dict[kSecCodeInfoTeamIdentifier as String] as? String
        return value?.isEmpty == false ? value : nil
    }
    static func regularTree(_ root: URL) throws {
        let keys: [URLResourceKey] = [.isSymbolicLinkKey, .isDirectoryKey, .isRegularFileKey]
        guard root.standardizedFileURL == root.resolvingSymlinksInPath(),
              let entries = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys, options: [], errorHandler: { _, _ in false }) else { throw MacUpdateError.invalidArchive }
        var count = 0, total = 0
        for case let entry as URL in entries {
            let values = try entry.resourceValues(forKeys: Set(keys + [.fileSizeKey]))
            count += 1; total += values.fileSize ?? 0
            guard count <= 4096, total <= MacUpdatePolicy.maximumExpandedSize,
                  values.isSymbolicLink != true, values.isDirectory == true || values.isRegularFile == true else { throw MacUpdateError.invalidArchive }
        }
    }
}

final class UpdateHTTPTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let address: URL, download: Bool, expectedSize: Int?, maximum: Int, deadline: TimeInterval
    private let configuration: URLSessionConfiguration?
    private let lock = NSLock()
    private var session: URLSession?, task: URLSessionDataTask?, timer: DispatchWorkItem?
    private var continuation: CheckedContinuation<Data, Error>?
    private var result: Result<Data, Error>?
    private var data = Data(), redirects = 0
    init(_ address: URL, download: Bool, expectedSize: Int?, maximum: Int, deadline: TimeInterval, configuration: URLSessionConfiguration? = nil) {
        self.configuration = configuration
        self.address = address; self.download = download; self.expectedSize = expectedSize; self.maximum = maximum; self.deadline = deadline
    }
    static func fetch(_ url: URL, download: Bool, expectedSize: Int?, maximum: Int, deadline: TimeInterval, configuration: URLSessionConfiguration? = nil) async throws -> Data {
        let transfer = UpdateHTTPTransfer(url, download: download, expectedSize: expectedSize, maximum: maximum, deadline: deadline, configuration: configuration)
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in transfer.start(continuation) }
        }, onCancel: { transfer.finish(.failure(MacUpdateError.stopped)) })
    }
    private func start(_ continuation: CheckedContinuation<Data, Error>) {
        lock.lock()
        if let result { lock.unlock(); continuation.resume(with: result); return }
        self.continuation = continuation
        let config = configuration ?? URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 8; config.timeoutIntervalForResource = deadline
        config.urlCredentialStorage = nil; config.httpCookieStorage = nil; config.urlCache = nil; config.httpShouldSetCookies = false
        let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
        self.session = session
        var request = URLRequest(url: address, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue("BareRemote-Updater", forHTTPHeaderField: "User-Agent")
        let task = session.dataTask(with: request); self.task = task
        let timer = DispatchWorkItem { [weak self] in self?.finish(.failure(MacUpdateError.stopped)) }
        self.timer = timer
        lock.unlock()
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + deadline, execute: timer)
        task.resume()
    }
    private func finish(_ outcome: Result<Data, Error>) {
        lock.lock()
        guard result == nil else { lock.unlock(); return }
        result = outcome
        let continuation = self.continuation, session = self.session, timer = self.timer
        self.continuation = nil; self.session = nil; self.task = nil; self.timer = nil
        lock.unlock()
        timer?.cancel(); session?.invalidateAndCancel(); continuation?.resume(with: outcome)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        redirects += 1
        guard download, redirects <= 4, let url = request.url, MacUpdatePolicy.downloadHost(url) else {
            completionHandler(nil); finish(.failure(MacUpdateError.invalidURL)); return
        }
        var next = request
        next.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        completionHandler(next)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, let url = http.url,
              download ? MacUpdatePolicy.downloadHost(url) : url == MacUpdatePolicy.metadataURL else {
            completionHandler(.cancel); finish(.failure(MacUpdateError.network)); return
        }
        let length = response.expectedContentLength
        guard length <= maximum, expectedSize == nil || length == -1 || length == expectedSize.map(Int64.init) else {
            completionHandler(.cancel); finish(.failure(MacUpdateError.wrongSize)); return
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive bytes: Data) {
        guard data.count <= maximum - bytes.count, expectedSize == nil || data.count + bytes.count <= expectedSize! else {
            finish(.failure(MacUpdateError.tooLarge)); return
        }
        data.append(bytes)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if error != nil { finish(.failure(MacUpdateError.network)) }
        else if let expectedSize, expectedSize != data.count { finish(.failure(MacUpdateError.wrongSize)) }
        else { finish(.success(data)) }
    }
}

/// System tools receive argv directly; no shell interpolation or unbounded subprocess.
enum UpdateProcess {
    static func run(_ executable: String, _ arguments: [String], timeout: TimeInterval) throws {
        let process = Process(); process.executableURL = URL(fileURLWithPath: executable); process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        try process.run()
        let limit = Date().addingTimeInterval(timeout)
        while process.isRunning && Date() < limit && !Task.isCancelled { Thread.sleep(forTimeInterval: 0.02) }
        if process.isRunning { process.terminate(); throw MacUpdateError.stopped }
        guard process.terminationStatus == 0 else { throw MacUpdateError.commandFailed }
    }
}
