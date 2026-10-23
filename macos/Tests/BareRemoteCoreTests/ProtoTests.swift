import Foundation
import XCTest
@testable import BareRemoteCore

final class ProtoTests: XCTestCase {
    func testIndependentWireVectors() throws {
        let shortOK = try Proto.Writer().message(10, Proto.Writer().number(1, 23).number(2, 3)).build()
        XCTAssertEqual(shortOK, hex("520408171003"))
        let inputs = try Proto.Writer().message(90, Proto.Writer().text(1, "barehome://inputs")).build()
        XCTAssertEqual(inputs, hex("d205130a1162617265686f6d653a2f2f696e70757473"))
        let configuration = try Proto.Writer().number(1, 2).number(2, 200)
            .message(30, Proto.Writer().message(1, Proto.Writer().number(1, 3).number(2, 6)).number(2, 1)).build()
        XCTAssertEqual(configuration, hex("080210c801f201080a04080310061001"))
    }

    func testUnknownFieldsAndDuplicateSemantics() throws {
        let message = try Proto.parse(hex("f50501020304f9050102030405060708a206021807520408171003"))
        XCTAssertEqual(try message.message(10).integer(1), 23)
        XCTAssertEqual(try message.message(100).integer(3), 7)
        XCTAssertEqual(try Proto.parse(hex("08010802")).integer(1), 2)
        XCTAssertEqual(try Proto.parse(hex("0800")).integer(2, default: 9), 9)
        XCTAssertFalse(try Proto.parse(Data()).has(1))
    }

    func testUnsignedVarintsAtBoundaries() throws {
        let values: [UInt64] = [0, 1, 127, 128, 255, 16_384, UInt64(UInt32.max), UInt64(Int64.max), UInt64.max]
        for value in values {
            let encoded = Array(Proto.varint(value))
            var offset = 0
            XCTAssertEqual(try Proto.readVarint(encoded, offset: &offset), value)
            XCTAssertEqual(offset, encoded.count)
        }
        XCTAssertEqual(Proto.varint(128), hex("8001"))
        XCTAssertEqual(Proto.varint(UInt64.max), hex("ffffffffffffffffff01"))
        for malformed in ["80", "80808080808080808080", "ffffffffffffffffff02"] {
            var offset = 0
            XCTAssertThrowsError(try Proto.readVarint(Array(hex(malformed)), offset: &offset), malformed)
        }
    }

    func testMalformedFieldsAreRejected() {
        // Invalid tags, groups, truncation, overflowing integers and out-of-range tags.
        for malformed in ["00", "0f", "0b", "0800ff", "08ff", "0a05aa", "0901", "0d01",
                          "088080808080808080808002", "ffffffff1f00"] {
            XCTAssertThrowsError(try Proto.parse(hex(malformed)), malformed)
        }
        XCTAssertThrowsError(try Proto.parse(hex("0a00")).integer(1))
    }

    func testResourceBoundsApplyToInboundAndOutboundMessages() throws {
        XCTAssertThrowsError(try Proto.Writer().number(0, 1))
        XCTAssertThrowsError(try Proto.Writer().number(1 << 29, 1))
        XCTAssertThrowsError(try Proto.Writer().bytes(1, Data(repeating: 0, count: Proto.maxFrame + 1)))
        XCTAssertThrowsError(try Proto.frame(Data()))
        XCTAssertThrowsError(try Proto.frame(Data(repeating: 0, count: Proto.maxFrame + 1)))
        XCTAssertThrowsError(try Proto.parse(Data(repeating: 0, count: Proto.maxFrame + 1)))
        let tooManyFields = Data(repeating: [UInt8(0x08), 0x01], count: 4_097)
        XCTAssertThrowsError(try Proto.parse(tooManyFields))
        let largestFrame = Data(repeating: 42, count: Proto.maxFrame)
        XCTAssertEqual(try Proto.frame(largestFrame), Proto.varint(UInt64(Proto.maxFrame)) + largestFrame)
    }

    func testPairingChallengeIndependentSHA256Vector() throws {
        let expected = hex("54794e89b7f113d8b4f45c7a648ec446bb96af58d13fe390c3e55cc5e4059121")
        for code in ["54ab12", "54AB12"] {
            XCTAssertEqual(try secret(code), expected)
        }
        XCTAssertEqual(try TVPairing.secret(clientModulus: hex("0080ff"), clientExponent: hex("00010001"),
                                            serverModulus: hex("00deadbeef"), serverExponent: hex("0003"), code: "54AB12"), expected,
                       "DER integer sign padding must not change the pairing challenge")
        XCTAssertThrowsError(try TVPairing.secret(clientModulus: Data([0]), clientExponent: hex("010001"),
                                                 serverModulus: hex("deadbeef"), serverExponent: hex("03"), code: "54AB12"))
        // A plausible six-hex-digit code with the wrong check byte must fail locally.
        for code in ["", "12345", "1234567", "12 456", "zz1234", "55ab12"] {
            XCTAssertThrowsError(try secret(code), code)
        }
    }

    private func secret(_ code: String) throws -> Data {
        try TVPairing.secret(clientModulus: hex("80ff"), clientExponent: hex("010001"),
                             serverModulus: hex("deadbeef"), serverExponent: hex("03"), code: code)
    }
}

private func hex(_ value: String) -> Data {
    var bytes = Data()
    var index = value.startIndex
    while index < value.endIndex {
        let next = value.index(index, offsetBy: 2)
        bytes.append(UInt8(value[index..<next], radix: 16)!)
        index = next
    }
    return bytes
}

private extension Data {
    init(repeating bytes: [UInt8], count: Int) {
        self.init()
        reserveCapacity(bytes.count * count)
        for _ in 0..<count { append(contentsOf: bytes) }
    }
}
