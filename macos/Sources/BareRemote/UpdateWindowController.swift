import AppKit
import SwiftUI
import BareRemoteCore

/// This controller does nothing on creation. Every check and download requires a click.
@MainActor final class UpdateWindowController: NSObject, NSWindowDelegate {
    private let model: MacUpdateModel
    private var window: NSWindow?
    init(installedBundle: Bundle = .main) {
        model = MacUpdateModel(installedBundle: installedBundle)
        super.init()
    }
    func show() {
        if window == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 440, height: 350),
                styleMask: [.titled, .closable], backing: .buffered, defer: false)
            window.title = "BareRemote Updates"
            window.isReleasedWhenClosed = false
            window.contentView = NSHostingView(rootView: MacUpdateView(model: model))
            window.delegate = self
            window.center()
            self.window = window
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
    func showInstallFailure() {
        model.reportInstallFailure()
        show()
    }
    func windowWillClose(_ notification: Notification) { model.cancel() }
}

@MainActor private final class MacUpdateModel: ObservableObject {
    enum State { case idle, checking, available, downloading, ready, installing, current, failed }
    @Published var state: State = .idle
    @Published var message = "Updates are checked only when you choose Check for Updates."
    @Published var release: MacUpdateRelease?
    let installedName: String
    private let installedApp: URL
    private let service = MacUpdateService()
    private var operation: Task<Void, Never>?
    private var generation = UUID()
    private var prepared: PreparedMacUpdate?
    var busy: Bool { [.checking, .downloading, .installing].contains(state) }
    init(installedBundle: Bundle) {
        installedApp = installedBundle.bundleURL
        installedName = installedBundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }
    func check() {
        cancel(); let id = generation
        release = nil; prepared = nil; state = .checking; message = "Checking for updates…"
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                let version = try MacUpdateVerifier.installedVersion(at: installedApp)
                let found = try await service.check(installedVersion: version)
                guard generation == id, !Task.isCancelled else { return }
                release = found
                if let found {
                    state = .available; message = "BareRemote \(found.versionName) is available (\(ByteCountFormatter.string(fromByteCount: Int64(found.size), countStyle: .file)))."
                } else { state = .current; message = "BareRemote is up to date." }
            } catch { fail(error, id: id) }
        }
    }
    func download() {
        guard let release else { return }
        cancel(); let id = generation
        state = .downloading; message = "Downloading and verifying BareRemote \(release.versionName)…"
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                let update = try await service.download(release, installedApp: installedApp)
                guard generation == id, !Task.isCancelled else { return }
                prepared = update; state = .ready
                message = "Download verified. Install & Reopen will quit BareRemote, replace the app, and reopen it. Your paired TV is retained."
            } catch { fail(error, id: id) }
        }
    }
    func install() {
        guard let prepared else { return }
        cancel(clearPrepared: false); let id = generation
        state = .installing; message = "Preparing the verified app for installation…"
        let installed = installedApp
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                let preparing = Task.detached(priority: .utility) { try MacUpdateInstaller.prepare(prepared, replacing: installed) }
                let plan = try await withTaskCancellationHandler(operation: { try await preparing.value }, onCancel: { preparing.cancel() })
                guard generation == id, !Task.isCancelled else { return }
                try plan.launch()
                NSApp.terminate(nil)
            } catch { fail(error, id: id) }
        }
    }
    func cancel(clearPrepared: Bool = true) {
        generation = UUID(); operation?.cancel(); operation = nil
        if clearPrepared { prepared = nil }
        if busy || (clearPrepared && state == .ready) { state = release == nil ? .idle : .available; message = "Update cancelled. Nothing was installed." }
    }
    func reportInstallFailure() {
        cancel(); release = nil; prepared = nil; state = .failed
        message = "BareRemote could not install the update. Your previous app was retained. Check for updates to try again."
    }
    private func fail(_ error: Error, id: UUID) {
        guard generation == id, !Task.isCancelled else { return }
        state = .failed; message = (error as? MacUpdateError)?.localizedDescription ?? "The update could not be completed. Nothing was installed. Try again."
    }
}

private struct MacUpdateView: View {
    @ObservedObject var model: MacUpdateModel
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "arrow.down.app").font(.system(size: 34)).foregroundStyle(.green)
                VStack(alignment: .leading, spacing: 4) {
                    Text("BareRemote Updates").font(.title2.weight(.semibold))
                    Text("Installed version \(model.installedName)").font(.callout).foregroundStyle(.secondary)
                }
            }
            if model.busy { ProgressView().controlSize(.small).accessibilityLabel(model.message) }
            Text(model.message).font(.body).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                .foregroundStyle(model.state == .failed ? Color.orange : Color.primary)
            Spacer(minLength: 0)
            Text("Manual updates from BareRemote’s GitHub releases. Downloads are checked against this app’s signing identity before installation.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            HStack {
                if model.busy { Button("Cancel") { model.cancel() }.keyboardShortcut(.cancelAction) }
                Spacer()
                if model.state == .ready {
                    Button("Install & Reopen") { model.install() }.buttonStyle(.borderedProminent)
                } else if model.state == .available || (model.state == .failed && model.release != nil) {
                    Button("Check Again") { model.check() }
                    Button("Download") { model.download() }.buttonStyle(.borderedProminent)
                } else if !model.busy {
                    Button("Check for Updates") { model.check() }.buttonStyle(.borderedProminent)
                }
            }
        }.padding(26).frame(width: 440, height: 350).preferredColorScheme(.dark)
    }
}
