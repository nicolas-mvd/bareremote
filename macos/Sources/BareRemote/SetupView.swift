import SwiftUI
import AppKit
import BareRemoteCore

/// Unknown state must never expose Start. Recovery is an explicit user action.
enum DogModeSettingsAction: Equatable {
    case start, end, restore
    init?(state: String) {
        switch state {
        case "off": self = .start
        case "active": self = .end
        case "starting", "restore_required", "unknown", "error",
             "nsnow_access", "nsnow_menu", "nsnow_channel", "nsnow_player", "nsnow_debug",
             "nsnow_target", "nsnow_input": self = .restore
        default: return nil
        }
    }
    var title: String {
        switch self {
        case .start: return "Start Dog Mode"
        case .end: return "End Dog Mode"
        case .restore: return "Restore settings & exit"
        }
    }
}

struct SetupView: View {
    @ObservedObject var model: RemoteModel
    @ObservedObject var discovery: TVDiscovery
    var checkForUpdates: () -> Void = {}
    @FocusState private var codeFocused: Bool
    @State private var connectionOptionsOpen = false
    @State private var advancedOpen = false
    @State private var confirmForget = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Settings").font(.system(size: 23, weight: .semibold))
                    Text("Your TV, Dog Mode and app updates.").font(.callout).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Done") { discovery.stop(); model.closeSetup() }.keyboardShortcut(.cancelAction)
            }.padding(24)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if model.awaitingCode { pairingCode }
                    else { television; dogMode; remoteHelp; updates; advanced }
                }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(width: 470, height: 650).preferredColorScheme(.dark)
        .background(SetupWindowBehavior())
        .onDisappear { discovery.stop(); model.stopHolding() }
        .confirmationDialog("Forget this TV on this Mac?", isPresented: $confirmForget) {
            Button("Forget TV", role: .destructive) { discovery.stop(); model.forget() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You will need a new TV code to pair this Mac again. Other remotes keep their pairing.")
        }
    }

    private var television: some View {
        section("Television", symbol: "tv") {
            VStack(alignment: .leading, spacing: 14) {
                if let saved = model.saved {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "tv").font(.title2).foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(model.displayName).font(.headline)
                            Text(saved.host).font(.system(.callout, design: .monospaced)).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Label("Paired", systemImage: "checkmark.shield").font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    Text("Add your TV").font(.headline)
                    Text("Keep your Mac and TV on the same network. Pair this Mac separately from your phone.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                connectionStatus
                HStack {
                    if discovery.isScanning {
                        ProgressView().controlSize(.small)
                        Button("Stop scan") { discovery.stop() }
                    } else {
                        Button("Scan for TVs") { discovery.start() }.disabled(connectionBusy)
                    }
                    Spacer()
                    if model.saved != nil {
                        Button("Reconnect") { discovery.stop(); model.sync() }
                            .buttonStyle(.borderedProminent).disabled(connectionBusy)
                    }
                }
                if model.saved != nil {
                    Text("If your TV changes its IP address, reconnecting looks for it automatically.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if discovery.isScanning || !discovery.results.isEmpty { nearbyTVs }
                if model.saved == nil { connectionOptions }
                else {
                    DisclosureGroup("Change address or pair a TV", isExpanded: $connectionOptionsOpen) {
                        connectionOptions.padding(.top, 10)
                    }.font(.callout)
                }
            }
        }
    }

    private var connectionStatus: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                if model.busy { ProgressView().controlSize(.small) }
                else if model.connected { Image(systemName: "circle.fill").font(.system(size: 7)).foregroundStyle(.green) }
                Text(model.status).font(.callout).foregroundStyle(.secondary)
            }.accessibilityElement(children: .combine)
            if !model.detail.isEmpty {
                Text(model.detail).font(.callout).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            }
            if model.needsLocalNetworkAccess {
                Button("Open Local Network Settings") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
        }
    }

    private var nearbyTVs: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("Nearby TVs").font(.caption).foregroundStyle(.secondary)
            if discovery.results.isEmpty { Text("Looking for TVs…").font(.callout).foregroundStyle(.secondary) }
            ForEach(discovery.results) { tv in
                Button {
                    model.select(tv); connectionOptionsOpen = true; discovery.stop()
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "tv")
                        VStack(alignment: .leading, spacing: 3) {
                            Text(tv.name).font(.callout).lineLimit(1)
                            Text(tv.host).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if model.host == tv.host { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                    }.padding(9).frame(maxWidth: .infinity, alignment: .leading)
                        .background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 8)).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(connectionBusy)
            }
        }
    }

    private var connectionOptions: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("TV IP address").font(.caption).foregroundStyle(.secondary)
            TextField("192.168.1.10", text: $model.host).textFieldStyle(.roundedBorder)
                .accessibilityLabel("TV IPv4 address").disabled(connectionBusy)
            Text("TV name").font(.caption).foregroundStyle(.secondary)
            TextField("Optional", text: $model.tvName).textFieldStyle(.roundedBorder)
                .accessibilityLabel("TV name, optional").disabled(connectionBusy)
            HStack {
                if model.saved != nil {
                    Button("Pair with a new TV code") { discovery.stop(); model.pair() }.disabled(model.host.isEmpty || connectionBusy)
                    Spacer()
                    Button("Use this address") { discovery.stop(); model.reconnect() }.disabled(model.host.isEmpty || connectionBusy)
                } else {
                    Spacer()
                    Button("Pair with TV") { discovery.stop(); model.pair() }
                        .buttonStyle(.borderedProminent).disabled(model.host.isEmpty || connectionBusy)
                }
            }
            Text(model.saved == nil
                 ? "Your TV must support Android TV Remote Service v2. It will show a six-character code."
                 : "Use this address checks your saved TV’s identity. A different or reset TV needs a new code.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }

    private var dogMode: some View {
        section("Dog Mode", symbol: "pawprint") {
            VStack(alignment: .leading, spacing: 12) {
                Text("NS NOW · CANAL 10").font(.headline)
                Text("Keeps the TV on while you’re out. Ending Dog Mode restores its previous energy settings and opens Home.")
                    .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                Text("One-time setup in BareHome on the TV is required. NS NOW requires USB debugging to be off.")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                HStack(alignment: .top, spacing: 8) {
                    if model.dogBusy { ProgressView().controlSize(.small) }
                    Text(model.dogStatus).font(.callout)
                        .foregroundStyle(dogStatusColor)
                        .fixedSize(horizontal: false, vertical: true)
                }.accessibilityElement(children: .combine)
                if model.saved == nil { Text("Pair your TV above to use Dog Mode.").font(.caption).foregroundStyle(.secondary) }
                if model.saved != nil {
                    Button("Set up on TV") { discovery.stop(); model.openDogSetup() }
                        .disabled(dogUnavailable)
                }
                HStack {
                    Button("Check status") { discovery.stop(); model.refreshDogMode() }.disabled(dogUnavailable)
                    Spacer()
                    if let action = DogModeSettingsAction(state: model.dogMode) {
                        Button(action.title) {
                            discovery.stop()
                            if action == .start { model.startDogMode() }
                            else { model.stopDogMode() }
                        }.buttonStyle(.borderedProminent).disabled(dogUnavailable)
                    }
                }
            }
        }
    }

    private var remoteHelp: some View {
        section("Remote controls", symbol: "slider.horizontal.3") {
            VStack(alignment: .leading, spacing: 9) {
                Text("Day / Night").font(.callout.weight(.medium))
                Text("The sun or moon shows the last TV-confirmed picture preset. A question mark means it is unconfirmed. Tap the mode button to change and confirm the preset. Sync only reconnects. Requires BareHome picture controls.")
                    .font(.caption).foregroundStyle(.secondary)
                Text("Quick access").font(.callout.weight(.medium))
                Text("Use the TV icon in the menu bar or Show Desktop Remote. Open Remote for typing and hold-to-repeat controls.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var updates: some View {
        section("App updates", symbol: "arrow.down.circle") {
            HStack {
                Text("BareRemote \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                    .font(.callout).foregroundStyle(.secondary)
                Spacer()
                Button("Check for Updates…", action: checkForUpdates)
            }
        }
    }

    private var advanced: some View {
        DisclosureGroup("Advanced", isExpanded: $advancedOpen) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Forgetting removes only this Mac’s saved pairing. Other remotes are unaffected.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Forget TV…", role: .destructive) { confirmForget = true }
                    .disabled(model.saved == nil || connectionBusy || model.dogMode == "active" || model.dogMode == "starting" || model.dogMode == "restore_required")
                if model.dogMode == "active" || model.dogMode == "starting" || model.dogMode == "restore_required" {
                    Text("End Dog Mode before forgetting the TV so its energy settings can be restored.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }.padding(.top, 10)
        }.font(.callout)
    }

    private var pairingCode: some View {
        section("Check your TV screen", symbol: "tv") {
            VStack(alignment: .leading, spacing: 14) {
                Text("Enter the six characters shown on the TV you want to control.").font(.callout).foregroundStyle(.secondary)
                TextField("A1B2C3", text: $model.code).font(.system(size: 26, weight: .medium, design: .monospaced))
                    .textFieldStyle(.roundedBorder).focused($codeFocused)
                    .onSubmit { if validCode && !model.busy { model.finishPairing() } }
                    .accessibilityLabel("Six character TV pairing code")
                connectionStatus
                HStack {
                    Button("Cancel pairing") { model.cancelPairing() }
                    Spacer()
                    Button("Verify code") { model.finishPairing() }.buttonStyle(.borderedProminent).disabled(!validCode || model.busy)
                }
            }.onAppear { codeFocused = true }
        }
    }

    private func section<Content: View>(_ title: String, symbol: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: symbol).font(.system(size: 14, weight: .semibold))
            content().padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var connectionBusy: Bool { model.busy || model.dogBusy }
    private var dogUnavailable: Bool { model.saved == nil || model.busy || model.dogBusy }
    private var dogStatusColor: Color {
        if model.dogMode == "active" { return .green }
        if model.dogMode == "restore_required" || model.dogMode == "error" || DogStateWire.isStartupFailure(model.dogMode) { return .orange }
        return .secondary
    }
    private var validCode: Bool {
        let text = model.code.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.count == 6 && text.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }
    }
}

/// Setup has no unsaved document requiring a decision before the app can quit.
private struct SetupWindowBehavior: NSViewRepresentable {
    func makeNSView(context: Context) -> WindowView { WindowView() }
    func updateNSView(_ nsView: WindowView, context: Context) {}
    final class WindowView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.preventsApplicationTerminationWhenModal = false
        }
    }
}
