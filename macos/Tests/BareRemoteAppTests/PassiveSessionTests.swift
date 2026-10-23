import Foundation
import XCTest
@testable import BareRemote
@testable import BareRemoteCore

@MainActor final class PassiveSessionTests: XCTestCase {
    func testSessionResetKeepsOnlyTheSameTVsLastPictureConfirmation() {
        let model = RemoteModel()
        model.saved = SavedTV(host: "192.0.2.1", name: "TV", certificate: Data([1]))
        model.pictureState = "night"
        model.dogMode = "active"
        model.disconnect()
        XCTAssertEqual(model.pictureState, "night", "Disconnect does not manufacture a fresh picture query")
        XCTAssertEqual(model.modeLabel, "Night last confirmed")
        XCTAssertEqual(model.dogMode, "unknown", "An earlier Dog Mode snapshot cannot authorize a new Start")
        XCTAssertFalse(model.dogBusy)

        model.saved = SavedTV(host: "192.0.2.1", name: "Renamed TV", certificate: Data([1]))
        XCTAssertEqual(model.pictureState, "night", "A display-name edit does not change the paired TV")
        model.saved = SavedTV(host: "192.0.2.1", name: "TV", certificate: Data([2]))
        XCTAssertEqual(model.pictureState, "unknown", "Another certificate cannot inherit the cached preset")
        model.pictureState = "day"
        model.saved = SavedTV(host: "192.0.2.9", name: "TV", certificate: Data([2]))
        XCTAssertEqual(model.pictureState, "unknown", "Another saved address invalidates the snapshot scope")
        model.pictureState = "day"
        model.saved = nil
        XCTAssertEqual(model.pictureState, "unknown", "Forgetting clears the cached preset")
    }

    func testSettingsTransitionDoesNotInventDogStatusOrStartARequest() {
        let model = RemoteModel()
        model.saved = nil // This test never opens a transport or accesses a TV.
        model.dogMode = "active"
        model.openSetup()
        XCTAssertTrue(model.setupOpen)
        XCTAssertEqual(model.dogMode, "unknown")
        XCTAssertFalse(model.dogBusy)
        XCTAssertTrue(model.dogStatus.contains("Check status"))
        model.startDogMode()
        XCTAssertEqual(model.dogMode, "unknown")
        XCTAssertFalse(model.dogBusy, "Opening Settings does not authorize Start from an unconfirmed state")
        XCTAssertTrue(model.dogStatus.contains("Check Dog Mode status before starting"))
        model.closeSetup()
        XCTAssertFalse(model.setupOpen)
        XCTAssertEqual(model.dogMode, "unknown")
        XCTAssertFalse(model.dogBusy)
    }
}
