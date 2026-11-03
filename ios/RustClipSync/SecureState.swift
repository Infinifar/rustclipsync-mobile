import Foundation
import Security
import Darwin

// Only exact core ciphertext is persisted. The raw 32-byte state key lives in Keychain.
final class SecureState: @unchecked Sendable {
    private let directory: URL
    private let file: URL
    private static let maximum = 64 * 1024 * 1024
    init() throws {
        directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("RustClipSync", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        file = directory.appendingPathComponent("session.rcs")
    }
    func load() throws -> Data? {
        do {
            let handle: FileHandle
            do { handle = try FileHandle(forReadingFrom: file) }
            catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError { return nil }
            defer { try? handle.close() }
            var data = Data()
            while let chunk = try handle.read(upToCount: min(16 * 1024, Self.maximum + 1 - data.count)), !chunk.isEmpty {
                data.append(chunk)
                if data.count > Self.maximum { throw AdapterError.Storage }
            }
            return data
        } catch { throw AdapterError.Storage }
    }
    func save(_ ciphertext: Data) throws {
        guard ciphertext.count <= Self.maximum else { throw AdapterError.Storage }
        let temporary = directory.appendingPathComponent("session.tmp")
        do {
            guard FileManager.default.createFile(atPath: temporary.path, contents: nil,
                attributes: [.posixPermissions: 0o600, .protectionKey: FileProtectionType.complete]) else { throw AdapterError.Storage }
            defer { try? FileManager.default.removeItem(at: temporary) }
            let output = try FileHandle(forWritingTo: temporary)
            do { try output.write(contentsOf: ciphertext); try output.synchronize(); try output.close() }
            catch { try? output.close(); throw error }
            guard rename(temporary.path, file.path) == 0 else { throw AdapterError.Storage }
            let fd = Darwin.open(directory.path, O_RDONLY)
            guard fd >= 0 else { throw AdapterError.Storage }
            defer { Darwin.close(fd) }
            guard fsync(fd) == 0 else { throw AdapterError.Storage }
        } catch { throw AdapterError.Storage }
    }
    func key() throws -> Data {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "io.github.infinifar.rustclipsync.state",
            kSecAttrAccount as String: "state-key-v1"]
        var lookup = query; lookup[kSecReturnData as String] = true; lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess {
            guard let value = result as? Data, value.count == 32 else { throw AdapterError.Storage }
            return value
        }
        guard status == errSecItemNotFound else { throw AdapterError.Storage }
        // An absent key must not silently reset unreadable or existing session state.
        guard try load() == nil else { throw AdapterError.Storage }
        var raw = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, raw.count, &raw) == errSecSuccess else { throw AdapterError.Storage }
        let value = Data(raw)
        var insert = query; insert[kSecValueData as String] = value
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        insert[kSecAttrSynchronizable as String] = false
        guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw AdapterError.Storage }
        return value
    }
}
