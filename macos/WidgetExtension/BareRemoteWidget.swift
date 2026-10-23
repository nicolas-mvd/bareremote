import SwiftUI
import WidgetKit

private enum WidgetLink {
    static let open = URL(string: "bareremote://widget/open")!
    static func key(_ code: Int) -> URL { URL(string: "bareremote://widget/key?code=\(code)")! }
    static func action(_ action: String) -> URL { URL(string: "bareremote://widget/\(action)")! }
}

/// A remote has no useful background refresh. All commands run in the paired app,
/// only after a click; the extension never reads pairing credentials or the network.
private struct RemoteProvider: TimelineProvider {
    func placeholder(in context: Context) -> RemoteEntry { RemoteEntry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (RemoteEntry) -> Void) {
        completion(RemoteEntry(date: .now))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<RemoteEntry>) -> Void) {
        completion(Timeline(entries: [RemoteEntry(date: .now)], policy: .never))
    }
}

private struct RemoteEntry: TimelineEntry { let date: Date }

private struct RemoteWidgetView: View {
    @Environment(\.widgetFamily) private var family
    private let accent = Color(red: 0.58, green: 0.86, blue: 0.67)

    var body: some View {
        Group {
            if family == .systemSmall { launcher }
            else if family == .systemLarge { largeRemote }
            else { remote }
        }
        .foregroundStyle(Color(red: 0.91, green: 0.93, blue: 0.89))
        .containerBackground(Color(red: 0.075, green: 0.09, blue: 0.085), for: .widget)
        .widgetURL(WidgetLink.open)
    }

    private var launcher: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: "tv").font(.system(size: 33, weight: .light)).foregroundStyle(accent)
            Spacer(minLength: 0)
            Text("BareRemote").font(.system(size: 17, weight: .semibold, design: .rounded))
            HStack(spacing: 5) {
                Text("Open remote")
                Image(systemName: "arrow.up.right")
            }.font(.system(size: 11, weight: .medium)).foregroundStyle(accent)
        }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("BareRemote. Open your TV remote")
    }

    private var remote: some View {
        VStack(spacing: 7) {
            header
            HStack(spacing: 16) {
                dpad(diameter: 118)
                VStack(spacing: 5) {
                    HStack(spacing: 5) {
                        control("power", "Power", url: WidgetLink.key(26))
                        control("arrow.triangle.2.circlepath", "Sync connection", url: WidgetLink.action("sync"))
                        control("gamecontroller", "TV inputs", url: WidgetLink.action("inputs"))
                    }
                    HStack(spacing: 5) {
                        control("arrow.uturn.backward", "Back", url: WidgetLink.key(4))
                        control("house.fill", "Home", url: WidgetLink.key(3))
                        control("playpause.fill", "Play or pause", url: WidgetLink.key(85))
                    }
                    HStack(spacing: 5) {
                        control("speaker.wave.1", "Volume down", url: WidgetLink.key(25))
                        control("speaker.slash", "Mute", url: WidgetLink.key(164))
                        control("speaker.wave.3", "Volume up", url: WidgetLink.key(24))
                    }
                }.frame(maxWidth: .infinity)
            }.frame(maxHeight: .infinity)
        }
    }

    private var largeRemote: some View {
        VStack(spacing: 7) {
            header
            HStack(spacing: 7) {
                control("arrow.triangle.2.circlepath", "Sync connection", url: WidgetLink.action("sync"))
                control("circle.lefthalf.filled", "Toggle Day or Night", url: WidgetLink.action("toggle"))
                control("playpause.fill", "Play or pause", url: WidgetLink.key(85))
                control("power", "Power", url: WidgetLink.key(26))
            }
            dpad(diameter: 160).frame(maxWidth: .infinity, maxHeight: .infinity)
            HStack(spacing: 7) {
                control("arrow.uturn.backward", "Back", url: WidgetLink.key(4))
                control("house.fill", "Home", url: WidgetLink.key(3))
                control("gamecontroller", "TV inputs", url: WidgetLink.action("inputs"))
            }
            HStack(spacing: 7) {
                control("speaker.wave.1", "Volume down", url: WidgetLink.key(25))
                control("speaker.slash", "Mute", url: WidgetLink.key(164))
                control("speaker.wave.3", "Volume up", url: WidgetLink.key(24))
            }
        }
    }

    private var header: some View {
        HStack {
            Text("BARE REMOTE").font(.system(size: 10, weight: .bold, design: .rounded)).tracking(1.6)
            Spacer()
            Link(destination: WidgetLink.open) {
                Image(systemName: "arrow.up.right").font(.system(size: 11, weight: .semibold))
            }.accessibilityLabel("Open full remote")
        }.foregroundStyle(accent)
    }

    private func dpad(diameter: CGFloat) -> some View {
        let large = diameter > 118
        return ZStack {
            Circle().fill(.white.opacity(0.055))
            control("chevron.up", "Up", url: WidgetLink.key(19), round: true, size: large ? 44 : 36).offset(y: -diameter / 3)
            control("chevron.down", "Down", url: WidgetLink.key(20), round: true, size: large ? 44 : 36).offset(y: diameter / 3)
            control("chevron.left", "Left", url: WidgetLink.key(21), round: true, size: large ? 44 : 36).offset(x: -diameter / 3)
            control("chevron.right", "Right", url: WidgetLink.key(22), round: true, size: large ? 44 : 36).offset(x: diameter / 3)
            Link(destination: WidgetLink.key(23)) {
                Text("OK").font(.system(size: large ? 15 : 12, weight: .semibold, design: .rounded))
                    .frame(width: large ? 54 : 40, height: large ? 54 : 40)
                    .background(accent.opacity(0.22), in: Circle())
            }.accessibilityLabel("OK")
        }.frame(width: diameter, height: diameter)
    }

    private func control(_ symbol: String, _ label: String, url: URL, round: Bool = false, size: CGFloat = 36) -> some View {
        Link(destination: url) {
            Image(systemName: symbol).font(.system(size: 14, weight: .medium))
                .frame(maxWidth: round ? nil : .infinity)
                .frame(width: round ? size : nil, height: size)
                .background(round ? Color.clear : Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(label)
    }
}

@main
struct BareRemoteWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "uy.nico.bareremote.macos.remote", provider: RemoteProvider()) { _ in
            RemoteWidgetView()
        }
        .configurationDisplayName("BareRemote")
        .description("Keep your TV remote on your desktop. Pair your TV in BareRemote first.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .containerBackgroundRemovable(false)
    }
}
