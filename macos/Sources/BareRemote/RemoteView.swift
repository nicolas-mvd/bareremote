import SwiftUI
import AppKit

private let ink = Color(red: 0.075, green: 0.09, blue: 0.085)
private let panel = Color(red: 0.13, green: 0.15, blue: 0.14)
private let accent = Color(red: 0.58, green: 0.86, blue: 0.67)
private let paper = Color(red: 0.91, green: 0.93, blue: 0.89)

struct RemoteView: View {
    @ObservedObject var model: RemoteModel
    var checkForUpdates: () -> Void = {}
    @StateObject private var discovery = TVDiscovery()
    @State private var showShortcuts = false
    var body: some View {
        VStack(spacing: 22) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("BARE\u{2009}REMOTE").font(.system(size: 13, weight: .bold, design: .rounded)).tracking(2.2)
                    Text(model.saved == nil ? "Your TV, within reach." : model.displayName)
                        .font(.system(size: 12)).foregroundStyle(paper.opacity(0.5)).lineLimit(1)
                }
                Spacer()
                Button { if model.keyboardMode { model.showRemoteMode() } else { model.showKeyboardMode() } } label: {
                    Image(systemName: model.keyboardMode ? "circle.grid.cross" : "keyboard").font(.system(size: 17))
                }.buttonStyle(.plain).frame(width: 32, height: 36)
                    .help(model.keyboardMode ? "Back to remote mode" : "Type on TV (⌘K)")
                    .accessibilityLabel(model.keyboardMode ? "Back to remote mode" : "Switch to keyboard mode")
                Button { model.openSetup() } label: { Image(systemName: "slider.horizontal.3").font(.system(size: 17)) }
                    .buttonStyle(.plain).frame(width: 32, height: 36).help("TV setup (⌘,)").accessibilityLabel("TV setup")
            }
            HStack(spacing: 12) {
                HStack(spacing: 0) {
                    Button { model.sync() } label: {
                        Image(systemName: "arrow.triangle.2.circlepath").frame(width: 54, height: 40)
                    }.help("Sync connection and picture mode (⌘R)").accessibilityLabel("Sync")
                    Rectangle().fill(ink.opacity(0.15)).frame(width: 1, height: 16)
                    Button { model.refreshPicture(toggle: true) } label: {
                        Image(systemName: model.modeSymbol).frame(width: 54, height: 40)
                    }.disabled(!model.canControl || model.pictureBusy)
                        .help("\(model.modeLabel). Toggle Day/Night with BareHome.").accessibilityLabel(model.modeLabel + ". Toggle Day or Night")
                }.font(.system(size: 18, weight: .medium)).buttonStyle(.plain)
                    .background(accent, in: Capsule()).foregroundStyle(ink)
                Spacer()
                control("power", "Power", code: 26, size: 42)
            }
            if model.keyboardMode {
                TVKeyboardView(model: model)
            } else {
            dpad
            HStack(spacing: 14) {
                control("arrow.uturn.backward", "Back", code: 4, width: 76)
                control("house.fill", "Home", code: 3, width: 104)
                Button { model.inputPicker() } label: { Image(systemName: "gamecontroller").font(.system(size: 21)).frame(width: 76, height: 54) }
                    .buttonStyle(.plain).background(panel, in: Capsule()).disabled(!model.canControl)
                    .help("TV inputs · requires BareHome 1.1 or newer").accessibilityLabel("TV inputs")
            }
            HStack(spacing: 10) {
                control("speaker.slash", "Mute", code: 164, width: 62, size: 44)
                control("speaker.wave.1", "Volume down", code: 25, repeatable: true, width: 94, size: 44)
                control("speaker.wave.3", "Volume up", code: 24, repeatable: true, width: 94, size: 44)
            }
            }
            VStack(spacing: 10) {
                HStack(spacing: 6) {
                    Circle().fill(model.connected ? accent : paper.opacity(0.3)).frame(width: 5, height: 5)
                    Text(model.status).font(.system(size: 11)).foregroundStyle(paper.opacity(0.65)).lineLimit(2)
                    if model.busy { ProgressView().controlSize(.mini).scaleEffect(0.7) }
                }
                if !model.detail.isEmpty {
                    Text(model.detail).font(.system(size: 10)).foregroundStyle(Color.orange.opacity(0.9))
                        .multilineTextAlignment(.center).lineLimit(3).textSelection(.enabled)
                }
                HStack(spacing: 16) {
                    Button { model.sendKey(85) } label: { Image(systemName: "playpause.fill") }.help("Play / Pause (Space)").disabled(!model.canControl)
                    Button { showShortcuts.toggle() } label: { Image(systemName: "questionmark.circle") }.help("Keyboard shortcuts")
                        .popover(isPresented: $showShortcuts) { shortcuts.padding(22) }
                }.buttonStyle(.plain).font(.system(size: 13)).foregroundStyle(paper.opacity(0.35))
            }.frame(minHeight: 48)
        }
        .padding(.horizontal, 30).padding(.top, 42).padding(.bottom, 22)
        .frame(width: 360).background(ink).foregroundStyle(paper).preferredColorScheme(.dark)
        .background(KeyboardCapture(model: model))
        .sheet(isPresented: $model.setupOpen, onDismiss: { discovery.stop() }) {
            SetupView(model: model, discovery: discovery, checkForUpdates: checkForUpdates)
        }
        .onAppear { model.appeared() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in model.stopHolding(); discovery.stop() }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didResignKeyNotification)) { _ in model.stopHolding() }
    }
    private var dpad: some View {
        let generation = model.inputGeneration
        return ZStack {
            Circle().fill(panel).overlay(Circle().strokeBorder(paper.opacity(0.04), lineWidth: 1))
            control("chevron.up", "Up", code: 19, repeatable: true, width: 84, size: 64).offset(y: -88)
            control("chevron.down", "Down", code: 20, repeatable: true, width: 84, size: 64).offset(y: 88)
            control("chevron.left", "Left", code: 21, repeatable: true, width: 64, size: 84).offset(x: -88)
            control("chevron.right", "Right", code: 22, repeatable: true, width: 64, size: 84).offset(x: 88)
            PressControl(label: "OK · hold for long press", symbol: nil, title: "OK", enabled: model.canControl,
                         begin: {}, end: {}, action: { model.sendKey(23) }, longAction: {
                    if model.inputGeneration == generation { model.sendKey(23, long: true) }
                })
                .frame(width: 100, height: 100).background(Color(red: 0.24, green: 0.29, blue: 0.25), in: Circle())
                .overlay(Circle().strokeBorder(paper.opacity(0.05), lineWidth: 1)).clipShape(Circle())
                .help("OK (Return) · hold for long press")
        }.frame(width: 280, height: 280)
    }
    private func control(_ symbol: String, _ label: String, code: Int, repeatable: Bool = false, width: CGFloat? = nil, size: CGFloat = 54) -> some View {
        PressControl(label: label, symbol: symbol, title: nil, enabled: model.canControl,
                     begin: { if repeatable { model.startHolding(code) } }, end: { model.stopHolding() },
                     action: { if !repeatable { model.sendKey(code) } }, longAction: nil)
            .frame(width: width ?? size, height: size)
            .background(panel, in: Capsule()).clipShape(Capsule()).help(label + (repeatable ? " · hold to repeat" : ""))
    }
    private var shortcuts: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Make yourself comfortable").font(.headline)
            Grid(alignment: .leading, horizontalSpacing: 30, verticalSpacing: 9) {
                shortcut("↑ ↓ ← →", "Navigate")
                shortcut("Return", "OK · hold for long press")
                shortcut("Esc", "Back")
                shortcut("H", "Home")
                shortcut("− / +", "Volume · hold to repeat")
                shortcut("M", "Mute")
                shortcut("Space", "Play / Pause")
                shortcut("⌘R", "Sync")
                shortcut("⌘,", "TV setup")
            }.font(.system(size: 12))
            Text("Shortcuts work while the remote window is active.").font(.caption).foregroundStyle(.secondary)
        }
    }
    private func shortcut(_ keys: String, _ meaning: String) -> some View {
        GridRow { Text(keys).fontDesign(.monospaced).foregroundStyle(accent); Text(meaning) }
    }
}

private struct PressControl: View {
    let label: String
    let symbol: String?
    let title: String?
    let enabled: Bool
    let begin: () -> Void
    let end: () -> Void
    let action: () -> Void
    let longAction: (() -> Void)?
    @State private var pressed = false
    var body: some View {
        ZStack {
            if let symbol { Image(systemName: symbol).font(.system(size: 21, weight: .medium)) }
            if let title { Text(title).font(.system(size: 15, weight: .semibold, design: .rounded)).tracking(1) }
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(pressed ? paper.opacity(0.10) : Color.clear)
            .opacity(enabled ? 1 : 0.35)
            .overlay(MousePressArea(label: label, enabled: enabled, pressed: $pressed, begin: begin, end: end, action: action, longAction: longAction))
    }
}

private struct MousePressArea: NSViewRepresentable {
    let label: String
    let enabled: Bool
    @Binding var pressed: Bool
    let begin: () -> Void
    let end: () -> Void
    let action: () -> Void
    let longAction: (() -> Void)?
    func makeNSView(context: Context) -> PressView { PressView() }
    func updateNSView(_ view: PressView, context: Context) {
        view.enabled = enabled; view.setAccessibilityElement(true); view.setAccessibilityRole(.button)
        view.setAccessibilityLabel(label); view.setAccessibilityEnabled(enabled)
        view.begin = begin; view.end = end; view.action = action; view.longAction = longAction
        view.highlight = { pressed = $0 }
    }
    final class PressView: NSView {
        var enabled = true { didSet { if oldValue && !enabled { cancel() } } }
        var begin: () -> Void = {}; var end: () -> Void = {}; var action: () -> Void = {}
        var longAction: (() -> Void)?; var highlight: (Bool) -> Void = { _ in }
        private var tracking = false
        private var longSent = false
        private var timer: Timer?
        private var deactivation: NSObjectProtocol?
        private var windowResign: NSObjectProtocol?
        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            deactivation = NotificationCenter.default.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.cancel() }
            }
            windowResign = NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification, object: nil, queue: .main) { [weak self] note in
                MainActor.assumeIsolated { if let self, note.object as? NSWindow === self.window { self.cancel() } }
            }
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
        deinit {
            if let deactivation { NotificationCenter.default.removeObserver(deactivation) }
            if let windowResign { NotificationCenter.default.removeObserver(windowResign) }
            timer?.invalidate()
        }
        override func mouseDown(with event: NSEvent) {
            guard enabled else { return }
            tracking = true; longSent = false; highlight(true); begin()
            if longAction != nil {
                timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { [weak self] _ in
                    guard let self, self.tracking, self.enabled else { return }
                    self.longSent = true; self.longAction?()
                }
            }
        }
        override func mouseDragged(with event: NSEvent) {
            if !bounds.contains(convert(event.locationInWindow, from: nil)) { cancel() }
        }
        override func mouseUp(with event: NSEvent) {
            let commit = tracking && enabled && bounds.contains(convert(event.locationInWindow, from: nil)) && !longSent
            cancel(); if commit { action() }
        }
        private func cancel() { tracking = false; timer?.invalidate(); timer = nil; highlight(false); end() }
        override func viewWillMove(toWindow newWindow: NSWindow?) { if newWindow == nil { cancel() } }
        override func accessibilityPerformPress() -> Bool { guard enabled else { return false }; begin(); end(); action(); return true }
    }
}
