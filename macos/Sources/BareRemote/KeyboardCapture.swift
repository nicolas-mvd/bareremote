import SwiftUI
import AppKit

struct KeyboardCapture: NSViewRepresentable {
    let model: RemoteModel
    func makeCoordinator() -> Coordinator { Coordinator(model: model) }
    func makeNSView(context: Context) -> NSView { context.coordinator.install(); return NSView() }
    func updateNSView(_ nsView: NSView, context: Context) { if !model.canControl || model.keyboardMode { context.coordinator.cancelKeys() } }
    static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) { coordinator.remove() }

    @MainActor final class Coordinator {
        let model: RemoteModel
        private var monitor: Any?
        private var pressed: Set<UInt16> = []
        private var okTimer: Timer?
        private var longOK = false
        private var resigned: NSObjectProtocol?
        init(model: RemoteModel) { self.model = model }
        func install() {
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { [weak self] event in
                guard let self else { return event }
                return self.handle(event)
            }
            resigned = NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.cancelKeys() }
            }
        }
        private func handle(_ event: NSEvent) -> NSEvent? {
            let key = event.keyCode
            // Release tracked controls even when modifiers or focus changed during the hold.
            if event.type == .keyUp, pressed.remove(key) != nil {
                if [126, 125, 123, 124, 27, 24].contains(key) { model.stopHolding() }
                if key == 36 || key == 76 {
                    okTimer?.invalidate(); okTimer = nil
                    if !longOK && model.canControl { model.sendKey(23) }
                }
                return nil
            }
            guard model.canControl, !model.keyboardMode, event.window?.isKeyWindow == true,
                  !event.modifierFlags.contains(.command), !event.modifierFlags.contains(.control), !event.modifierFlags.contains(.option),
                  !(event.window?.firstResponder is NSTextView) else { return event }
            let repeatKeys: [UInt16: Int] = [126: 19, 125: 20, 123: 21, 124: 22, 27: 25, 24: 24]
            let tapKeys: [UInt16: Int] = [53: 4, 4: 3, 46: 164, 49: 85]
            guard repeatKeys[key] != nil || tapKeys[key] != nil || key == 36 || key == 76 else { return event }
            if event.type == .keyDown {
                guard !event.isARepeat, !pressed.contains(key) else { return nil }
                pressed.insert(key)
                if let code = repeatKeys[key] { model.startHolding(code) }
                else if key == 36 || key == 76 {
                    longOK = false
                    let generation = model.inputGeneration
                    okTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { [weak self] _ in
                        MainActor.assumeIsolated {
                            guard let self, self.model.inputGeneration == generation else { return }
                            self.longOK = true; self.model.sendKey(23, long: true)
                        }
                    }
                } else if let code = tapKeys[key] { model.sendKey(code) }
            }
            return nil
        }
        func cancelKeys() { pressed.removeAll(); okTimer?.invalidate(); okTimer = nil; model.stopHolding() }
        func remove() {
            if let monitor { NSEvent.removeMonitor(monitor) }; monitor = nil
            if let resigned { NotificationCenter.default.removeObserver(resigned) }; resigned = nil
            cancelKeys()
        }
    }
}
