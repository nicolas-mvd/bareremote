import SwiftUI
import AppKit

struct SetupView: View {
    @ObservedObject var model: RemoteModel
    @ObservedObject var discovery: TVDiscovery
    var checkForUpdates: () -> Void = {}
    @FocusState private var codeFocused: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Your television").font(.system(size: 22, weight: .semibold))
                    Text("Keep your Mac and TV on the same network.").font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Done") { discovery.stop(); model.closeSetup() }.keyboardShortcut(.cancelAction)
            }
            if model.awaitingCode {
                VStack(alignment: .leading, spacing: 14) {
                    Label("Check your TV screen", systemImage: "tv").font(.headline)
                    Text("Enter the six characters shown on the TV you want to control.").font(.callout).foregroundStyle(.secondary)
                    TextField("A1B2C3", text: $model.code).font(.system(size: 26, weight: .medium, design: .monospaced))
                        .textFieldStyle(.roundedBorder).focused($codeFocused)
                        .onSubmit { if validCode && !model.busy { model.finishPairing() } }
                        .accessibilityLabel("Six character TV pairing code")
                    HStack {
                        Button("Cancel pairing") { model.cancelPairing() }
                        Spacer()
                        Button("Verify code") { model.finishPairing() }.buttonStyle(.borderedProminent).disabled(!validCode || model.busy)
                    }
                }.padding(18).background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 14))
                    .onAppear { codeFocused = true }
            } else {
                HStack {
                    Text("NEARBY TVS").font(.system(size: 10, weight: .semibold)).tracking(1.2).foregroundStyle(.secondary)
                    Spacer()
                    if discovery.isScanning { ProgressView().controlSize(.small); Button("Stop") { discovery.stop() } }
                    else { Button("Scan for TVs") { discovery.start() }.disabled(model.busy) }
                }
                ScrollView {
                    VStack(spacing: 1) {
                        if discovery.results.isEmpty {
                            VStack(spacing: 7) {
                                Image(systemName: "tv").font(.system(size: 25)).foregroundStyle(.secondary)
                                Text(discovery.isScanning ? "Looking for TVs…" : "Scan, or enter an IP address below.").font(.system(size: 12)).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, minHeight: 92)
                        }
                        ForEach(discovery.results) { tv in
                            Button { model.select(tv); discovery.stop() } label: {
                                HStack {
                                    Image(systemName: "tv").frame(width: 30)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(tv.name).font(.system(size: 13, weight: .medium)).lineLimit(1)
                                        Text(tv.host).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if model.host == tv.host { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                }.padding(10).contentShape(Rectangle())
                            }.buttonStyle(.plain).disabled(model.busy)
                        }
                    }
                }.frame(height: 108).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 10) {
                    TextField("TV IPv4 address", text: $model.host).textFieldStyle(.roundedBorder)
                        .accessibilityLabel("TV IPv4 address").disabled(model.busy)
                    TextField("TV name (optional)", text: $model.tvName).textFieldStyle(.roundedBorder).disabled(model.busy)
                }
                if model.saved != nil {
                    HStack {
                        Button("Pair with a TV code") { discovery.stop(); model.pair() }.disabled(model.host.isEmpty || model.busy)
                        Spacer()
                        Button("Reconnect") { discovery.stop(); model.reconnect() }.buttonStyle(.borderedProminent).disabled(model.host.isEmpty || model.busy)
                    }
                    Text("Reconnect checks your saved TV certificate before saving an address. A different or reset TV needs a new code.")
                        .font(.system(size: 11)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                } else {
                    Button("Pair with TV") { discovery.stop(); model.pair() }.buttonStyle(.borderedProminent)
                        .disabled(model.host.isEmpty || model.busy).frame(maxWidth: .infinity, alignment: .trailing)
                    Text("Your TV needs Android TV Remote Service v2. Pair this Mac separately from your phone.")
                        .font(.system(size: 11)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack(spacing: 8) {
                if model.busy { ProgressView().controlSize(.small) }
                Text(model.status).font(.system(size: 12)).foregroundStyle(.secondary)
            }
            if !model.detail.isEmpty { Text(model.detail).font(.system(size: 12)).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true).textSelection(.enabled) }
            if model.needsLocalNetworkAccess {
                Button("Open Local Network Settings") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
            Spacer(minLength: 0)
            HStack {
                Text("BareRemote for macOS · \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")").font(.system(size: 10)).foregroundStyle(.tertiary)
                Spacer()
                if model.saved != nil { Button("Forget TV") { discovery.stop(); model.forget() }.font(.system(size: 11)).disabled(model.busy) }
            }
            Button("Check for Updates…", action: checkForUpdates).font(.system(size: 11))
        }.padding(26).frame(width: 440, height: 540).preferredColorScheme(.dark)
            .background(SetupWindowBehavior())
            .onDisappear { discovery.stop(); model.stopHolding() }
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
