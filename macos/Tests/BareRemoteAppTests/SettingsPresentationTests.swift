import AppKit
import SwiftUI
import XCTest
@testable import BareRemote

@MainActor final class SettingsPresentationTests: XCTestCase {
    func testUnknownStateOffersExplicitRecoveryWhileUnsupportedStateCannotMutate() {
        for state in ["unknown", "error", "starting", "nsnow_access", "nsnow_menu",
                      "nsnow_channel", "nsnow_player", "nsnow_debug", "nsnow_target", "nsnow_input"] {
            XCTAssertEqual(DogModeSettingsAction(state: state), .restore)
            XCTAssertNotEqual(DogModeSettingsAction(state: state), .start)
        }
        for state in ["unavailable", ""] {
            XCTAssertNil(DogModeSettingsAction(state: state))
        }
    }

    func testConfirmedActiveStateEndsAndIncompleteRestoreOffersRecovery() {
        XCTAssertEqual(DogModeSettingsAction(state: "off"), .start)
        XCTAssertEqual(DogModeSettingsAction(state: "active"), .end)
        XCTAssertEqual(DogModeSettingsAction(state: "restore_required"), .restore)
        XCTAssertEqual(DogModeSettingsAction(state: "restore_required")?.title, "Restore settings & exit")
    }

    func testNativeSettingsViewportStaysBoundedWithLongFailure() {
        let model = RemoteModel()
        model.saved = nil
        model.detail = String(repeating: "The TV could not confirm its settings. ", count: 18)
        let view = NSHostingView(rootView: SetupView(model: model, discovery: TVDiscovery()))
        view.frame = NSRect(x: 0, y: 0, width: 470, height: 650)
        view.layoutSubtreeIfNeeded()
        XCTAssertEqual(view.fittingSize.width, 470, accuracy: 1)
        XCTAssertEqual(view.fittingSize.height, 650, accuracy: 1)
    }
}
