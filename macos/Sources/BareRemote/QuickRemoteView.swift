import SwiftUI

/// A fixed viewport keeps status updates from moving the menu bar popover.
/// The remote stays top-aligned and scrolls when the display is too short.
struct QuickRemoteViewport: View {
    let content: QuickRemoteView
    let size: CGSize

    var body: some View {
        ScrollView(.vertical) {
            content.fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .top)
        }
        .frame(width: size.width, height: size.height, alignment: .top)
        .background(QuickRemotePalette.ink)
        .preferredColorScheme(.dark)
    }
}

/// A small remote for the menu bar. It shares the application's paired session,
/// and leaves connection ownership to the surface that presents it.
struct QuickRemoteView: View {
    @ObservedObject var model: RemoteModel
    let openRemote: () -> Void
    let openDesktop: () -> Void
    let openSetup: () -> Void
    let quit: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            if model.keyboardMode {
                HStack {
                    Text(model.displayName).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                    Spacer()
                    Button { model.showRemoteMode() } label: {
                        Label("Remote", systemImage: "circle.grid.cross")
                    }.buttonStyle(.bordered).accessibilityLabel("Back to remote mode")
                }
                TVKeyboardView(model: model, compact: true)
            } else {
                QuickRemoteControls(model: model, openSetup: openSetup)
            }

            Divider().overlay(QuickRemotePalette.paper.opacity(0.06))

            VStack(spacing: 2) {
                surfaceAction("Open Remote", symbol: "arrow.up.forward.app", action: openRemote)
                surfaceAction("Show Desktop Remote", symbol: "square.grid.2x2", action: openDesktop)
                surfaceAction("TV Setup…", symbol: "slider.horizontal.3", action: openSetup)
                surfaceAction("Quit BareRemote", symbol: "power", action: quit)
            }
        }
        .padding(16)
        .frame(width: 300)
        .background(QuickRemotePalette.ink)
        .foregroundStyle(QuickRemotePalette.paper)
        .preferredColorScheme(.dark)
    }

    private func surfaceAction(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol).frame(width: 18)
                    .foregroundStyle(QuickRemotePalette.paper.opacity(0.65))
                Text(title)
                Spacer(minLength: 0)
            }
            .font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 9).frame(height: 28)
        }
        .buttonStyle(QuickRemoteButtonStyle(fill: .clear, cornerRadius: 7))
        .accessibilityLabel(title)
    }
}

/// Reusable controls without popover, window, or network lifecycle behavior.
struct QuickRemoteControls: View {
    @ObservedObject var model: RemoteModel
    let openSetup: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("BARE\u{2009}REMOTE")
                        .font(.system(size: 11, weight: .bold, design: .rounded)).tracking(1.8)
                    Text(model.saved == nil ? "Add your television" : model.displayName)
                        .font(.system(size: 11)).foregroundStyle(QuickRemotePalette.paper.opacity(0.55))
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Button { if model.saved == nil { openSetup() } else { model.showKeyboardMode() } } label: {
                    Image(systemName: "keyboard").font(.system(size: 16)).frame(width: 32, height: 36)
                }.buttonStyle(.plain).accessibilityLabel("Switch to keyboard mode").help("Type on TV")
                key("power", "Power", code: 26, width: 36, height: 36)
            }

            HStack(spacing: 1) {
                Button {
                    if model.saved == nil { openSetup() } else { model.sync() }
                } label: {
                    Label(model.saved == nil ? "Set up TV" : "Sync", systemImage: "arrow.triangle.2.circlepath")
                        .font(.system(size: 12, weight: .semibold))
                        .frame(maxWidth: .infinity).frame(height: 34)
                }
                .disabled(model.busy)
                .accessibilityLabel(model.saved == nil ? "Set up TV" : "Sync connection and picture mode")
                .help(model.saved == nil ? "Pair with your television" : "Reconnect and refresh picture mode")

                Rectangle().fill(QuickRemotePalette.ink.opacity(0.20)).frame(width: 1, height: 14)

                Button { model.refreshPicture(toggle: true) } label: {
                    Image(systemName: model.modeSymbol)
                        .font(.system(size: 16, weight: .medium))
                        .frame(width: 54, height: 34)
                }
                .disabled(!model.canControl || model.pictureBusy)
                .accessibilityLabel(model.modeLabel + ". Toggle Day or Night")
                .help(model.modeLabel + " · toggle Day / Night with BareHome")
            }
            .buttonStyle(.plain)
            .foregroundStyle(QuickRemotePalette.ink)
            .background(QuickRemotePalette.accent, in: Capsule())

            dpad

            HStack(spacing: 8) {
                key("arrow.uturn.backward", "Back", code: 4)
                key("house.fill", "Home", code: 3)
                Button { model.inputPicker() } label: {
                    Image(systemName: "gamecontroller").font(.system(size: 18, weight: .medium))
                        .frame(maxWidth: .infinity).frame(height: 38)
                }
                .buttonStyle(QuickRemoteButtonStyle())
                .disabled(!model.canControl)
                .accessibilityLabel("TV inputs")
                .help("TV inputs · requires BareHome 1.1 or newer")
            }

            HStack(spacing: 8) {
                key("speaker.slash", "Mute", code: 164)
                key("speaker.wave.1", "Volume down", code: 25)
                key("speaker.wave.3", "Volume up", code: 24)
                key("playpause.fill", "Play / Pause", code: 85)
            }

            VStack(spacing: 5) {
                HStack(spacing: 6) {
                    Circle().fill(model.connected ? QuickRemotePalette.accent : QuickRemotePalette.paper.opacity(0.3))
                        .frame(width: 5, height: 5)
                    Text(model.status).font(.system(size: 10)).lineLimit(2)
                    if model.busy { ProgressView().controlSize(.mini) }
                }
                .foregroundStyle(QuickRemotePalette.paper.opacity(0.6))
                .accessibilityElement(children: .combine)

                if !model.detail.isEmpty {
                    Text(model.detail).font(.system(size: 10)).foregroundStyle(Color.orange)
                        .multilineTextAlignment(.center).lineLimit(3)
                        .help(model.detail)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 16)
        }
    }

    private var dpad: some View {
        ZStack {
            Circle().fill(QuickRemotePalette.panel)
                .overlay(Circle().strokeBorder(QuickRemotePalette.paper.opacity(0.04), lineWidth: 1))
            key("chevron.up", "Up", code: 19, width: 60, height: 44).offset(y: -59)
            key("chevron.down", "Down", code: 20, width: 60, height: 44).offset(y: 59)
            key("chevron.left", "Left", code: 21, width: 44, height: 60).offset(x: -59)
            key("chevron.right", "Right", code: 22, width: 44, height: 60).offset(x: 59)
            Button { model.sendKey(23) } label: {
                Text("OK").font(.system(size: 12, weight: .semibold, design: .rounded)).tracking(0.8)
                    .frame(width: 62, height: 62)
            }
            .buttonStyle(QuickRemoteButtonStyle(fill: Color(red: 0.24, green: 0.29, blue: 0.25), cornerRadius: 31))
            .disabled(!model.canControl)
            .accessibilityLabel("OK")
            .help("OK")
        }
        .frame(width: 180, height: 180)
        .frame(maxWidth: .infinity)
    }

    private func key(_ symbol: String, _ label: String, code: Int, width: CGFloat? = nil, height: CGFloat = 38) -> some View {
        Button { model.sendKey(code) } label: {
            Image(systemName: symbol).font(.system(size: 17, weight: .medium))
                .frame(maxWidth: width == nil ? .infinity : nil)
                .frame(width: width, height: height)
        }
        .buttonStyle(QuickRemoteButtonStyle())
        .disabled(!model.canControl)
        .accessibilityLabel(label)
        .help(label)
    }
}

enum QuickRemotePalette {
    static let ink = Color(red: 0.075, green: 0.09, blue: 0.085)
    static let panel = Color(red: 0.13, green: 0.15, blue: 0.14)
    static let accent = Color(red: 0.58, green: 0.86, blue: 0.67)
    static let paper = Color(red: 0.91, green: 0.93, blue: 0.89)
}

private struct QuickRemoteButtonStyle: ButtonStyle {
    var fill: Color = QuickRemotePalette.panel
    var cornerRadius: CGFloat = 24
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(fill, in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(configuration.isPressed ? QuickRemotePalette.paper.opacity(0.12) : .clear)
                    .allowsHitTesting(false)
            }
            .contentShape(RoundedRectangle(cornerRadius: cornerRadius))
            .opacity(isEnabled ? 1 : 0.35)
    }
}
