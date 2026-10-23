import AppKit

let output = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: output, withIntermediateDirectories: true)
for size in [16, 32, 64, 128, 256, 512, 1024] {
    let image = NSImage(size: NSSize(width: size, height: size))
    image.lockFocus()
    let scale = CGFloat(size) / 1024
    NSAffineTransform(transform: AffineTransform(scale: scale)).concat()
    let background = NSBezierPath(roundedRect: NSRect(x: 70, y: 70, width: 884, height: 884), xRadius: 200, yRadius: 200)
    NSColor(calibratedRed: 0.09, green: 0.12, blue: 0.10, alpha: 1).setFill(); background.fill()
    let ring = NSBezierPath(ovalIn: NSRect(x: 230, y: 230, width: 564, height: 564))
    NSColor(calibratedRed: 0.58, green: 0.86, blue: 0.67, alpha: 1).setStroke(); ring.lineWidth = 45; ring.stroke()
    let center = NSBezierPath(ovalIn: NSRect(x: 410, y: 410, width: 204, height: 204))
    NSColor(calibratedRed: 0.58, green: 0.86, blue: 0.67, alpha: 1).setFill(); center.fill()
    for angle in [CGFloat(0), .pi / 2, .pi, .pi * 1.5] {
        let transform = NSAffineTransform(); transform.translateX(by: 512, yBy: 512); transform.rotate(byRadians: angle); transform.concat()
        let chevron = NSBezierPath(); chevron.move(to: NSPoint(x: -29, y: 175)); chevron.line(to: NSPoint(x: 0, y: 204)); chevron.line(to: NSPoint(x: 29, y: 175)); chevron.lineWidth = 15; chevron.lineCapStyle = .round; chevron.lineJoinStyle = .round; chevron.stroke()
        transform.invert(); transform.concat()
    }
    image.unlockFocus()
    guard let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff), let png = bitmap.representation(using: .png, properties: [:]) else { fatalError("Could not render icon") }
    let names: [Int: [String]] = [16: ["icon_16x16.png"], 32: ["icon_16x16@2x.png", "icon_32x32.png"], 64: ["icon_32x32@2x.png"], 128: ["icon_128x128.png"], 256: ["icon_128x128@2x.png", "icon_256x256.png"], 512: ["icon_256x256@2x.png", "icon_512x512.png"], 1024: ["icon_512x512@2x.png"]]
    for name in names[size]! { try png.write(to: URL(fileURLWithPath: output).appendingPathComponent(name)) }
}
