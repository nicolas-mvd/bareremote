import Foundation
import XCTest
@testable import BareRemoteCore

final class WidgetCommandTests: XCTestCase {
    func testWidgetControlsOnlyTargetTheSavedTVAndFixedVocabulary() throws {
        let keys = [19, 20, 21, 22, 23, 4, 3, 24, 25, 164, 26, 85]
        for code in keys {
            XCTAssertEqual(try parse("bareremote://widget/key?code=\(code)").action, .key(code))
        }
        XCTAssertEqual(try parse("bareremote://widget/sync").action, .sync)
        XCTAssertEqual(try parse("bareremote://widget/inputs").action, .inputs)
        XCTAssertEqual(try parse("bareremote://widget/toggle").action, .toggle)
        XCTAssertEqual(try parse("bareremote://widget/open").action, .open)
        for argument in ["host=192.168.1.9", "uri=barehome://inputs", "certificate=123", "code=178"] {
            XCTAssertThrowsError(try parse("bareremote://widget/key?\(argument)"))
        }
    }

    func testEveryStaticLinkTapRemainsAnIndependentUserAction() throws {
        let url = try RemoteShortcutAction.key(24).url()
        let first = try RemoteShortcutCommand(url: url)
        let second = try RemoteShortcutCommand(url: url)
        XCTAssertEqual(first.action, .key(24))
        XCTAssertEqual(second.action, .key(24))
        XCTAssertNil(first.deliveryIdentifier)
        XCTAssertNil(second.deliveryIdentifier)
    }

    func testPerExecutionIdentifiersSurviveRoutingWithoutBeingRequiredByStaticLinks() throws {
        let identifier = UUID(uuidString: "12345678-1234-4234-8234-123456789abc")!
        let generated = try RemoteShortcutAction.inputs.url(deliveryIdentifier: identifier)
        let command = try RemoteShortcutCommand(url: generated)
        XCTAssertEqual(command.action, .inputs)
        XCTAssertEqual(command.deliveryIdentifier, identifier)
        XCTAssertEqual(try RemoteShortcutAction(url: generated), .inputs)
        XCTAssertEqual(try parse("bareremote://widget/key?id=12345678-1234-4234-8234-123456789ABC&code=23").deliveryIdentifier,
                       identifier)
        XCTAssertNotEqual(try RemoteShortcutAction.key(23).url(deliveryIdentifier: UUID()),
                          try RemoteShortcutAction.key(23).url(deliveryIdentifier: UUID()))
    }

    func testUntrustedOriginsAndAmbiguousURIFormsFailClosed() throws {
        let invalid = [
            "https://widget/key?code=23", "BAREREMOTE://widget/key?code=23",
            "bareremote://other/key?code=23", "bareremote://WIDGET/key?code=23",
            "bareremote://user@widget/key?code=23", "bareremote://widget:80/key?code=23",
            "bareremote:///widget/key?code=23", "bareremote://widget/key/?code=23",
            "bareremote://widget/KEY?code=23", "bareremote://widget/../key?code=23",
            "bareremote://widget/%6bey?code=23", "bareremote://%77idget/key?code=23",
            "bareremote://widget/key?code=%32%33", "bareremote://widget/key?%63ode=23",
            "bareremote://widget/key?code=23#", "bareremote://widget/key?code=23#open",
            "bareremote://widget/key?code=23&", "bareremote://widget/key?code=23&&id=a",
            "bareremote://widget/open?", "bareremote://widget/open?code=23",
            "bareremote://widget/sync?url=https://example.com", "bareremote://widget/unknown"
        ]
        for value in invalid { XCTAssertThrowsError(try parse(value), value) }
        let relative = URL(string: "/key?code=23", relativeTo: URL(string: "bareremote://widget")!)!
        XCTAssertThrowsError(try RemoteShortcutCommand(url: relative))
    }

    func testDuplicateArgumentsAndNonCanonicalIntegersCannotChangeAnAction() {
        for code in ["", "+23", "023", "-23", "23.0", "2.3e1", "0x17", "23=24",
                     "2147483648", String(repeating: "9", count: 300), "1", "178", "304"] {
            XCTAssertThrowsError(try parse("bareremote://widget/key?code=\(code)"), code)
        }
        for query in ["code=23&code=24", "code=23&code=23", "id=a&code=23", "id=&code=23",
                      "code=23&id=12345678123442348234123456789abc",
                      "code=23&id=12345678-1234-4234-8234-123456789abc&id=12345678-1234-4234-8234-123456789abc"] {
            XCTAssertThrowsError(try parse("bareremote://widget/key?\(query)"), query)
        }
        XCTAssertThrowsError(try RemoteShortcutAction.key(178).url())
    }

    private func parse(_ value: String) throws -> RemoteShortcutCommand {
        try RemoteShortcutCommand(url: XCTUnwrap(URL(string: value)))
    }
}
