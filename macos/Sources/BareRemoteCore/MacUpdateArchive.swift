import Foundation
import Compression

/// Inspect both ZIP directories before bounded, ordinary-file extraction.
/// No ZIP64, links, encryption, path traversal, alias paths or unsupported compression.
public enum MacUpdateArchive {
    public static func validate(_ data: Data) throws { _ = try entries(data) }

    /// Extract exactly the preflighted ordinary files. Each decoder has an output
    /// buffer capped to the entry's declared size, itself inside the total bound.
    /// No general-purpose archive tool can materialize links or surprise files.
    public static func extract(_ data: Data, to destination: URL) throws {
        let records = try entries(data)
        let manager = FileManager.default
        guard destination.standardizedFileURL == destination.resolvingSymlinksInPath(),
              (try manager.contentsOfDirectory(atPath: destination.path)).isEmpty else { throw MacUpdateError.invalidArchive }
        for entry in records {
            try Task.checkCancellation()
            if entry.name.hasPrefix("__MACOSX/") { continue }
            let output = destination.appendingPathComponent(entry.name)
            if entry.name.hasSuffix("/") {
                try manager.createDirectory(at: output, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o755])
                continue
            }
            try manager.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o755])
            var decoded: Data
            if entry.method == 0 {
                guard entry.compressed == entry.expanded else { throw MacUpdateError.invalidArchive }
                decoded = data.subdata(in: entry.offset..<(entry.offset + entry.compressed))
            } else {
                decoded = Data(count: (entry.expanded + 1))
                let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
                defer { stream.deallocate() }
                guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { throw MacUpdateError.invalidArchive }
                defer { compression_stream_destroy(stream) }
                let valid = decoded.withUnsafeMutableBytes { outputBuffer in
                    data.withUnsafeBytes { inputBuffer in
                        stream.pointee.src_ptr = inputBuffer.bindMemory(to: UInt8.self).baseAddress!.advanced(by: entry.offset)
                        stream.pointee.src_size = entry.compressed
                        stream.pointee.dst_ptr = outputBuffer.bindMemory(to: UInt8.self).baseAddress!
                        stream.pointee.dst_size = (entry.expanded + 1)
                        var status = COMPRESSION_STATUS_OK
                        repeat {
                            if Task.isCancelled { return false }
                            let previousSource = stream.pointee.src_size, previousOutput = stream.pointee.dst_size
                            status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                            if status == COMPRESSION_STATUS_OK && (stream.pointee.dst_size == 0 ||
                                (previousSource == stream.pointee.src_size && previousOutput == stream.pointee.dst_size)) { return false }
                        } while status == COMPRESSION_STATUS_OK
                        let actual = (entry.expanded + 1) - stream.pointee.dst_size
                        return status == COMPRESSION_STATUS_END && stream.pointee.src_size == 0 && actual == entry.expanded
                    }
                }
                guard valid else { throw MacUpdateError.invalidArchive }
                decoded.count = entry.expanded
            }
            guard try crc32(decoded) == entry.crc else { throw MacUpdateError.invalidArchive }
            try decoded.write(to: output, options: .withoutOverwriting)
            let permissions = entry.mode == 0 ? 0o644 : entry.mode & 0o755
            try manager.setAttributes([.posixPermissions: permissions], ofItemAtPath: output.path)
        }
    }
    private struct Entry {
        let name: String, offset: Int, compressed: Int, expanded: Int, method: Int, mode: Int
        let crc: UInt32
    }
    private static let crcTable: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = crc & 1 == 1 ? (crc >> 1) ^ 0xedb88320 : crc >> 1 }
        return crc
    }
    private static func crc32(_ data: Data) throws -> UInt32 {
        var crc: UInt32 = 0xffffffff
        var position = 0
        for byte in data {
            if position & 65535 == 0 { try Task.checkCancellation() }
            crc = (crc >> 8) ^ crcTable[Int((crc ^ UInt32(byte)) & 255)]
            position += 1
        }
        return crc ^ 0xffffffff
    }
    private static func entries(_ data: Data) throws -> [Entry] {
        guard data.count >= 22, data.count <= MacUpdatePolicy.maximumArchiveSize else { throw MacUpdateError.invalidArchive }
        let b = [UInt8](data)
        func u16(_ i: Int) throws -> Int {
            guard i >= 0, i + 2 <= b.count else { throw MacUpdateError.invalidArchive }
            return Int(b[i]) | Int(b[i + 1]) << 8
        }
        func u32(_ i: Int) throws -> Int {
            guard i >= 0, i + 4 <= b.count else { throw MacUpdateError.invalidArchive }
            return Int(b[i]) | Int(b[i + 1]) << 8 | Int(b[i + 2]) << 16 | Int(b[i + 3]) << 24
        }
        // Archives produced by BareRemote have no comments or trailing data.
        let end = b.count - 22
        guard (try u32(end)) == 0x06054b50, (try u16(end + 4)) == 0, (try u16(end + 6)) == 0,
              (try u16(end + 8)) == (try u16(end + 10)), (try u16(end + 20)) == 0 else { throw MacUpdateError.invalidArchive }
        let count = (try u16(end + 10)), centralSize = (try u32(end + 12)), centralStart = (try u32(end + 16))
        guard count > 0, count <= 4096, centralStart > 0, centralStart + centralSize == end else { throw MacUpdateError.invalidArchive }
        var cursor = centralStart, total = 0, names = Set<String>(), ranges: [Range<Int>] = []
        var records: [Entry] = []
        for _ in 0..<count {
            try Task.checkCancellation()
            guard (try u32(cursor)) == 0x02014b50 else { throw MacUpdateError.invalidArchive }
            let flags = (try u16(cursor + 8)), method = (try u16(cursor + 10))
            let compressed = (try u32(cursor + 20)), expanded = (try u32(cursor + 24))
            let length = (try u16(cursor + 28)), extra = (try u16(cursor + 30)), comment = (try u16(cursor + 32))
            let attributes = (try u32(cursor + 38)), local = (try u32(cursor + 42))
            guard flags & ~0x808 == 0, [0, 8].contains(method), (try u16(cursor + 34)) == 0,
                  length > 0, length <= 1024, expanded <= MacUpdatePolicy.maximumExpandedSize,
                  compressed <= MacUpdatePolicy.maximumArchiveSize,
                  cursor + 46 + length + extra + comment <= end,
                  let name = String(bytes: b[(cursor + 46)..<(cursor + 46 + length)], encoding: .utf8),
                  safePath(name), names.insert(name.precomposedStringWithCanonicalMapping.lowercased()).inserted else { throw MacUpdateError.invalidArchive }
            let type = (attributes >> 16) & 0xf000
            guard type == 0 || type == 0x8000 || type == 0x4000,
                  (type != 0x4000 || name.hasSuffix("/")), (type != 0x8000 || !name.hasSuffix("/")) else { throw MacUpdateError.invalidArchive }
            total += expanded
            guard total <= MacUpdatePolicy.maximumExpandedSize, (try u32(local)) == 0x04034b50,
                  (try u16(local + 6)) == flags, (try u16(local + 8)) == method, (try u16(local + 26)) == length else { throw MacUpdateError.invalidArchive }
            let localExtra = (try u16(local + 28)), dataStart = local + 30 + length + localExtra
            guard local >= 0, dataStart + compressed <= centralStart,
                  Array(b[(local + 30)..<(local + 30 + length)]) == Array(name.utf8) else { throw MacUpdateError.invalidArchive }
            if flags & 8 == 0 {
                guard (try u32(local + 14)) == (try u32(cursor + 16)), (try u32(local + 18)) == compressed,
                      (try u32(local + 22)) == expanded else { throw MacUpdateError.invalidArchive }
            }
            records.append(Entry(name: name, offset: dataStart, compressed: compressed, expanded: expanded, method: method, mode: attributes >> 16, crc: UInt32(try u32(cursor + 16))))
            ranges.append(local..<(dataStart + compressed))
            cursor += 46 + length + extra + comment
        }
        guard cursor == end, names.contains("bareremote.app/contents/info.plist"),
              names.contains("bareremote.app/contents/macos/bareremote") else { throw MacUpdateError.invalidArchive }
        let sorted = ranges.sorted { $0.lowerBound < $1.lowerBound }
        for i in 1..<sorted.count where sorted[i - 1].upperBound > sorted[i].lowerBound { throw MacUpdateError.invalidArchive }
        return records
    }

    private static func safePath(_ name: String) -> Bool {
        guard !name.hasPrefix("/"), !name.contains("\\"), !name.contains(":"),
              !name.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }),
              name == name.precomposedStringWithCanonicalMapping else { return false }
        let components = name.split(separator: "/", omittingEmptySubsequences: false)
        guard !components.isEmpty else { return false }
        for (i, component) in components.enumerated() {
            if component.isEmpty && i == components.count - 1 { continue }
            if component.isEmpty || component == "." || component == ".." { return false }
        }
        // ditto's optional AppleDouble records are ignored during extraction.
        return name == "BareRemote.app/" || name.hasPrefix("BareRemote.app/") ||
            name == "__MACOSX/" || name.hasPrefix("__MACOSX/BareRemote.app/") || name == "__MACOSX/._BareRemote.app"
    }
}
