import Darwin
import Foundation
import XCTest
@testable import BareRemoteCore

final class DogStateUDPTests: XCTestCase {
    private let token = Data(0..<32)
    // Independent Python hashlib/hmac vectors, shared with the wire regression.
    private let requestID = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd"
    private let offMAC = "8f31657bd9bd7f7187508dee9bb4f1ed397fad1097713d0b8974d3e8bfa8b1ad"
    private let activeMAC = "5791310f313bcd83266b3f9d6c86e902a2cee9787a77e85ca5f229d357839110"

    func testStartProgressRequiresFinalReplyAndQueryCanReturnStarting() throws {
        let starting = Data("BH2|\(requestID)|starting|a009608c5bf9568014e6aa43db84df5af0ada39cd65d60ec5b7369a2f0926ebe".utf8)
        let receiver = try DogLoopbackDatagramSocket()
        let sender = try DogLoopbackDatagramSocket()
        let request = try DogStateRequest(socket: receiver.takeDescriptor(), token: token,
                                         peer: "127.0.0.1", timeout: 0.3)
        try sender.send(starting, to: receiver.port)
        try sender.send(Data("BH2|\(requestID)|active|\(activeMAC)".utf8), to: receiver.port)
        var progress: [String] = []
        XCTAssertEqual(try request.wait(waitForFinal: true) { progress.append($0) }, "active")
        XCTAssertEqual(progress, ["starting"])
        XCTAssertThrowsError(try request.wait())

        let querySocket = try DogLoopbackDatagramSocket()
        let query = try DogStateRequest(socket: querySocket.takeDescriptor(), token: token,
                                       peer: "127.0.0.1", timeout: 0.3)
        try sender.send(starting, to: querySocket.port)
        XCTAssertEqual(try query.wait(), "starting", "A state query returns the current starting snapshot immediately")
    }

    func testStartingWithoutCompletionTimesOutAndLongStartCanCancel() throws {
        let receiver = try DogLoopbackDatagramSocket()
        let sender = try DogLoopbackDatagramSocket()
        let request = try DogStateRequest(socket: receiver.takeDescriptor(), token: token,
                                         peer: "127.0.0.1", timeout: 0.15)
        try sender.send(Data("BH2|\(requestID)|starting|a009608c5bf9568014e6aa43db84df5af0ada39cd65d60ec5b7369a2f0926ebe".utf8), to: receiver.port)
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try request.wait(waitForFinal: true)) { error in
            guard case DogStateError.timeout = error else { return XCTFail("Progress cannot confirm completion") }
        }
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 0.5)
        let longSocket = try DogLoopbackDatagramSocket()
        let longRequest = try DogStateRequest(socket: longSocket.takeDescriptor(), token: token,
                                             peer: "127.0.0.1", timeout: 35)
        longRequest.close()
        XCTAssertThrowsError(try longRequest.wait(waitForFinal: true)) { error in
            guard case DogStateError.cancelled = error else { return XCTFail("Long Start must remain cancellable") }
        }
    }

    func testStartCanFinishWithAuthenticatedRollbackFailure() throws {
        let receiver = try DogLoopbackDatagramSocket()
        let sender = try DogLoopbackDatagramSocket()
        let request = try DogStateRequest(socket: receiver.takeDescriptor(), token: token,
                                         peer: "127.0.0.1", timeout: 0.3)
        try sender.send(Data("BH2|\(requestID)|starting|a009608c5bf9568014e6aa43db84df5af0ada39cd65d60ec5b7369a2f0926ebe".utf8), to: receiver.port)
        try sender.send(Data("BH2|\(requestID)|nsnow_channel|fc8d3dcb84167f55b481f46005541ee9213a2b50fa69aa8df71ae710fad30091".utf8), to: receiver.port)
        var progress: [String] = []
        XCTAssertEqual(try request.wait(waitForFinal: true) { progress.append($0) }, "nsnow_channel")
        XCTAssertEqual(progress, ["starting"])
        XCTAssertThrowsError(try request.wait(), "Final failure closes the one-shot reply socket")
    }

    func testAuthenticatedOneShotReplyIgnoresAnEarlierForgeryAndClosesSocket() throws {
        let receiver = try DogLoopbackDatagramSocket()
        let sender = try DogLoopbackDatagramSocket()
        let request = try DogStateRequest(socket: receiver.takeDescriptor(), token: token,
                                             peer: "127.0.0.1", timeout: 0.3)
        defer { request.close() }
        // A changed state with the original MAC is rejected before a valid reply arrives.
        try sender.send(Data("BH2|\(requestID)|active|\(offMAC)".utf8), to: receiver.port)
        try sender.send(Data("BH2|\(requestID)|off|\(offMAC)".utf8), to: receiver.port)
        XCTAssertEqual(try request.wait(), "off")
        XCTAssertThrowsError(try request.wait()) { error in
            guard case DogStateError.cancelled = error else { return XCTFail("A one-shot request must close after its reply") }
        }
    }

    func testAuthenticatedReplyFromTheWrongAddressIsIgnoredUntilBoundedTimeout() throws {
        let receiver = try DogLoopbackDatagramSocket()
        let sender = try DogLoopbackDatagramSocket()
        let request = try DogStateRequest(socket: receiver.takeDescriptor(), token: token,
                                             peer: "127.0.0.2", timeout: 0.2)
        defer { request.close() }
        // The payload is authentic, but its source is 127.0.0.1 rather than the pinned peer.
        try sender.send(Data("BH2|\(requestID)|active|\(activeMAC)".utf8), to: receiver.port)
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try request.wait()) { error in
            guard case DogStateError.timeout = error else { return XCTFail("Wrong-source replies must never confirm a state") }
        }
        let elapsed = ProcessInfo.processInfo.systemUptime - began
        XCTAssertGreaterThanOrEqual(elapsed, 0.15)
        XCTAssertLessThan(elapsed, 0.6)
    }

    func testCloseCancelsBlockedUDPWaitWithinOnePollSlice() throws {
        let receiver = try DogLoopbackDatagramSocket()
        let request = try DogStateRequest(socket: receiver.takeDescriptor(), token: token,
                                             peer: "127.0.0.1", timeout: 4)
        defer { request.close() }
        let started = DispatchSemaphore(value: 0)
        let completed = expectation(description: "UDP wait exits when explicitly closed")
        DispatchQueue.global().async {
            started.signal()
            do {
                _ = try request.wait()
                XCTFail("A closed request must not confirm picture state")
            } catch DogStateError.cancelled { }
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
            XCTAssertThrowsError(try DogStateRequest(localAddress: host, peer: "192.168.1.40"))
            XCTAssertThrowsError(try DogStateRequest(localAddress: "192.168.1.4", peer: host))
        }
    }
}

private final class DogLoopbackDatagramSocket {
    private var descriptor: Int32
    let port: UInt16

    init() throws {
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw DogStateError.socket }
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
        guard result == 0 else { Darwin.close(fd); throw DogStateError.socket }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard named == 0 else { Darwin.close(fd); throw DogStateError.socket }
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
        guard sent == data.count else { throw DogStateError.socket }
    }

    deinit { if descriptor >= 0 { Darwin.close(descriptor) } }
}
