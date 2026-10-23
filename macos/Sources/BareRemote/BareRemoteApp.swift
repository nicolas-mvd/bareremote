import AppKit
import SwiftUI
import BareRemoteCore

@main enum BareRemoteApplication {
    @MainActor static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        withExtendedLifetime(delegate) { app.run() }
    }
}

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let model = RemoteModel()
    private let updates = UpdateWindowController()
    private var menuBar: MenuBarController?
    private var remoteWindow: NSWindow?
    private var desktopRemote: DesktopRemoteController?
    private var recentDeliveries: [UUID] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        model.keyboardWindowRequested = { [weak self] in self?.showRemote() }
        createMainMenu()
        let bar = MenuBarController(model: model,
            openRemote: { [weak self] in self?.showRemote() },
            openDesktop: { [weak self] in self?.showDesktopRemote() },
            openSetup: { [weak self] in self?.showSetup() },
            quit: { NSApp.terminate(nil) })
        bar.onShow = { [weak self] in self?.model.surfaceOpened(.menu) }
        bar.onClose = { [weak self] in self?.model.surfaceClosed(.menu) }
        menuBar = bar
        desktopRemote = DesktopRemoteController(model: model,
            openRemote: { [weak self] in self?.showRemote() },
            onHide: { UserDefaults.standard.set(false, forKey: "desktopRemoteVisible") })
        if UserDefaults.standard.object(forKey: "desktopRemoteVisible") == nil ||
            UserDefaults.standard.bool(forKey: "desktopRemoteVisible") {
            showDesktopRemote()
        }
        if CommandLine.arguments.contains("--bareremote-update-failed") { updates.showInstallFailure() }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        menuBar?.show()
        return false
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationWillTerminate(_ notification: Notification) { model.disconnect() }

    /// The single route for widget links. Controls don't create a remote window.
    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls.prefix(32) {
            do {
                let command = try RemoteShortcutCommand(url: url)
                if let id = command.deliveryIdentifier {
                    guard !recentDeliveries.contains(id) else { continue }
                    recentDeliveries.append(id)
                    if recentDeliveries.count > 64 { recentDeliveries.removeFirst() }
                }
                if command.action == .open { showRemote(); continue }
                guard model.saved != nil, !model.setupOpen else { showSetup(); continue }
                model.performShortcut(command.action)
            } catch {
                model.detail = error.localizedDescription
                showRemote()
            }
        }
    }

    @objc func showRemote() {
        menuBar?.close()
        if remoteWindow == nil {
            let hosting = NSHostingView(rootView: RemoteView(model: model, checkForUpdates: { [weak self] in self?.showUpdates() }))
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 710),
                styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView], backing: .buffered, defer: false)
            window.title = "BareRemote"
            window.titleVisibility = .hidden
            window.titlebarAppearsTransparent = true
            window.isMovableByWindowBackground = true
            window.isReleasedWhenClosed = false
            window.backgroundColor = NSColor(calibratedRed: 0.075, green: 0.09, blue: 0.085, alpha: 1)
            window.contentView = hosting
            window.setContentSize(hosting.fittingSize)
            window.delegate = self
            window.center()
            remoteWindow = window
        }
        NSApp.setActivationPolicy(.regular)
        model.surfaceOpened(.window)
        remoteWindow?.deminiaturize(nil)
        remoteWindow?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc func showSetup() { model.openSetup(); showRemote() }
    @objc func showUpdates() { updates.show() }
    @objc func showKeyboard() { showRemote(); model.showKeyboardMode() }
    @objc func sync() {
        if model.saved == nil { showSetup() }
        else { model.sync() }
    }
    @objc func disconnect() { model.disconnect() }
    @objc func toggleFloating() {
        guard let window = remoteWindow else { showRemote(); return }
        window.level = window.level == .floating ? .normal : .floating
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        model.stopHolding()
        model.surfaceClosed(.window)
        if model.setupOpen { model.cancelPairing(); model.setupOpen = false }
        return true
    }
    func windowWillClose(_ notification: Notification) {
        model.surfaceClosed(.window)
        NSApp.setActivationPolicy(.accessory)
    }
    func windowDidMiniaturize(_ notification: Notification) { model.surfaceClosed(.window) }
    func windowDidDeminiaturize(_ notification: Notification) { model.surfaceOpened(.window) }
    func applicationDidHide(_ notification: Notification) { model.surfaceClosed(.window) }
    func applicationDidUnhide(_ notification: Notification) {
        if remoteWindow?.isVisible == true { model.surfaceOpened(.window) }
    }

    @objc func showDesktopRemote() {
        UserDefaults.standard.set(true, forKey: "desktopRemoteVisible")
        desktopRemote?.show()
    }

    private func createMainMenu() {
        let main = NSMenu()
        func submenu(_ title: String) -> NSMenu {
            let holder = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            let menu = NSMenu(title: title)
            holder.submenu = menu; main.addItem(holder)
            return menu
        }
        func item(_ menu: NSMenu, _ title: String, _ action: Selector, _ key: String = "", target: AnyObject? = nil) {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.target = target
            menu.addItem(item)
        }
        let app = submenu("BareRemote")
        item(app, "About BareRemote", #selector(NSApplication.orderFrontStandardAboutPanel(_:)))
        item(app, "Check for Updates…", #selector(showUpdates), target: self)
        app.addItem(.separator())
        item(app, "TV Setup…", #selector(showSetup), ",", target: self)
        item(app, "Show Desktop Remote", #selector(showDesktopRemote), target: self)
        app.addItem(.separator())
        let services = NSMenu(title: "Services")
        let servicesItem = NSMenuItem(title: "Services", action: nil, keyEquivalent: "")
        servicesItem.submenu = services; app.addItem(servicesItem); NSApp.servicesMenu = services
        app.addItem(.separator())
        item(app, "Hide BareRemote", #selector(NSApplication.hide(_:)), "h")
        item(app, "Show All", #selector(NSApplication.unhideAllApplications(_:)))
        app.addItem(.separator())
        item(app, "Quit BareRemote", #selector(NSApplication.terminate(_:)), "q")
        let edit = submenu("Edit")
        item(edit, "Undo", Selector(("undo:")), "z")
        item(edit, "Cut", #selector(NSText.cut(_:)), "x")
        item(edit, "Copy", #selector(NSText.copy(_:)), "c")
        item(edit, "Paste", #selector(NSText.paste(_:)), "v")
        item(edit, "Select All", #selector(NSText.selectAll(_:)), "a")
        let remote = submenu("Remote")
        item(remote, "Open Remote", #selector(showRemote), "o", target: self)
        item(remote, "Keyboard Mode", #selector(showKeyboard), "k", target: self)
        item(remote, "Sync", #selector(sync), "r", target: self)
        item(remote, "Disconnect", #selector(disconnect), target: self)
        item(remote, "Keep on Top", #selector(toggleFloating), target: self)
        let windows = submenu("Window")
        item(windows, "Minimize", #selector(NSWindow.performMiniaturize(_:)), "m")
        item(windows, "Zoom", #selector(NSWindow.performZoom(_:)))
        NSApp.windowsMenu = windows
        NSApp.mainMenu = main
    }
}
