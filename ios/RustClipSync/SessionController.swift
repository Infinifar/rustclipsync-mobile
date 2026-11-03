import Foundation
import SwiftUI

@MainActor
final class SessionController: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var connected = false
    @Published private(set) var status: SessionStatus?
    @Published private(set) var message = "仅在前台手动同步"
    private var native: MobileSession?
    private var platform: IOSPlatform?
    private var epoch: UInt64 = 0
    private var retiring: Task<Void, Never>?

    func connect(server: String, username: String, password: String, policy: SensitivePolicy) {
        guard !busy, native == nil else { return }
        epoch &+= 1; let version = epoch; busy = true
        Task {
            do {
                await retiring?.value
                let (store, key) = try await Task.detached { let state = try SecureState(); return (state, try state.key()) }.value
                guard version == epoch else { return }
                let adapter = IOSPlatform(state: store)
                let session = try MobileSession(config: MobileConfig(server: server, username: username, password: password,
                    stateKey: key, sensitivePolicy: policy), platform: adapter)
                platform = adapter; native = session; adapter.observe(session)
                try await session.check()
                let value = try await session.status()
                if version == epoch { status = value; connected = true; message = "已连接" }
            } catch {
                if version == epoch { close(); message = safeError(error) }
            }
            if version == epoch { busy = false }
        }
    }
    func send() { command { String(describing: try await $0.sendClipboard()) } }
    func receive() { command { String(describing: try await $0.receive()) } }
    func policy(_ policy: SensitivePolicy) { command { try await $0.updatePolicy(policy: policy); return "敏感策略已更新" } }
    func copyInbox() { guard let id = status?.inbox?.id else { return }; command { try await $0.copyInbox(id: id); return "已显式复制敏感内容" } }
    func clearInbox() { guard let id = status?.inbox?.id else { return }; command { try await $0.clearInbox(id: id); return "收件箱已清空" } }
    private func command(_ operation: @escaping (MobileSession) async throws -> String) {
        guard let session = native, connected, !busy else { return }
        let version = epoch; busy = true
        Task {
            do {
                let result = try await operation(session)
                let value = try await session.status()
                if version == epoch { message = result; status = value }
            } catch {
                if version == epoch {
                    if let error = error as? MobileError, [.Storage, .Closed, .Internal].contains(error) { close() }
                    message = safeError(error)
                }
            }
            if version == epoch { busy = false }
        }
    }
    func close() {
        epoch &+= 1
        platform?.revoke(); platform = nil
        let session = native; native = nil
        connected = false; busy = false; status = nil; message = "已关闭；回到前台后可重新连接"
        if let session {
            session.requestClose()
            let previous = retiring
            retiring = Task { await previous?.value; try? await session.shutdown() }
        }
    }
    private func safeError(_ error: Error) -> String {
        guard let error = error as? MobileError else { return "平台或安全存储不可用；请检查配置后重试" }
        switch error {
        case .Closed: return "会话已关闭"
        case .Busy: return "会话忙，请重试"
        case .InvalidInput: return "请检查 HTTPS 地址和配置"
        case .Storage: return "安全存储不可用，请重新连接"
        case .Clipboard: return "剪贴板不可用，请在前台允许粘贴后重试"
        case .Pairing: return "请检查账户、设备注册或服务器状态"
        case .Conflict: return "存在同步冲突；未覆盖远端内容"
        case .Expired: return "待发送内容已过期"
        case .Network: return "网络不可用"
        case .Protocol: return "服务器响应无效"
        case .ProtocolUnsupported: return "服务端协议不兼容，请使用支持协议 3 的服务端"
        case .Internal: return "会话异常，请重新连接"
        }
    }
}
