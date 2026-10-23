import Combine
import Darwin
import Foundation

struct DiscoveredTV: Identifiable, Equatable, Sendable {
    var id: String { host }
    let name: String
    let host: String
}

/// Explicit foreground-only Bonjour discovery. Names are untrusted display
/// labels; choosing a result never creates or replaces certificate trust.
@MainActor
final class TVDiscovery: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    @Published private(set) var results: [DiscoveredTV] = []
    @Published private(set) var isScanning = false

    private var browser: NetServiceBrowser?
    private var deadline: DispatchWorkItem?
    private var queued: [NetService] = []
    private var candidates: [ObjectIdentifier: NetService] = [:]
    private var resolvedHosts: [ObjectIdentifier: String] = [:]
    private var resolving: NetService?
    private let maximumCandidates = 64
    private let maximumResults = 32

    func start() {
        stop()
        results = []
        isScanning = true
        let browser = NetServiceBrowser()
        self.browser = browser
        browser.delegate = self
        browser.searchForServices(ofType: "_androidtvremote2._tcp.", inDomain: "local.")
        let deadline = DispatchWorkItem { [weak self] in self?.stop() }
        self.deadline = deadline
        DispatchQueue.main.asyncAfter(deadline: .now() + 12, execute: deadline)
    }

    func stop() {
        deadline?.cancel()
        deadline = nil
        isScanning = false
        browser?.delegate = nil
        browser?.stop()
        browser = nil
        for service in candidates.values {
            service.delegate = nil
            service.stop()
        }
        candidates.removeAll()
        resolvedHosts.removeAll()
        queued.removeAll()
        resolving = nil
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        MainActor.assumeIsolated { didFind(browser: browser, service: service) }
    }

    private func didFind(browser: NetServiceBrowser, service: NetService) {
        guard isScanning, browser === self.browser, candidates.count < maximumCandidates,
              service.type.lowercased() == "_androidtvremote2._tcp.",
              service.name.utf8.count <= 256 else { return }
        let identifier = ObjectIdentifier(service)
        guard candidates[identifier] == nil else { return }
        candidates[identifier] = service
        queued.append(service)
        resolveNext()
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        MainActor.assumeIsolated { didRemove(browser: browser, service: service) }
    }

    private func didRemove(browser: NetServiceBrowser, service: NetService) {
        guard isScanning, browser === self.browser else { return }
        // Removal callbacks may contain a different NetService instance.
        let identifiers = candidates.compactMap { identifier, candidate in
            candidate.name == service.name && candidate.type == service.type && candidate.domain == service.domain
                ? identifier : nil
        }
        for identifier in identifiers {
            guard let candidate = candidates.removeValue(forKey: identifier) else { continue }
            candidate.delegate = nil
            candidate.stop()
            queued.removeAll { $0 === candidate }
            if resolving === candidate { resolving = nil }
            if let host = resolvedHosts.removeValue(forKey: identifier), !resolvedHosts.values.contains(host) {
                results.removeAll { $0.host == host }
            }
        }
        resolveNext()
    }

    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        MainActor.assumeIsolated {
            guard browser === self.browser else { return }
            stop()
        }
    }

    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        MainActor.assumeIsolated { didResolve(sender) }
    }

    private func didResolve(_ sender: NetService) {
        guard isScanning, resolving === sender,
              candidates[ObjectIdentifier(sender)] === sender else { return }
        if sender.port == 6466, let host = Self.ipv4Address(sender.addresses ?? []) {
            resolvedHosts[ObjectIdentifier(sender)] = host
            if !results.contains(where: { $0.host == host }), results.count < maximumResults {
                let name = sender.name.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
                let displayName = String(String.UnicodeScalarView(name)).trimmingCharacters(in: .whitespacesAndNewlines)
                results.append(DiscoveredTV(name: displayName.isEmpty ? host : displayName, host: host))
                results.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            }
        }
        finishResolution(sender)
    }

    nonisolated func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        MainActor.assumeIsolated {
            guard isScanning, resolving === sender,
                  candidates[ObjectIdentifier(sender)] === sender else { return }
            finishResolution(sender)
        }
    }

    private func resolveNext() {
        guard isScanning, resolving == nil, !queued.isEmpty else { return }
        let service = queued.removeFirst()
        resolving = service
        service.delegate = self
        service.resolve(withTimeout: 3)
    }

    private func finishResolution(_ service: NetService) {
        service.delegate = nil
        service.stop()
        resolving = nil
        resolveNext()
    }

    private static func ipv4Address(_ addresses: [Data]) -> String? {
        for data in addresses {
            guard data.count >= MemoryLayout<sockaddr_in>.size else { continue }
            var address = sockaddr_in()
            _ = withUnsafeMutableBytes(of: &address) { data.copyBytes(to: $0) }
            guard Int32(address.sin_family) == AF_INET else { continue }
            // Exclude unspecified, multicast and limited-broadcast addresses.
            let hostOrder = UInt32(bigEndian: address.sin_addr.s_addr)
            guard hostOrder != 0, hostOrder != UInt32.max, hostOrder & 0xf0000000 != 0xe0000000 else { continue }
            var storage = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &address.sin_addr, &storage, socklen_t(storage.count)) != nil else { continue }
            return String(cString: storage)
        }
        return nil
    }
}
