// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BareRemote",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "BareRemote", targets: ["BareRemote"])],
    targets: [
        .target(name: "BareRemoteCore"),
        .executableTarget(name: "BareRemote", dependencies: ["BareRemoteCore"]),
        .testTarget(name: "BareRemoteCoreTests", dependencies: ["BareRemoteCore"]),
        .testTarget(name: "BareRemoteAppTests", dependencies: ["BareRemote"])
    ]
)
