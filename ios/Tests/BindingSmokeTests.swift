import XCTest
@testable import RustClipSync

// The Rust executor serializes these calls. No production state or clipboard is touched.
private final class MemoryPlatform: Platform, @unchecked Sendable {
    private let lock = NSLock()
    private var data: Data?
    func loadState() throws -> Data? { lock.lock(); defer { lock.unlock() }; return data }
    func saveState(ciphertext: Data) throws { lock.lock(); defer { lock.unlock() }; data = ciphertext }
    func clipboardSnapshot() throws -> ClipboardText? { throw AdapterError.Unavailable }
    func clipboardWrite(value: ClipboardText) throws { throw AdapterError.Unavailable }
}
final class BindingSmokeTests: XCTestCase {
    func testEncryptedStateAndRepeatedShutdown() async throws {
        let platform = MemoryPlatform()
        let session = try MobileSession(config: MobileConfig(server: "https://synthetic.invalid/", username: "synthetic", password: "synthetic",
            stateKey: Data(repeating: 7, count: 32), sensitivePolicy: .disabled), platform: platform)
        let status = try await session.status()
        XCTAssertFalse(status.paired)
        XCTAssertEqual(try platform.loadState()?.prefix(4), Data("RCS1".utf8))
        session.requestClose(); session.requestClose()
        try await session.shutdown(); try await session.shutdown()
        do { _ = try await session.status(); XCTFail("closed session accepted status") }
        catch MobileError.Closed { }
    }
}
