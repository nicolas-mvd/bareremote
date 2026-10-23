import AppKit
import CoreGraphics
import SwiftUI

/// An app-owned desktop remote. Displaying it does not own a network session;
/// each explicit control action uses RemoteModel's bounded shortcut session.
@MainActor final class DesktopRemoteController {
    private let panel: DesktopRemotePanel
    private let onHide: () -> Void
    private static let frameName = NSWindow.FrameAutosaveName("BareRemoteDesktopRemote")

    init(model: RemoteModel, openRemote: @escaping () -> Void, onHide: @escaping () -> Void) {
        self.onHide = onHide
        panel = DesktopRemotePanel(contentRect: NSRect(x: 0, y: 0, width: 340, height: 270),
                                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.title = "BareRemote Desktop"
        panel.identifier = NSUserInterfaceItemIdentifier("bareremote-desktop-remote")
        panel.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopIconWindow)) + 1)
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        panel.isFloatingPanel = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.hidesOnDeactivate = false
        panel.canHide = false
        panel.isReleasedWhenClosed = false
        panel.isMovableByWindowBackground = true
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.contentView = NSHostingView(rootView: DesktopRemoteView(model: model, openRemote: openRemote, hide: { [weak self] in self?.hide() }))
        if !panel.setFrameUsingName(Self.frameName), let screen = NSScreen.main ?? NSScreen.screens.first {
            panel.setFrameTopLeftPoint(NSPoint(x: screen.visibleFrame.minX + 24, y: screen.visibleFrame.maxY - 24))
        }
        panel.setFrameAutosaveName(Self.frameName)
    }

    var isVisible: Bool { panel.isVisible }

    func show() {
        // Its desktop level keeps ordinary application windows above it.
        panel.orderFrontRegardless()
    }

    func hide() {
        panel.saveFrame(usingName: Self.frameName)
        panel.orderOut(nil)
        onHide()
    }
}

private final class DesktopRemotePanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}
