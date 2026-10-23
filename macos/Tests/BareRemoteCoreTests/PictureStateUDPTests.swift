import Darwin
import Foundation
import XCTest
@testable import BareRemoteCore

final class PictureStateUDPTests: XCTestCase {
    private let token = Data(0..<32)
    // Independent Python hashlib/hmac vectors, shared with the wire regression.
    private let requestID = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd"
    private let dayMAC = "2bc56748e4ab67e5d3100672707885c78bd47578a8e5b713962250ae0ab32f51"
    private let nightMAC = "ec25732dbfb5835434bea4e9ac99b545f90763a5a090ec5660aaffcc6917646e"

    func testAuthenticatedOneShotReplyIgnoresAnEarlierForgeryAndClosesSocket() throws {
        let receiver = try LoopbackDatagramSocket()
        let sender = try LoopbackDatagramSocket()
        let request = try PictureStateRequest(socket: receiver.takeDescriptor(), token: token,
                                             peer: "127.0.0.1", timeout: 0.3)
        defer { request.close() }
        // A changed state with the original MAC is rejected before a valid reply arrives.
        try sender.send(Data("BH1|\(requestID)|night|\(dayMAC)".utf8), to: receiver.port)
        try sender.send(Data("BH1|\(requestID)|day|\(dayMAC)".utf8), to: receiver.port)
        XCTAssertEqual(try request.wait(), "day")
        XCTAssertThrowsError(try request.wait()) { error in
            guard case PictureStateError.cancelled = error else { return XCTFail("A one-shot request must close after its reply") }
        }
    }

    func testAuthenticatedReplyFromTheWrongAddressIsIgnoredUntilBoundedTimeout() throws {
        let receiver = try LoopbackDatagramSocket()
        let sender = try LoopbackDatagramSocket()
        let request = try PictureStateRequest(socket: receiver.takeDescriptor(), token: token,
                                             peer: "127.0.0.2", timeout: 0.2)
        defer { request.close() }
        // The payload is authentic, but its source is 127.0.0.1 rather than the pinned peer.
        try sender.send(Data("BH1|\(requestID)|night|\(nightMAC)".utf8), to: receiver.port)
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try request.wait()) { error in
            guard case PictureStateError.timeout = error else { return XCTFail("Wrong-source replies must never confirm a state") }
        }
        let elapsed = ProcessInfo.processInfo.systemUptime - began
        XCTAssertGreaterThanOrEqual(elapsed, 0.15)
        XCTAssertLessThan(elapsed, 0.6)
    }

    func testCloseCancelsBlockedUDPWaitWithinOnePollSlice() throws {
        let receiver = try LoopbackDatagramSocket()
        let request = try PictureStateRequest(socket: receiver.takeDescriptor(), token: token,
                                             peer: "127.0.0.1", timeout: 4)
        defer { request.close() }
        let started = DispatchSemaphore(value: 0)
        let completed = expectation(description: "UDP wait exits when explicitly closed")
        DispatchQueue.global().async {
            started.signal()
            do {
                _ = try request.wait()
                XCTFail("A closed request must not confirm picture state")
            } catch PictureStateError.cancelled { }
            catch { XCTFail("Unexpected cancellation error: \(error)") }
            completed.fulfill()
        }
        XCTAssertEqual(started.wait(timeout: .now() + 1), .success)
        Thread.sleep(forTimeInterval: 0.02)
        let began = ProcessInfo.processInfo.systemUptime
        request.close()
        wait(for: [completed], timeout: 0.15)
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 0.15,
                          "Close must finish within the 100ms poll slice plus scheduler overhead")
    }

    func testPublicInitializerStillRejectsLoopbackAndPublicAddresses() {
        for host in ["127.0.0.1", "8.8.8.8"] {
            XCTAssertThrowsError(try PictureStateRequest(localAddress: host, peer: "192.168.1.40"))
            XCTAssertThrowsError(try PictureStateRequest(localAddress: "192.168.1.4", peer: host))
        }
    }
}

private final class LoopbackDatagramSocket {
    private var descriptor: Int32
    let port: UInt16

    init() throws {
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw PictureStateError.socket }
        descriptor = fd
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        inet_pton(AF_INET, "127.0.0.1", &address.sin_addr)
        let result = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else { Darwin.close(fd); throw PictureStateError.socket }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard named == 0 else { Darwin.close(fd); throw PictureStateError.socket }
        port = UInt16(bigEndian: address.sin_port)
    }

    func takeDescriptor() -> Int32 {
        let value = descriptor
        descriptor = -1
        return value
    }

    func send(_ data: Data, to port: UInt16) throws {
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        inet_pton(AF_INET, "127.0.0.1", &address.sin_addr)
        let sent = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { target in
                data.withUnsafeBytes { bytes in
                    sendto(descriptor, bytes.baseAddress, bytes.count, 0, target, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == data.count else { throw PictureStateError.socket }
    }

    deinit { if descriptor >= 0 { Darwin.close(descriptor) } }
}
