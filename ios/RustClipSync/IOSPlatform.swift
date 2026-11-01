import Foundation
import UIKit
import UniformTypeIdentifiers

// Mutable request state is locked. The operation itself runs only on the main queue.
private final class MainRequest<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var valid = true
    private var result: Result<T, Error>?
    let semaphore = DispatchSemaphore(value: 0)
    let deadline = DispatchTime.now() + .seconds(2)
    func begin() -> Bool { lock.lock(); defer { lock.unlock() }; return valid && DispatchTime.now() < deadline }
    func complete(_ value: Result<T, Error>) { lock.lock(); result = value; lock.unlock(); semaphore.signal() }
    func finish() throws -> T {
        if semaphore.wait(timeout: deadline) == .timedOut {
            lock.lock(); valid = false; lock.unlock(); throw AdapterError.Timeout
        }
        lock.lock(); defer { lock.unlock() }
        guard let result else { throw AdapterError.Unavailable }
        return try result.get()
    }
}

// Active state is locked; no callback retains a View or ViewModel.
final class IOSPlatform: Platform, @unchecked Sendable {
    private let state: SecureState
    private let lock = NSLock()
    private var active = true
    @MainActor private var observer: NSObjectProtocol?
    private static let originType = "io.github.infinifar.rustclipsync.origin"
    private static let sensitiveType = "io.github.infinifar.rustclipsync.sensitive"
    init(state: SecureState) { self.state = state }
    @MainActor func observe(_ session: MobileSession) {
        observer = NotificationCenter.default.addObserver(forName: UIPasteboard.changedNotification, object: nil, queue: .main) { [weak session] _ in
            session?.clipboardChanged()
        }
    }
    @MainActor func revoke() {
        lock.lock(); active = false; lock.unlock()
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
    }
    private func isActive() -> Bool { lock.lock(); defer { lock.unlock() }; return active }
    private func onMain<T: Sendable>(_ operation: @escaping @MainActor @Sendable () throws -> T) throws -> T {
        // Rust invokes from its executor. Never wait for the main queue from itself.
        guard !Thread.isMainThread else { throw AdapterError.Unavailable }
        let request = MainRequest<T>()
        DispatchQueue.main.async { [self] in
            guard request.begin(), isActive() else { request.complete(.failure(AdapterError.Unavailable)); return }
            request.complete(Result { try operation() })
        }
        return try request.finish()
    }
    func clipboardSnapshot() throws -> ClipboardText? {
        try onMain {
            let board = UIPasteboard.general
            guard let text = board.string else { return nil }
            guard text.utf8.count <= 10 * 1024 * 1024 else { throw AdapterError.Unavailable }
            // iOS has no universal password/sensitive pasteboard mark. Only trust our explicit metadata.
            let sensitive = board.data(forPasteboardType: Self.sensitiveType) == Data([1])
            let origin = board.data(forPasteboardType: Self.originType).flatMap { String(data: $0, encoding: .utf8) }
            return ClipboardText(text: text, sensitive: sensitive, origin: origin)
        }
    }
    func clipboardWrite(value: ClipboardText) throws {
        try onMain {
            var item: [String: Any] = [UTType.utf8PlainText.identifier: value.text,
                Self.sensitiveType: Data([value.sensitive ? 1 : 0])]
            if let origin = value.origin { item[Self.originType] = Data(origin.utf8) }
            var options: [UIPasteboard.OptionsKey: Any] = [.localOnly: true]
            if value.sensitive { options[.expirationDate] = Date().addingTimeInterval(60) }
            UIPasteboard.general.setItems([item], options: options)
        }
    }
    func loadState() throws -> Data? { try state.load() }
    func saveState(ciphertext: Data) throws { try state.save(ciphertext) }
}
