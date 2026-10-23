import AppKit
import SwiftUI
import OSLog

/// Keeps BareRemote in the menu bar when all of its regular windows are closed.
/// Visibility callbacks let the app share one foreground session across surfaces.
@MainActor final class MenuBarController: NSObject, NSPopoverDelegate {
    var onShow: () -> Void = {}
    var onClose: () -> Void = {}

    private let statusItem: NSStatusItem
    private let popover = NSPopover()
    private var hosting: NSHostingController<QuickRemoteViewport>?
    private var surfaceIsVisible = false
    private let logger = Logger(subsystem: "uy.nico.bareremote.macos", category: "MenuBarLayout")

    init(model: RemoteModel, openRemote: @escaping () -> Void, openDesktop: @escaping () -> Void,
         openSetup: @escaping () -> Void, quit: @escaping () -> Void) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        super.init()

        popover.behavior = .transient
        popover.animates = true
        popover.delegate = self
        let content = QuickRemoteView(
            model: model,
            openRemote: { [weak self] in self?.close(); openRemote() },
            openDesktop: { [weak self] in self?.close(); openDesktop() },
            openSetup: { [weak self] in self?.close(); openSetup() },
            quit: { [weak self] in self?.close(); quit() }
        )
        let hosting = NSHostingController(rootView: QuickRemoteViewport(content: content,
            size: CGSize(width: 300, height: 580)))
        // SwiftUI may update its status text while the popover is open. Keep
        // AppKit's viewport stable instead of resizing an already placed window.
        hosting.sizingOptions = []
        self.hosting = hosting
        popover.contentViewController = hosting

        if let button = statusItem.button {
            let image = NSImage(systemSymbolName: "tv", accessibilityDescription: "BareRemote")
            image?.isTemplate = true
            button.image = image
            button.toolTip = "BareRemote — quick TV controls"
            button.setAccessibilityLabel("BareRemote")
            button.setAccessibilityIdentifier("bareremote-menu-bar")
            button.target = self
            button.action = #selector(toggle)
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
    }

    @objc private func toggle() {
        if popover.isShown { close() } else { show() }
    }

    func show() {
        guard !popover.isShown, let button = statusItem.button else { return }
        sizeToFitDisplay(below: button)
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        guard popover.isShown else { return }
        if !surfaceIsVisible { surfaceIsVisible = true; onShow() }
        popover.contentViewController?.view.window?.makeKey()
    }

    func close() {
        surfaceDidClose()
        popover.close()
    }

    func popoverWillClose(_ notification: Notification) { surfaceDidClose() }
    func popoverDidClose(_ notification: Notification) { surfaceDidClose() }

    private func sizeToFitDisplay(below button: NSStatusBarButton) {
        guard let hosting, let window = button.window, let screen = window.screen else { return }
        let anchor = window.convertToScreen(button.convert(button.bounds, to: nil))
        // Leave room for the popover arrow and window border above the Dock.
        let available = max(1, floor(min(anchor.minY, screen.visibleFrame.maxY) - screen.visibleFrame.minY - 24))
        let measure = NSHostingController(rootView: hosting.rootView.content)
        let ideal = measure.sizeThatFits(in: CGSize(width: 300, height: CGFloat.greatestFiniteMagnitude))
        let size = CGSize(width: 300, height: min(ceil(ideal.height), available))
        hosting.rootView = QuickRemoteViewport(content: hosting.rootView.content, size: size)
        hosting.preferredContentSize = size
        hosting.view.setFrameSize(size)
        hosting.view.layoutSubtreeIfNeeded()
        popover.contentSize = size
    }

    func popoverDidShow(_ notification: Notification) {
        guard let button = statusItem.button, let anchorWindow = button.window,
              let screen = anchorWindow.screen, let window = hosting?.view.window else { return }
        let anchor = anchorWindow.convertToScreen(button.convert(button.bounds, to: nil))
        logger.info("anchor=\(NSStringFromRect(anchor), privacy: .public) visible=\(NSStringFromRect(screen.visibleFrame), privacy: .public) popup=\(NSStringFromRect(window.frame), privacy: .public) content=\(NSStringFromSize(self.popover.contentSize), privacy: .public) flipped=\(button.isFlipped)")
    }

    private func surfaceDidClose() {
        guard surfaceIsVisible else { return }
        surfaceIsVisible = false
        onClose()
    }
}
