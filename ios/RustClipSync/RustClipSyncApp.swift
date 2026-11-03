import SwiftUI

@main
@MainActor
struct RustClipSyncApp: App {
    @Environment(\.scenePhase) private var phase
    @StateObject private var session = SessionController()
    var body: some Scene {
        WindowGroup {
            SyncView(session: session)
                .onChange(of: phase) { _, value in if value != .active { session.close() } }
        }
    }
}

@MainActor
struct SyncView: View {
    @ObservedObject var session: SessionController
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var policy: SensitivePolicy = .disabled
    @State private var confirmAutoWrite = false
    var body: some View {
        NavigationStack {
            Form {
                Section("连接") {
                    TextField("HTTPS 服务器地址", text: $server).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("账户", text: $username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("密码（仅本次内存会话）", text: $password)
                }.disabled(session.connected || session.busy)
                Section("已标记的敏感内容") {
                    Picker("接收策略", selection: Binding(get: { policy }, set: { value in
                        if value == .autoWrite { confirmAutoWrite = true }
                        else { policy = value; if session.connected { session.policy(value) } }
                    })) {
                        Text("跳过").tag(SensitivePolicy.disabled)
                        Text("加密收件箱").tag(SensitivePolicy.encryptedInbox)
                        Text("自动写入（显式启用）").tag(SensitivePolicy.autoWrite)
                    }.disabled(session.busy)
                    Text("未标记的文本无法保证识别为敏感内容。iOS 没有通用的密码剪贴板标记。")
                }
                Section("同步") {
                    if session.connected {
                        Button("发送当前剪贴板", action: session.send).disabled(session.busy)
                        Button("接收", action: session.receive).disabled(session.busy)
                        Button("关闭会话", action: session.close)
                    } else {
                        Button("连接并注册设备") {
                            session.connect(server: server.trimmingCharacters(in: .whitespacesAndNewlines), username: username, password: password, policy: policy)
                            password = ""
                        }.disabled(session.busy || server.isEmpty)
                    }
                    if session.busy { ProgressView() }
                    Text(session.message)
                    if let status = session.status { Text("游标 \(status.cursor) · \(status.hasPending ? "有待处理事务" : "无待处理事务")") }
                }
                if session.status?.inbox != nil {
                    Section("加密收件箱") {
                        Text("有一条敏感消息；正文默认隐藏。")
                        Button("显式复制", action: session.copyInbox).disabled(session.busy)
                        Button("删除", action: session.clearInbox).disabled(session.busy)
                    }
                }
                Section {
                    Text("离开前台即关闭；不在后台监视剪贴板。读取可能触发系统粘贴许可。首次注册不回放服务器旧消息。")
                }
            }.navigationTitle("RustClipSync")
                .privacySensitive()
                .confirmationDialog("允许敏感内容自动替换系统剪贴板？其他应用可能读取。", isPresented: $confirmAutoWrite, titleVisibility: .visible) {
                    Button("启用自动写入", role: .destructive) { policy = .autoWrite; if session.connected { session.policy(policy) } }
                }
        }
    }
}
