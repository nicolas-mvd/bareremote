import Foundation
import CryptoKit
import Security
import Darwin

public enum PictureStateError: LocalizedError {
    case localNetwork, socket, timeout, cancelled
    public var errorDescription: String? {
        switch self {
        case .localNetwork: return "Picture sync needs a private IPv4 network and BareHome 1.2 or newer."
        case .socket: return "Could not open the temporary picture reply connection."
        case .timeout: return "No picture confirmation received. Sync before trying again."
        case .cancelled: return "Picture request cancelled."
        }
    }
}

public enum PictureStateWire {
    public static func isPrivateIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let numbers = parts.compactMap { UInt8($0) }
        guard numbers.count == 4, zip(parts, numbers).allSatisfy({ String($0.1) == $0.0 }) else { return false }
        return numbers[0] == 10 || (numbers[0] == 172 && (16...31).contains(numbers[1]))
            || (numbers[0] == 192 && numbers[1] == 168)
    }

    public static func request(toggle: Bool, host: String, port: UInt16, token: Data) throws -> String {
        guard isPrivateIPv4(host), port >= 1024, token.count == 32 else { throw PictureStateError.localNetwork }
        return "barehome://picture/\(toggle ? "toggle" : "state")?reply=\(host)&port=\(port)&token=\(hex(token))"
    }

    public static func verify(_ reply: Data, token: Data) -> String? {
        guard token.count == 32, (1...160).contains(reply.count), reply.allSatisfy({ (0x20...0x7e).contains($0) }),
              let text = String(data: reply, encoding: .ascii) else { return nil }
        let parts = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == "BH1", ["day", "night", "custom", "unavailable", "error"].contains(parts[2]),
              let requestID = unhex(parts[1]), let receivedMAC = unhex(parts[3]),
              equal(Data(SHA256.hash(data: token)), requestID) else { return nil }
        let prefix = Data(parts.prefix(3).joined(separator: "|").utf8)
        let mac = Data(HMAC<SHA256>.authenticationCode(for: prefix, using: SymmetricKey(data: token)))
        return equal(mac, receivedMAC) ? parts[2] : nil
    }

    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    static func unhex(_ text: String) -> Data? {
        let bytes = Array(text.utf8)
        guard bytes.count == 64, bytes.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { return nil }
        return Data(stride(from: 0, to: 64, by: 2).map { index in
            func nibble(_ byte: UInt8) -> UInt8 { byte <= 57 ? byte - 48 : byte - 87 }
            return nibble(bytes[index]) << 4 | nibble(bytes[index + 1])
        })
    }
    static func equal(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}

public enum BareHomeLink {
    public static func validate(_ uri: String) throws {
        if uri == "barehome://inputs" || uri == "barehome://dog/setup" { return }
        guard uri.utf8.count <= 256 else { throw PictureStateError.localNetwork }
        let pieces = uri.components(separatedBy: "?")
        guard pieces.count == 2, ["barehome://picture/state", "barehome://picture/toggle",
                                   "barehome://dog/state", "barehome://dog/start", "barehome://dog/stop"].contains(pieces[0]) else {
            throw PictureStateError.localNetwork
        }
        let fields = pieces[1].components(separatedBy: "&")
        guard fields.count == 3, fields[0].hasPrefix("reply="), fields[1].hasPrefix("port="), fields[2].hasPrefix("token=") else {
            throw PictureStateError.localNetwork
        }
        let host = String(fields[0].dropFirst(6))
        let portText = String(fields[1].dropFirst(5))
        let tokenText = String(fields[2].dropFirst(6))
        guard PictureStateWire.isPrivateIPv4(host), let port = UInt16(portText), port >= 1024,
              String(port) == portText, PictureStateWire.unhex(tokenText) != nil else { throw PictureStateError.localNetwork }
    }
}

/// A single temporary UDP reply socket. No polling and no automatic toggle retries.
public final class PictureStateRequest {
    private let lock = NSLock()
    private let cancellationLock = NSLock()
    private var cancellationRequested = false
    private var descriptor: Int32
    private let token: Data
    private let host: String
    private let peer: in_addr_t
    private let port: UInt16
    private let deadline: TimeInterval

    public init(localAddress: String, peer: String) throws {
        deadline = ProcessInfo.processInfo.systemUptime + 4
        guard PictureStateWire.isPrivateIPv4(localAddress), PictureStateWire.isPrivateIPv4(peer) else {
            throw PictureStateError.localNetwork
        }
        var tokenBytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, tokenBytes.count, &tokenBytes) == errSecSuccess else {
            throw PictureStateError.socket
        }
        token = Data(tokenBytes)
        host = localAddress
        var peerAddress = in_addr()
        guard inet_pton(AF_INET, peer, &peerAddress) == 1 else { throw PictureStateError.localNetwork }
        self.peer = peerAddress.s_addr
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw PictureStateError.socket }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        inet_pton(AF_INET, localAddress, &address.sin_addr)
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else { Darwin.close(fd); throw PictureStateError.socket }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        let selectedPort = UInt16(bigEndian: address.sin_port)
        guard named == 0, selectedPort >= 1024 else { Darwin.close(fd); throw PictureStateError.socket }
        descriptor = fd
        port = selectedPort
    }

    /// Owns an already bound IPv4 socket after successful initialization. This
    /// internal fixture path leaves the public private-network checks intact.
    init(socket: Int32, token: Data, peer: String, timeout: TimeInterval) throws {
        guard socket >= 0, token.count == 32, timeout > 0, timeout <= 4 else {
            throw PictureStateError.socket
        }
        var peerAddress = in_addr()
        guard inet_pton(AF_INET, peer, &peerAddress) == 1 else { throw PictureStateError.localNetwork }
        var address = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socket, $0, &length) }
        }
        let selectedPort = UInt16(bigEndian: address.sin_port)
        guard named == 0, address.sin_family == sa_family_t(AF_INET), selectedPort >= 1024 else {
            throw PictureStateError.socket
        }
        var hostBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address.sin_addr, &hostBuffer, socklen_t(hostBuffer.count)) != nil else {
            throw PictureStateError.socket
        }
        self.descriptor = socket
        self.token = token
        self.host = String(cString: hostBuffer)
        self.peer = peerAddress.s_addr
        self.port = selectedPort
        self.deadline = ProcessInfo.processInfo.systemUptime + timeout
    }

    public func requestURL(toggle: Bool) throws -> String {
        try PictureStateWire.request(toggle: toggle, host: host, port: port, token: token)
    }

    public func wait() throws -> String {
        defer { close() }
        while ProcessInfo.processInfo.systemUptime < deadline {
            try checkCancellation()
            lock.lock()
            let fd = descriptor
            guard fd >= 0 else { lock.unlock(); throw PictureStateError.cancelled }
            // Poll in short bounded slices. The lock prevents descriptor reuse during close.
            var item = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let remaining = (deadline - ProcessInfo.processInfo.systemUptime) * 1000
            let milliseconds = Int32(min(100, max(1, ceil(remaining))))
            let available = Darwin.poll(&item, 1, milliseconds)
            if available > 0, item.revents & Int16(POLLIN) != 0 {
                var source = sockaddr_in()
                var size = socklen_t(MemoryLayout<sockaddr_in>.size)
                var buffer = [UInt8](repeating: 0, count: 161)
                let count = withUnsafeMutablePointer(to: &source) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        recvfrom(fd, &buffer, buffer.count, MSG_DONTWAIT, $0, &size)
                    }
                }
                lock.unlock()
                try checkCancellation()
                if count > 0, source.sin_family == sa_family_t(AF_INET), source.sin_addr.s_addr == peer,
                   let state = PictureStateWire.verify(Data(buffer.prefix(count)), token: token) { return state }
            } else { lock.unlock() }
        }
        throw PictureStateError.timeout
    }

    public func close() {
        // Publish cancellation before waiting for the polling lock, so its
        // reader cannot reacquire that lock and starve close for extra slices.
        cancellationLock.lock()
        cancellationRequested = true
        cancellationLock.unlock()
        lock.lock()
        let fd = descriptor
        descriptor = -1
        lock.unlock()
        if fd >= 0 { Darwin.close(fd) }
    }
    private func checkCancellation() throws {
        cancellationLock.lock()
        defer { cancellationLock.unlock() }
        if cancellationRequested { throw PictureStateError.cancelled }
    }
    deinit { close() }
}
