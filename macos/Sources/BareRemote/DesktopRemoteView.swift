import AppKit
import BareRemoteCore
import SwiftUI

struct DesktopRemoteView: View {
    @ObservedObject var model: RemoteModel
    let openRemote: () -> Void
    let hide: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            header
            if model.keyboardMode {
                VStack(spacing: 12) {
                    Image(systemName: "keyboard").font(.system(size: 30)).foregroundStyle(QuickRemotePalette.accent)
                    Text("Keyboard on TV").font(.system(size: 16, weight: .semibold))
                    Button("Type on TV") { model.openKeyboardWindow() }.buttonStyle(.borderedProminent)
                        .tint(QuickRemotePalette.accent).foregroundStyle(QuickRemotePalette.ink)
                    Button("Back to remote") { model.showRemoteMode() }.buttonStyle(.plain)
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
            HStack(spacing: 14) {
                dpad
                VStack(spacing: 6) {
                    HStack(spacing: 6) {
                        control("arrow.triangle.2.circlepath", "Sync connection", action: .sync)
                        control("circle.lefthalf.filled", "Toggle Day or Night", action: .toggle)
                        control("power", "Power", action: .key(26))
                    }
                    HStack(spacing: 6) {
                        control("arrow.uturn.backward", "Back", action: .key(4))
                        control("house.fill", "Home", action: .key(3))
                        control("gamecontroller", "TV inputs", action: .inputs)
                    }
                    HStack(spacing: 6) {
                        control("speaker.wave.1", "Volume down", action: .key(25))
                        control("speaker.slash", "Mute", action: .key(164))
                        control("speaker.wave.3", "Volume up", action: .key(24))
                    }
                }.frame(maxWidth: .infinity)
            }
            HStack(spacing: 10) {
                control("playpause.fill", "Play or pause", action: .key(85), width: 40, height: 32)
                VStack(alignment: .leading, spacing: 3) {
                    Text(statusText)
                        .font(.system(size: 10)).foregroundStyle(QuickRemotePalette.paper.opacity(0.65))
                        .lineLimit(1)
                    if !model.detail.isEmpty {
                        Text(model.detail).font(.system(size: 9)).foregroundStyle(.orange)
                            .lineLimit(2).help(model.detail)
                    } else {
                        Text("Drag the title to move").font(.system(size: 9)).foregroundStyle(QuickRemotePalette.paper.opacity(0.32))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.frame(height: 32)
            }
        }
        .padding(16)
        .frame(width: 340, height: 270)
        .background(QuickRemotePalette.ink, in: RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(QuickRemotePalette.paper.opacity(0.08), lineWidth: 1))
        .foregroundStyle(QuickRemotePalette.paper)
        .preferredColorScheme(.dark)
    }

    private var header: some View {
        HStack(spacing: 8) {
            ZStack(alignment: .leading) {
                DesktopDragHandle()
                VStack(alignment: .leading, spacing: 3) {
                    Text("BARE REMOTE").font(.system(size: 10, weight: .bold, design: .rounded)).tracking(1.5)
                        .foregroundStyle(QuickRemotePalette.accent)
                    Text(model.saved == nil ? "Your TV, within reach." : model.displayName)
                        .font(.system(size: 11)).lineLimit(1).foregroundStyle(QuickRemotePalette.paper.opacity(0.65))
                }.allowsHitTesting(false)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Button { model.openKeyboardWindow() } label: {
                Image(systemName: "keyboard").font(.system(size: 13, weight: .medium)).frame(width: 28, height: 28)
            }.buttonStyle(DesktopRemoteButtonStyle(fill: .clear))
                .accessibilityLabel("Switch to keyboard mode").help("Type on TV")
            Button(action: openRemote) {
                Image(systemName: "arrow.up.right").font(.system(size: 12, weight: .medium)).frame(width: 28, height: 28)
            }.buttonStyle(DesktopRemoteButtonStyle(fill: .clear))
                .accessibilityLabel("Open full remote").help("Open full remote")
            Button(action: hide) {
                Image(systemName: "xmark").font(.system(size: 11, weight: .medium)).frame(width: 28, height: 28)
            }.buttonStyle(DesktopRemoteButtonStyle(fill: .clear))
                .accessibilityLabel("Hide desktop remote").help("Hide desktop remote")
        }.frame(height: 34)
    }

    private var dpad: some View {
        ZStack {
            Circle().fill(QuickRemotePalette.panel)
                .overlay(Circle().strokeBorder(QuickRemotePalette.paper.opacity(0.04), lineWidth: 1))
            control("chevron.up", "Up", action: .key(19), width: 40, height: 36, fill: .clear).offset(y: -49)
            control("chevron.down", "Down", action: .key(20), width: 40, height: 36, fill: .clear).offset(y: 49)
            control("chevron.left", "Left", action: .key(21), width: 36, height: 40, fill: .clear).offset(x: -49)
            control("chevron.right", "Right", action: .key(22), width: 36, height: 40, fill: .clear).offset(x: 49)
            Button { perform(.key(23)) } label: {
                Text("OK").font(.system(size: 12, weight: .semibold, design: .rounded)).tracking(0.6)
                    .frame(width: 52, height: 52)
            }.buttonStyle(DesktopRemoteButtonStyle(fill: QuickRemotePalette.accent.opacity(0.22), radius: 26))
                .disabled(!actionsAvailable).accessibilityLabel("OK").help("OK")
        }.frame(width: 148, height: 148)
    }

    private var actionsAvailable: Bool { !model.setupOpen && !model.awaitingCode }

    private var statusText: String {
        if model.setupOpen || model.awaitingCode { return "Finish TV setup in the app" }
        if model.saved == nil { return "Pair your TV in the app" }
        return model.connected || model.busy ? model.status : "Tap a control to connect"
    }

    private func perform(_ action: RemoteShortcutAction) {
        guard actionsAvailable else { return }
        guard model.saved != nil else { openRemote(); return }
        model.performShortcut(action)
    }

    private func control(_ symbol: String, _ label: String, action: RemoteShortcutAction,
                         width: CGFloat? = nil, height: CGFloat = 40, fill: Color = QuickRemotePalette.panel) -> some View {
        Button { perform(action) } label: {
            Image(systemName: symbol).font(.system(size: 14, weight: .medium))
                .frame(maxWidth: width == nil ? .infinity : nil).frame(width: width, height: height)
        }.buttonStyle(DesktopRemoteButtonStyle(fill: fill))
            .disabled(!actionsAvailable || (action == .toggle && model.pictureBusy))
            .accessibilityLabel(label).help(label)
    }
}

private struct DesktopRemoteButtonStyle: ButtonStyle {
    var fill: Color = QuickRemotePalette.panel
    var radius: CGFloat = 12
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(fill, in: RoundedRectangle(cornerRadius: radius))
            .overlay(RoundedRectangle(cornerRadius: radius).fill(configuration.isPressed ? .white.opacity(0.12) : .clear).allowsHitTesting(false))
            .contentShape(RoundedRectangle(cornerRadius: radius))
            .opacity(isEnabled ? 1 : 0.35)
    }
}

private struct DesktopDragHandle: NSViewRepresentable {
    func makeNSView(context: Context) -> DragView { DragView() }
    func updateNSView(_ view: DragView, context: Context) {}
    final class DragView: NSView {
        override var mouseDownCanMoveWindow: Bool { true }
        override func mouseDown(with event: NSEvent) { window?.performDrag(with: event) }
        override func resetCursorRects() { addCursorRect(bounds, cursor: .openHand) }
    }
}
