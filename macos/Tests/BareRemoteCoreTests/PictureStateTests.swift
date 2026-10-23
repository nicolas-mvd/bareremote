import Foundation
import XCTest
@testable import BareRemoteCore

final class PictureStateTests: XCTestCase {
    private let token = Data(0..<32)
    // Generated independently with Python hashlib/hmac, not the implementation under test.
    private let requestID = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd"
    private let dayMAC = "2bc56748e4ab67e5d3100672707885c78bd47578a8e5b713962250ae0ab32f51"

    func testIndependentAuthenticatedReplies() {
        let vectors = [
            ("day", "2bc56748e4ab67e5d3100672707885c78bd47578a8e5b713962250ae0ab32f51"),
            ("night", "ec25732dbfb5835434bea4e9ac99b545f90763a5a090ec5660aaffcc6917646e"),
            ("custom", "6fce8cf722556bfca24a8a1e12ee7b30090f83d0d3e42c96ca20479d5f3d33b5"),
            ("unavailable", "5e7f0de8bff897b201253ecd099b98e7840bcaeb7b4c8539346f17e407c553ca"),
            ("error", "b1f574ca1a63607b0d9c2a52b2a8d9a444d79ec6a2831087abe697315d46eac9")
        ]
        for (state, mac) in vectors {
            let response = Data("BH1|\(requestID)|\(state)|\(mac)".utf8)
            XCTAssertEqual(PictureStateWire.verify(response, token: token), state)
            XCTAssertNil(PictureStateWire.verify(response, token: Data(repeating: 1, count: 32)),
                         "A reply must be bound to this request's fresh secret")
        }
    }

    func testForgedAndMalformedRepliesCannotConfirmState() {
        let valid = "BH1|\(requestID)|day|\(dayMAC)"
        for malformed in ["", valid + "\n", valid + "|extra", valid.uppercased(),
                          valid.replacingOccurrences(of: "|day|", with: "|night|"),
                          valid.replacingOccurrences(of: "BH1", with: "BH2"),
                          "BH1|\(requestID)|day|" + String(dayMAC.dropLast()) + "0",
                          "BH1|\(requestID)|unknown|\(dayMAC)", String(repeating: "x", count: 161)] {
            XCTAssertNil(PictureStateWire.verify(Data(malformed.utf8), token: token), malformed)
        }
        XCTAssertNil(PictureStateWire.verify(Data(valid.utf8), token: Data(repeating: 0, count: 31)))
        XCTAssertNil(PictureStateWire.verify(Data([0xff, 0, 0x80]), token: token))
    }

    func testExactRequestURIsAndPrivateIPv4Validation() throws {
        let secret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        let state = "barehome://picture/state?reply=192.168.1.4&port=12345&token=\(secret)"
        XCTAssertEqual(try PictureStateWire.request(toggle: false, host: "192.168.1.4", port: 12_345, token: token), state)
        try BareHomeLink.validate(state)
        try BareHomeLink.validate("barehome://inputs")
        try BareHomeLink.validate(try PictureStateWire.request(toggle: true, host: "10.0.1.4", port: 65_535, token: token))
        for host in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.4"] {
            XCTAssertTrue(PictureStateWire.isPrivateIPv4(host), host)
        }
        for host in ["127.0.0.1", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.168.01.4", "10.1.2", "10.0.0.256", "10.0.0.1\n"] {
            XCTAssertFalse(PictureStateWire.isPrivateIPv4(host), host)
            XCTAssertThrowsError(try PictureStateWire.request(toggle: true, host: host, port: 12_345, token: token))
        }
        for malformed in [state + "#fragment", state + "&extra=1", state.replacingOccurrences(of: "port=12345", with: "port=012345"),
                          state.replacingOccurrences(of: "port=12345", with: "port=80"),
                          state.replacingOccurrences(of: "reply=192.168.1.4&port=12345", with: "port=12345&reply=192.168.1.4"),
                          state.replacingOccurrences(of: secret, with: secret.uppercased()), "https://example.com/"] {
            XCTAssertThrowsError(try BareHomeLink.validate(malformed), malformed)
        }
        XCTAssertThrowsError(try PictureStateWire.request(toggle: false, host: "10.0.0.1", port: 1_023, token: token))
        XCTAssertThrowsError(try PictureStateWire.request(toggle: false, host: "10.0.0.1", port: 12_345, token: Data()))
    }
}
