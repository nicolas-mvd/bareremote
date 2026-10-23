import Foundation
import XCTest
@testable import BareRemoteCore

final class DogStateTests: XCTestCase {
    private let token = Data(0..<32)
    // Generated independently with Python hashlib/hmac, not the implementation under test.
    private let requestID = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd"
    private let offMAC = "8f31657bd9bd7f7187508dee9bb4f1ed397fad1097713d0b8974d3e8bfa8b1ad"

    func testIndependentAuthenticatedReplies() {
        let vectors = [
            ("off", "8f31657bd9bd7f7187508dee9bb4f1ed397fad1097713d0b8974d3e8bfa8b1ad"),
            ("starting", "a009608c5bf9568014e6aa43db84df5af0ada39cd65d60ec5b7369a2f0926ebe"),
            ("active", "5791310f313bcd83266b3f9d6c86e902a2cee9787a77e85ca5f229d357839110"),
            ("restore_required", "e544e2b1e15ee30437defca257ba22385929c1eab2ba91e65839531bc6e8d219"),
            ("unavailable", "54f9a9fa9178230d6197775c0f4582a4a297091fab1c66b1e2b52847e8b8b378"),
            ("error", "6704afd0f230dedff601e18cc46f3fbb1115b3921b7ef98dc5a88d22c54c2586"),
            ("nsnow_access", "a1702bc1bb99f9038ecc14f7f78630a7487af2dc9e3aaaa082b11c69e174fbfd"),
            ("nsnow_menu", "9cad0b91291eda2512b1d51197c6ae25998c98fef07cabf9b252e2b823097336"),
            ("nsnow_channel", "fc8d3dcb84167f55b481f46005541ee9213a2b50fa69aa8df71ae710fad30091"),
            ("nsnow_player", "a30a700782aba47aed9d685b96f3615d09552589cb2f8e6e20d122a9514e580c"),
            ("nsnow_debug", "74e073aa5618d7eb24a5427212e8f41069704b8f6b13a4073ced969641c30258"),
            ("nsnow_target", "3b4c7ca8fa9fd737751936121f8ff9072a0d82a878b2e3919106c4efae987743"),
            ("nsnow_input", "c0eddece5fe09f8f29e43390c730d37e4747783837b3c9dd15c20adeae95aa21")
        ]
        for (state, mac) in vectors {
            let response = Data("BH2|\(requestID)|\(state)|\(mac)".utf8)
            XCTAssertEqual(DogStateWire.verify(response, token: token), state)
            XCTAssertNil(DogStateWire.verify(response, token: Data(repeating: 1, count: 32)),
                         "A reply must be bound to this request's fresh secret")
        }
    }

    func testForgedAndMalformedRepliesCannotConfirmState() {
        let valid = "BH2|\(requestID)|off|\(offMAC)"
        for malformed in ["", valid + "\n", valid + "|extra", valid.uppercased(),
                          valid.replacingOccurrences(of: "|off|", with: "|active|"),
                          valid.replacingOccurrences(of: "BH2", with: "BH1"),
                          "BH2|\(requestID)|off|" + String(offMAC.dropLast()) + "0",
                          "BH2|\(requestID)|unknown|\(offMAC)",
                          "BH2|\(requestID)|nsnow_unknown|f5cfccdc2d64a79e287edb494ec8fca2d672e3932dcf30d69d18b9f0b2c2808d",
                          "BH2|\(requestID)|nsnow_menu|a1702bc1bb99f9038ecc14f7f78630a7487af2dc9e3aaaa082b11c69e174fbfd",
                          String(repeating: "x", count: 177)] {
            XCTAssertNil(DogStateWire.verify(Data(malformed.utf8), token: token), malformed)
        }
        XCTAssertNil(DogStateWire.verify(Data(valid.utf8), token: Data(repeating: 0, count: 31)))
        XCTAssertNil(DogStateWire.verify(Data([0xff, 0, 0x80]), token: token))
    }

    func testOnlyFiniteRollbackFailuresHaveRestorationMessages() {
        let subjects = ["nsnow_access": "controls", "nsnow_menu": "TV EN VIVO",
                        "nsnow_channel": "select CANAL 10", "nsnow_player": "confirm CANAL 10",
                        "nsnow_debug": "USB debugging", "nsnow_target": "could not be identified",
                        "nsnow_input": "did not accept"]
        for (state, subject) in subjects {
            XCTAssertTrue(DogStateWire.isStartupFailure(state))
            let message = DogStateWire.startupFailureMessage(state)
            XCTAssertTrue(message?.contains(subject) == true)
            XCTAssertTrue(message?.contains("were restored and the helper is off") == true)
        }
        for state in ["error", "unknown", "restore_required", "off", "nsnow_unknown", "nsnow_access:details"] {
            XCTAssertFalse(DogStateWire.isStartupFailure(state))
            XCTAssertNil(DogStateWire.startupFailureMessage(state), "Other states must not imply startup rollback")
        }
    }

    func testDogSetupIsOneExactStaticLinkWithoutCallbackOrConsent() throws {
        try BareHomeLink.validate("barehome://dog/setup")
        for invalid in ["barehome://dog/setup?", "barehome://dog/setup?reply=192.168.1.4",
                        "barehome://dog/setup#fragment", "barehome://dog/setup/", "barehome://dog/%73etup",
                        "barehome://dog/Setup", "barehome://dog/anything"] {
            XCTAssertThrowsError(try BareHomeLink.validate(invalid), invalid)
        }
        XCTAssertThrowsError(try DogStateWire.request(action: "setup", host: "10.0.0.1", port: 12345, token: token),
                             "Setup must not become an arbitrary callback operation")
    }

    func testExactRequestURIsAndPrivateIPv4Validation() throws {
        let secret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        let state = "barehome://dog/state?reply=192.168.1.4&port=12345&token=\(secret)"
        XCTAssertEqual(try DogStateWire.request(action: "state", host: "192.168.1.4", port: 12_345, token: token), state)
        try BareHomeLink.validate(state)
        try BareHomeLink.validate("barehome://inputs")
        try BareHomeLink.validate(try DogStateWire.request(action: "stop", host: "10.0.1.4", port: 12345, token: token))
        XCTAssertThrowsError(try DogStateWire.request(action: "toggle", host: "10.0.1.4", port: 12345, token: token))
        try BareHomeLink.validate(try DogStateWire.request(action: "start", host: "10.0.1.4", port: 65_535, token: token))
        for host in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.4"] {
            XCTAssertTrue(DogStateWire.isPrivateIPv4(host), host)
        }
        for host in ["127.0.0.1", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.168.01.4", "10.1.2", "10.0.0.256", "10.0.0.1\n"] {
            XCTAssertFalse(DogStateWire.isPrivateIPv4(host), host)
            XCTAssertThrowsError(try DogStateWire.request(action: "start", host: host, port: 12_345, token: token))
        }
        for malformed in [state + "#fragment", state + "&extra=1", state.replacingOccurrences(of: "port=12345", with: "port=012345"),
                          state.replacingOccurrences(of: "port=12345", with: "port=80"),
                          state.replacingOccurrences(of: "reply=192.168.1.4&port=12345", with: "port=12345&reply=192.168.1.4"),
                          state.replacingOccurrences(of: secret, with: secret.uppercased()), "https://example.com/"] {
            XCTAssertThrowsError(try BareHomeLink.validate(malformed), malformed)
        }
        XCTAssertThrowsError(try DogStateWire.request(action: "state", host: "10.0.0.1", port: 1_023, token: token))
        XCTAssertThrowsError(try DogStateWire.request(action: "state", host: "10.0.0.1", port: 12_345, token: Data()))
    }
}
