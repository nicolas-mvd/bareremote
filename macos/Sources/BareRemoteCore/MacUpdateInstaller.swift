import Foundation

/// Installation is an explicit final action. The new bundle is completely prepared
/// on the destination volume before the app quits. This never requests elevation.
public enum MacUpdateInstaller {
    public static func prepare(_ update: PreparedMacUpdate, replacing installed: URL) throws -> MacUpdateInstallPlan {
        try Task.checkCancellation()
        let destination = installed.standardizedFileURL
        let parent = destination.deletingLastPathComponent()
        guard destination.lastPathComponent == "BareRemote.app",
              destination == destination.resolvingSymlinksInPath(),
              parent == parent.resolvingSymlinksInPath(),
              FileManager.default.isWritableFile(atPath: parent.path) else { throw MacUpdateError.notWritable }
        let volume = try parent.resourceValues(forKeys: [.volumeIsReadOnlyKey, .volumeIsLocalKey])
        guard volume.volumeIsReadOnly != true, volume.volumeIsLocal == true else { throw MacUpdateError.notWritable }
        try MacUpdateVerifier.verify(candidate: update.app, installed: destination, release: update.release)
        let previousVersion = try MacUpdateVerifier.installedVersion(at: destination)
        let requirement = try MacUpdateVerifier.requirementString(installed: destination)
        let stage = parent.appendingPathComponent(".BareRemote-update-" + UUID().uuidString, isDirectory: true)
        do { try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
        catch { throw MacUpdateError.notWritable }
        var keep = false
        defer { if !keep { try? FileManager.default.removeItem(at: stage) } }
        let candidate = stage.appendingPathComponent("BareRemote.app", isDirectory: true)
        try FileManager.default.copyItem(at: update.app, to: candidate)
        try Task.checkCancellation()
        // Verify the actual copy that will be renamed, not only the download.
        try MacUpdateVerifier.verify(candidate: candidate, installed: destination, release: update.release)
        let script = stage.appendingPathComponent("install.sh")
        try helper.write(to: script, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: script.path)
        let plan = MacUpdateInstallPlan(directory: stage, candidate: candidate, destination: destination,
            requirement: requirement, previousVersion: previousVersion, release: update.release)
        keep = true
        return plan
    }

    // Every variable comes from an argv element; no interpolated shell source.
    // The helper waits for this exact parent, revalidates both versions and the
    // designated signing requirement after exit, then uses same-volume renames.
    private static let helper = #"""
    #!/bin/sh
    set -eu
    stage=$1
    candidate=$2
    destination=$3
    parent_pid=$4
    requirement=$5
    old_version=$6
    new_version=$7
    bundle_id=$8
    backup="$stage/previous.app"
    moved_old=0
    installed_new=0
    complete=0
    recover() {
        status=$?
        trap - EXIT HUP INT TERM
        if test "$complete" -eq 0; then
            if test "$installed_new" -eq 1 && test -e "$destination"; then
                /bin/mv "$destination" "$stage/failed.app" || true
            fi
            if test "$moved_old" -eq 1 && ! test -e "$destination"; then
                /bin/mv "$backup" "$destination" || true
            fi
            printf '%s\n' 'Update failed; the previous app was retained.' > "$stage/result.txt"
            /usr/bin/open -g "$destination" --args --bareremote-update-failed || true
        fi
        exit "$status"
    }
    trap recover EXIT
    trap 'exit 1' HUP INT TERM
    attempts=0
    while /bin/kill -0 "$parent_pid" 2>/dev/null; do
        attempts=$((attempts + 1))
        test "$attempts" -lt 300 || exit 1
        /bin/sleep 0.1
    done
    test ! -L "$stage" && test ! -L "$candidate" && test ! -L "$destination"
    test -d "$candidate" && test -d "$destination"
    test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$candidate/Contents/Info.plist")" = "$bundle_id"
    test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$destination/Contents/Info.plist")" = "$bundle_id"
    test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$destination/Contents/Info.plist")" = "$old_version"
    test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$candidate/Contents/Info.plist")" = "$new_version"
    /usr/bin/codesign --verify --deep --strict --all-architectures -R "=$requirement" "$destination"
    /usr/bin/codesign --verify --deep --strict --all-architectures -R "=$requirement" "$candidate"
    /bin/mv "$destination" "$backup"
    moved_old=1
    /bin/mv "$candidate" "$destination"
    installed_new=1
    /usr/bin/open "$destination"
    complete=1
    /bin/rm -rf "$stage"
    """#
}

public final class MacUpdateInstallPlan: @unchecked Sendable {
    public let directory: URL
    private let candidate: URL, destination: URL, requirement: String, previousVersion: Int, release: MacUpdateRelease
    private var handedOff = false
    init(directory: URL, candidate: URL, destination: URL, requirement: String, previousVersion: Int, release: MacUpdateRelease) {
        self.directory = directory; self.candidate = candidate; self.destination = destination
        self.requirement = requirement; self.previousVersion = previousVersion; self.release = release
    }
    /// Call on the main thread immediately before NSApp.terminate(nil).
    public func launch(parentProcessIdentifier: Int32 = ProcessInfo.processInfo.processIdentifier) throws {
        guard !handedOff else { throw MacUpdateError.installFailed }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = [directory.appendingPathComponent("install.sh").path, directory.path,
            candidate.path, destination.path, String(parentProcessIdentifier), requirement,
            String(previousVersion), String(release.versionCode), MacUpdatePolicy.bundleIdentifier]
        let log = directory.appendingPathComponent("install.log")
        FileManager.default.createFile(atPath: log.path, contents: nil, attributes: [.posixPermissions: 0o600])
        let output = try FileHandle(forWritingTo: log)
        process.standardOutput = output; process.standardError = output
        try process.run()
        try? output.close()
        handedOff = true
    }
    deinit { if !handedOff { try? FileManager.default.removeItem(at: directory) } }
}
