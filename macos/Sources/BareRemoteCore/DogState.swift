import Foundation
import CryptoKit
import Security
import Darwin

public enum DogStateError: LocalizedError {
    case localNetwork, socket, timeout, cancelled
    public var errorDescription: String? {
        switch self {
        case .localNetwork: return "Dog Mode needs a private IPv4 network and compatible BareHome."
        case .socket: return "Could not open the temporary Dog Mode reply connection."
        case .timeout: return "No Dog Mode confirmation received. Check status before trying again."
        case .cancelled: return "Dog Mode request cancelled."
        }
    }
}

public enum DogStateWire {
    public static func isPrivateIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let numbers = parts.compactMap { UInt8($0) }
        guard numbers.count == 4, zip(parts, numbers).allSatisfy({ String($0.1) == $0.0 }) else { return false }
        return numbers[0] == 10 || (numbers[0] == 172 && (16...31).contains(numbers[1]))
            || (numbers[0] == 192 && numbers[1] == 168)
    }

    public static func request(action: String, host: String, port: UInt16, token: Data) throws -> String {
        guard ["state", "start", "stop"].contains(action), isPrivateIPv4(host), port >= 1024, token.count == 32 else { throw DogStateError.localNetwork }
        return "barehome://dog/\(action)?reply=\(host)&port=\(port)&token=\(hex(token))"
    }

    public static func verify(_ reply: Data, token: Data) -> String? {
        guard token.count == 32, (1...176).contains(reply.count), reply.allSatisfy({ (0x20...0x7e).contains($0) }),
              let text = String(data: reply, encoding: .ascii) else { return nil }
        let parts = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == "BH2",
              (["off", "starting", "active", "restore_required", "unavailable", "error"].contains(parts[2]) || isStartupFailure(parts[2])),
              let requestID = unhex(parts[1]), let receivedMAC = unhex(parts[3]),
              equal(Data(SHA256.hash(data: token)), requestID) else { return nil }
        let prefix = Data(parts.prefix(3).joined(separator: "|").utf8)
        let mac = Data(HMAC<SHA256>.authenticationCode(for: prefix, using: SymmetricKey(data: token)))
        return equal(mac, receivedMAC) ? parts[2] : nil
    }

    public static func isStartupFailure(_ state: String) -> Bool {
        ["nsnow_access", "nsnow_menu", "nsnow_channel", "nsnow_player", "nsnow_debug",
         "nsnow_target", "nsnow_input"].contains(state)
    }

    /// Only these fixed authenticated TV states assert successful rollback and helper removal.
    public static func startupFailureMessage(_ state: String) -> String? {
        let detail: String
        switch state {
        case "nsnow_access": detail = "NS NOW’s controls could not be accessed."
        case "nsnow_menu": detail = "Could not open TV EN VIVO in NS NOW."
        case "nsnow_channel": detail = "Could not select CANAL 10 in NS NOW."
        case "nsnow_target": detail = "CANAL 10 could not be identified in NS NOW’s channel list."
        case "nsnow_input": detail = "The TV did not accept the CANAL 10 selection."
        case "nsnow_player": detail = "Could not confirm CANAL 10 in NS NOW."
        case "nsnow_debug": detail = "Turn off USB debugging on the TV."
        default: return nil
        }
        return detail + " Previous TV energy settings were restored and the helper is off. Check status before trying again."
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

/// A single temporary UDP reply socket. No polling and no automatic mutation retries.
public final class DogStateRequest {
    private let lock = NSLock()
    private let cancellationLock = NSLock()
    private var cancellationRequested = false
    private var descriptor: Int32
    private let token: Data
    private let host: String
    private let peer: in_addr_t
    private let port: UInt16
    private let deadline: TimeInterval

    public init(localAddress: String, peer: String, timeout: TimeInterval = 8) throws {
        guard timeout > 0, timeout <= 35 else { throw DogStateError.socket }
        deadline = ProcessInfo.processInfo.systemUptime + timeout
        guard DogStateWire.isPrivateIPv4(localAddress), DogStateWire.isPrivateIPv4(peer) else {
            throw DogStateError.localNetwork
        }
        var tokenBytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, tokenBytes.count, &tokenBytes) == errSecSuccess else {
            throw DogStateError.socket
        }
        token = Data(tokenBytes)
        host = localAddress
        var peerAddress = in_addr()
        guard inet_pton(AF_INET, peer, &peerAddress) == 1 else { throw DogStateError.localNetwork }
        self.peer = peerAddress.s_addr
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw DogStateError.socket }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        inet_pton(AF_INET, localAddress, &address.sin_addr)
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else { Darwin.close(fd); throw DogStateError.socket }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        let selectedPort = UInt16(bigEndian: address.sin_port)
        guard named == 0, selectedPort >= 1024 else { Darwin.close(fd); throw DogStateError.socket }
        descriptor = fd
        port = selectedPort
    }

    /// Owns an already bound IPv4 socket after successful initialization. This
    /// internal fixture path leaves the public private-network checks intact.
    init(socket: Int32, token: Data, peer: String, timeout: TimeInterval) throws {
        guard socket >= 0, token.count == 32, timeout > 0, timeout <= 35 else {
            throw DogStateError.socket
        }
        var peerAddress = in_addr()
        guard inet_pton(AF_INET, peer, &peerAddress) == 1 else { throw DogStateError.localNetwork }
        var address = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socket, $0, &length) }
        }
        let selectedPort = UInt16(bigEndian: address.sin_port)
        guard named == 0, address.sin_family == sa_family_t(AF_INET), selectedPort >= 1024 else {
            throw DogStateError.socket
        }
        var hostBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address.sin_addr, &hostBuffer, socklen_t(hostBuffer.count)) != nil else {
            throw DogStateError.socket
        }
        self.descriptor = socket
        self.token = token
        self.host = String(cString: hostBuffer)
        self.peer = peerAddress.s_addr
        self.port = selectedPort
        self.deadline = ProcessInfo.processInfo.systemUptime + timeout
    }

    public func requestURL(action: String) throws -> String {
        try DogStateWire.request(action: action, host: host, port: port, token: token)
    }

    public func wait(waitForFinal: Bool = false, progress: ((String) -> Void)? = nil) throws -> String {
        defer { close() }
        while ProcessInfo.processInfo.systemUptime < deadline {
            try checkCancellation()
            lock.lock()
            let fd = descriptor
            guard fd >= 0 else { lock.unlock(); throw DogStateError.cancelled }
            // Poll in short bounded slices. The lock prevents descriptor reuse during close.
            var item = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let remaining = (deadline - ProcessInfo.processInfo.systemUptime) * 1000
            let milliseconds = Int32(min(100, max(1, ceil(remaining))))
            let available = Darwin.poll(&item, 1, milliseconds)
            if available > 0, item.revents & Int16(POLLIN) != 0 {
                var source = sockaddr_in()
                var size = socklen_t(MemoryLayout<sockaddr_in>.size)
                var buffer = [UInt8](repeating: 0, count: 177)
                let count = withUnsafeMutablePointer(to: &source) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        recvfrom(fd, &buffer, buffer.count, MSG_DONTWAIT, $0, &size)
                    }
                }
                lock.unlock()
                try checkCancellation()
                if count > 0, source.sin_family == sa_family_t(AF_INET), source.sin_addr.s_addr == peer,
                   let state = DogStateWire.verify(Data(buffer.prefix(count)), token: token) {
                    if waitForFinal && state == "starting" { progress?(state) }
                    else { return state }
                }
            } else { lock.unlock() }
        }
        throw DogStateError.timeout
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
        if cancellationRequested { throw DogStateError.cancelled }
    }
    deinit { close() }
}
