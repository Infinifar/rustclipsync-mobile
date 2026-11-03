use super::*;
use crate::{AdapterError, ClipboardText};
use futures::{executor::block_on, future::join_all, poll};
use rustclipsync_core::{
    client::{Registration, RemoteState},
    state::{self, Ack, Message, Outbox, Persisted},
};
use std::sync::{
    Mutex,
    mpsc::{Receiver, Sender},
};

#[derive(Default)]
struct FakePlatform {
    bytes: Mutex<Option<Vec<u8>>>,
    clipboard: Mutex<Option<ClipboardText>>,
    writes: AtomicU64,
    reads: AtomicU64,
    forbid_clipboard: AtomicBool,
    fail_save: AtomicBool,
    fail_write: AtomicBool,
    fail_read: AtomicBool,
    threads: Mutex<Vec<thread::ThreadId>>,
}
impl Platform for FakePlatform {
    fn clipboard_snapshot(&self) -> Result<Option<ClipboardText>, AdapterError> {
        self.reads.fetch_add(1, Ordering::SeqCst);
        assert!(!self.forbid_clipboard.load(Ordering::SeqCst));
        if self.fail_read.load(Ordering::SeqCst) {
            return Err(AdapterError::Unavailable);
        }
        Ok(self.clipboard.lock().unwrap().clone())
    }
    fn clipboard_write(&self, value: ClipboardText) -> Result<(), AdapterError> {
        assert!(!self.forbid_clipboard.load(Ordering::SeqCst));
        if self.fail_write.load(Ordering::SeqCst) {
            return Err(AdapterError::Unavailable);
        }
        self.writes.fetch_add(1, Ordering::SeqCst);
        *self.clipboard.lock().unwrap() = Some(value);
        Ok(())
    }
    fn load_state(&self) -> Result<Option<Vec<u8>>, AdapterError> {
        self.threads.lock().unwrap().push(thread::current().id());
        Ok(self.bytes.lock().unwrap().clone())
    }
    fn save_state(&self, value: Vec<u8>) -> Result<(), AdapterError> {
        self.threads.lock().unwrap().push(thread::current().id());
        if self.fail_save.load(Ordering::SeqCst) {
            return Err(AdapterError::Storage);
        }
        *self.bytes.lock().unwrap() = Some(value);
        Ok(())
    }
}
#[derive(Default)]
struct Data {
    revision: u64,
    current: Option<(Message, String)>,
    acks: Vec<String>,
    sends: Vec<String>,
    attempts: Vec<String>,
}
type StateGate = (Sender<()>, Receiver<()>);

#[derive(Clone, Default)]
struct Remote {
    data: Arc<Mutex<Data>>,
    gate: Arc<Mutex<Option<StateGate>>>,
    fail_next_send: Arc<AtomicBool>,
    conflict_next_send: Arc<AtomicBool>,
    direction: Arc<Mutex<Option<(CoreDirection, bool)>>>,
    fail_direction: Arc<AtomicBool>,
}
const EPOCH: &str = "10000000-0000-4000-8000-000000000001";
impl Remote {
    fn publish(&self, text: &str, sensitive: bool) {
        let mut d = self.data.lock().unwrap();
        d.revision += 1;
        d.current = Some((
            Message {
                id: state::random_uuid().unwrap(),
                revision: d.revision,
                source_device_id: "20000000-0000-4000-8000-000000000001".into(),
                sensitive,
            },
            text.into(),
        ));
    }
    fn block_next_state(&self) -> (Receiver<()>, Sender<()>) {
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        *self.gate.lock().unwrap() = Some((entered_tx, release_rx));
        (entered_rx, release_tx)
    }
}
impl Transport for Remote {
    fn set_sync_direction(
        &self,
        session: &Persisted,
        direction: CoreDirection,
        paused: bool,
    ) -> Result<(), Error> {
        *self.direction.lock().unwrap() = Some((direction, paused));
        if session.paired {
            self.ensure_sync_direction(session)
        } else {
            Ok(())
        }
    }
    fn ensure_sync_direction(&self, _: &Persisted) -> Result<(), Error> {
        if self.fail_direction.load(Ordering::SeqCst) {
            return Err(Error::Protocol(
                rustclipsync_core::direction::SyncDirectionError {
                    code: DirectionErrorCode::ProtocolUnsupported,
                    version: None,
                    minimum_protocol_version: Some(3),
                },
            ));
        }
        self.direction
            .lock()
            .unwrap()
            .ok_or(Error::DirectionRequired)
            .map(|_| ())
    }
    fn revoke_sync_direction(&self, _: &Persisted) -> Result<(), Error> {
        *self.direction.lock().unwrap() = None;
        Ok(())
    }
    fn register(&self, _: &Persisted) -> Result<Registration, Error> {
        let d = self.data.lock().unwrap();
        Ok(Registration {
            epoch: EPOCH.into(),
            ack_revision: d.revision,
            current_revision: d.revision,
        })
    }
    fn state(&self, _: &Persisted) -> Result<RemoteState, Error> {
        let gate = self.gate.lock().unwrap().take();
        if let Some((entered, release)) = gate {
            entered.send(()).unwrap();
            release
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
        }
        let d = self.data.lock().unwrap();
        Ok(RemoteState {
            epoch: EPOCH.into(),
            revision: d.revision,
            ack_revision: 0,
            delivery_revision: d.current.as_ref().map(|(m, _)| m.revision),
            message_id: d.current.as_ref().map(|(m, _)| m.id.clone()),
            source_device_id: d.current.as_ref().map(|(m, _)| m.source_device_id.clone()),
            size: d.current.as_ref().map(|(_, text)| text.len() as u64),
            sensitive: d.current.as_ref().map(|(m, _)| m.sensitive),
        })
    }
    fn changes(&self, _: &Persisted) -> Result<Option<RemoteState>, Error> {
        panic!("manual sessions must not start long polling")
    }
    fn content(&self, _: &Persisted, _: &Message) -> Result<String, Error> {
        Ok(self
            .data
            .lock()
            .unwrap()
            .current
            .as_ref()
            .unwrap()
            .1
            .clone())
    }
    fn send(&self, _: &Persisted, outbox: &Outbox) -> Result<u64, Error> {
        let mut d = self.data.lock().unwrap();
        d.attempts.push(outbox.copy.id.clone());
        if self.fail_next_send.swap(false, Ordering::SeqCst) {
            return Err(Error::Io(std::io::Error::other("synthetic disconnect")));
        }
        if self.conflict_next_send.swap(false, Ordering::SeqCst) {
            return Err(Error::Status(409));
        }
        d.sends.push(outbox.copy.text.clone());
        d.revision += 1;
        Ok(d.revision)
    }
    fn cancel(&self, _: &Persisted, _: &Outbox) -> Result<Option<u64>, Error> {
        Ok(None)
    }
    fn acknowledge(&self, _: &Persisted, ack: &Ack) -> Result<(), Error> {
        self.data.lock().unwrap().acks.push(ack.result.clone());
        Ok(())
    }
}

#[test]
fn retry_pending_preserves_the_existing_request_instead_of_recording_another_copy() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let session = start(&platform, &remote, SensitivePolicy::Disabled);
        *platform.clipboard.lock().unwrap() = Some(text("synthetic pending", false));
        remote.fail_next_send.store(true, Ordering::SeqCst);
        assert!(matches!(
            session.send_clipboard().await,
            Err(MobileError::Network)
        ));
        assert!(session.status().await.unwrap().has_pending);
        assert_eq!(
            session.retry_pending().await.unwrap(),
            SyncOutcome::Uploaded
        );
        session.retry_pending().await.unwrap();
        assert!(!session.status().await.unwrap().has_pending);
        {
            let data = remote.data.lock().unwrap();
            assert_eq!(data.attempts.len(), 2);
            assert_eq!(
                data.attempts[0], data.attempts[1],
                "retry must retain its UUID"
            );
            assert_eq!(data.sends, ["synthetic pending"]);
        }
        session.shutdown().await.unwrap();
    });
}

#[cfg(feature = "disposable-tests")]
#[test]
fn disposable_constructor_rejects_every_non_loopback_root_destination() {
    for server in [
        "https://example.test/",
        "http://127.0.0.1:8443/",
        "https://localhost:8443/",
        "https://127.0.0.1:0/",
        "https://127.0.0.1:65536/",
        "https://127.0.0.1:8443/path/",
        "https://127.0.0.1:8443@evil.test/",
        "https://127.0.0.1:8443/?query=1",
    ] {
        let mut value = config(SensitivePolicy::Disabled);
        value.server = server.into();
        assert!(
            matches!(
                MobileSession::for_disposable_test(value, Arc::new(FakePlatform::default())),
                Err(MobileError::InvalidInput)
            ),
            "destination {server}"
        );
    }
}
fn config(policy: SensitivePolicy) -> MobileConfig {
    MobileConfig {
        server: "https://synthetic.invalid/".into(),
        username: "synthetic-user".into(),
        password: "synthetic-secret".into(),
        state_key: vec![7; 32],
        sensitive_policy: policy,
    }
}
fn start(
    platform: &Arc<FakePlatform>,
    remote: &Remote,
    policy: SensitivePolicy,
) -> Arc<MobileSession> {
    let remote = remote.clone();
    MobileSession::spawn(config(policy), platform.clone(), move |_| Ok(remote)).unwrap()
}
fn text(value: &str, sensitive: bool) -> ClipboardText {
    ClipboardText {
        text: value.into(),
        sensitive,
        origin: None,
    }
}

#[test]
fn core_round_trip_uses_encrypted_durable_state_on_one_non_ui_thread() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        assert!(!s.status().await.unwrap().paired);
        *platform.clipboard.lock().unwrap() = Some(text("synthetic outbound", false));
        assert_eq!(s.send_clipboard().await.unwrap(), SyncOutcome::Uploaded);
        remote.publish("synthetic inbound", false);
        assert_eq!(s.receive().await.unwrap(), SyncOutcome::Downloaded);
        assert_eq!(platform.writes.load(Ordering::SeqCst), 1);
        let status = s.status().await.unwrap();
        assert!(status.paired);
        let bytes = platform.bytes.lock().unwrap().clone().unwrap();
        assert_eq!(&bytes[..4], b"RCS1");
        assert!(!bytes.windows(18).any(|w| w == b"synthetic outbound"));
        let restored = state::open(&[7; 32], bytes).unwrap();
        assert_eq!(restored.state.cursor, status.cursor);
        let threads = platform.threads.lock().unwrap().clone();
        assert!(threads.iter().all(|id| *id != thread::current().id()));
        assert!(threads.iter().all(|id| *id == threads[0]));
        s.shutdown().await.unwrap();
        let reopened = start(&platform, &remote, SensitivePolicy::Disabled);
        assert_eq!(reopened.status().await.unwrap().cursor, status.cursor);
        reopened.shutdown().await.unwrap();
    });
}

#[test]
fn sensitive_policy_distinguishes_skip_inbox_and_actual_clipboard_write() {
    for (policy, expected, writes, ack) in [
        (
            SensitivePolicy::Disabled,
            SyncOutcome::Filtered,
            0,
            "skipped",
        ),
        (
            SensitivePolicy::EncryptedInbox,
            SyncOutcome::Stored,
            0,
            "skipped",
        ),
        (
            SensitivePolicy::AutoWrite,
            SyncOutcome::Downloaded,
            1,
            "applied",
        ),
    ] {
        block_on(async {
            let platform = Arc::new(FakePlatform::default());
            let remote = Remote::default();
            let s = start(&platform, &remote, policy);
            s.check().await.unwrap();
            remote.publish("synthetic marked text", true);
            assert_eq!(s.receive().await.unwrap(), expected, "policy {policy:?}");
            assert_eq!(
                platform.writes.load(Ordering::SeqCst),
                writes,
                "policy {policy:?}"
            );
            let persisted =
                state::open(&[7; 32], platform.bytes.lock().unwrap().clone().unwrap()).unwrap();
            assert_eq!(
                persisted.state.ack.unwrap().result,
                ack,
                "policy {policy:?}"
            );
            if let Some(inbox) = s.status().await.unwrap().inbox {
                assert_eq!(s.read_inbox("stale-id".into()).await.unwrap(), None);
                assert_eq!(
                    s.read_inbox(inbox.id.clone()).await.unwrap().as_deref(),
                    Some("synthetic marked text")
                );
                s.copy_inbox(inbox.id.clone()).await.unwrap();
                assert!(
                    platform
                        .clipboard
                        .lock()
                        .unwrap()
                        .as_ref()
                        .unwrap()
                        .sensitive
                );
                s.clear_inbox(inbox.id).await.unwrap();
                assert!(s.status().await.unwrap().inbox.is_none());
            }
            s.update_policy(SensitivePolicy::AutoWrite).await.unwrap();
            assert_eq!(
                s.receive().await.unwrap(),
                SyncOutcome::Current,
                "consumed values must not replay"
            );
            s.shutdown().await.unwrap();
        });
    }
}

#[test]
fn failed_os_write_is_never_acknowledged_as_applied() {
    block_on(async {
        let p = Arc::new(FakePlatform::default());
        let r = Remote::default();
        let s = start(&p, &r, SensitivePolicy::AutoWrite);
        s.check().await.unwrap();
        r.publish("synthetic", true);
        p.fail_write.store(true, Ordering::SeqCst);
        assert_eq!(s.receive().await, Err(MobileError::Clipboard));
        let durable = state::open(&[7; 32], p.bytes.lock().unwrap().clone().unwrap()).unwrap();
        assert_eq!(durable.state.ack.unwrap().result, "skipped");
        s.shutdown().await.unwrap();
    });
}

#[test]
fn storage_failure_terminates_and_corruption_never_resets_identity() {
    block_on(async {
        let p = Arc::new(FakePlatform::default());
        let r = Remote::default();
        let s = start(&p, &r, SensitivePolicy::Disabled);
        s.status().await.unwrap();
        let original = p.bytes.lock().unwrap().clone();
        p.fail_save.store(true, Ordering::SeqCst);
        *p.clipboard.lock().unwrap() = Some(text("synthetic", false));
        assert_eq!(s.send_clipboard().await, Err(MobileError::Storage));
        s.shutdown().await.unwrap();
        assert_eq!(s.status().await, Err(MobileError::Closed));
        assert_eq!(*p.bytes.lock().unwrap(), original);
        *p.bytes.lock().unwrap() = Some(b"corrupted synthetic state".to_vec());
        let reopened = start(&p, &r, SensitivePolicy::Disabled);
        assert_eq!(reopened.status().await, Err(MobileError::Storage));
        reopened.shutdown().await.unwrap();
        assert_eq!(
            p.bytes.lock().unwrap().as_deref(),
            Some(b"corrupted synthetic state".as_slice())
        );
    });
}

#[test]
fn close_is_nonblocking_rejects_queue_and_suppresses_late_clipboard_callbacks() {
    block_on(async {
        let p = Arc::new(FakePlatform::default());
        let r = Remote::default();
        let s = start(&p, &r, SensitivePolicy::AutoWrite);
        s.check().await.unwrap();
        r.publish("synthetic", false);
        let (entered, release) = r.block_next_state();
        let mut receive = Box::pin(s.receive());
        assert!(poll!(&mut receive).is_pending());
        entered
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let mut queued: Vec<_> = (0..QUEUE_CAPACITY).map(|_| Box::pin(s.status())).collect();
        for future in &mut queued {
            assert!(poll!(future).is_pending());
        }
        assert_eq!(s.status().await, Err(MobileError::Busy));
        s.request_close();
        s.request_close(); // Must return while the transport is still blocked.
        assert_eq!(s.status().await, Err(MobileError::Closed));
        release.send(()).unwrap();
        assert_eq!(receive.await, Err(MobileError::Closed));
        for result in join_all(queued).await {
            assert_eq!(result, Err(MobileError::Closed));
        }
        let (a, b) = futures::join!(s.shutdown(), s.shutdown());
        a.unwrap();
        b.unwrap();
        assert_eq!(
            p.writes.load(Ordering::SeqCst),
            0,
            "closed executor must not write clipboard"
        );
    });
}

#[test]
fn clipboard_change_during_network_prevents_stale_receive() {
    block_on(async {
        let p = Arc::new(FakePlatform::default());
        let r = Remote::default();
        let s = start(&p, &r, SensitivePolicy::AutoWrite);
        s.check().await.unwrap();
        r.publish("synthetic", false);
        let (entered, release) = r.block_next_state();
        let mut receive = Box::pin(s.receive());
        assert!(poll!(&mut receive).is_pending());
        entered
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        s.clipboard_changed();
        release.send(()).unwrap();
        assert_eq!(receive.await.unwrap(), SyncOutcome::Skipped);
        assert_eq!(p.writes.load(Ordering::SeqCst), 0);
        s.shutdown().await.unwrap();
    });
}

#[test]
fn executor_panic_terminates_session_and_reports_failed_shutdown() {
    struct PanicPlatform;
    impl Platform for PanicPlatform {
        fn clipboard_snapshot(&self) -> Result<Option<ClipboardText>, AdapterError> {
            unreachable!()
        }
        fn clipboard_write(&self, _: ClipboardText) -> Result<(), AdapterError> {
            unreachable!()
        }
        fn load_state(&self) -> Result<Option<Vec<u8>>, AdapterError> {
            panic!("synthetic adapter fault")
        }
        fn save_state(&self, _: Vec<u8>) -> Result<(), AdapterError> {
            unreachable!()
        }
    }
    block_on(async {
        let s = MobileSession::spawn(
            config(SensitivePolicy::Disabled),
            Arc::new(PanicPlatform),
            |_| Ok(Remote::default()),
        )
        .unwrap();
        assert_eq!(s.status().await, Err(MobileError::Closed));
        assert_eq!(s.shutdown().await, Err(MobileError::Internal));
        assert_eq!(s.shutdown().await, Err(MobileError::Internal));
    });
}

#[test]
fn unmarked_text_is_not_heuristically_treated_as_sensitive() {
    block_on(async {
        let p = Arc::new(FakePlatform::default());
        let r = Remote::default();
        let s = start(&p, &r, SensitivePolicy::Disabled);
        s.check().await.unwrap();
        r.publish("123456", false);
        assert_eq!(s.receive().await.unwrap(), SyncOutcome::Downloaded);
        assert!(!p.clipboard.lock().unwrap().as_ref().unwrap().sensitive);
        s.shutdown().await.unwrap();
    });
}

#[test]
fn background_binding_stores_only_marked_inbox_without_platform_clipboard_calls() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::EncryptedInbox);
        s.check().await.unwrap();
        let reads = platform.reads.load(Ordering::SeqCst);
        platform.forbid_clipboard.store(true, Ordering::SeqCst);
        remote.publish("synthetic notification body", true);
        assert_eq!(s.receive_inbox().await.unwrap(), SyncOutcome::Stored);
        assert_eq!(platform.reads.load(Ordering::SeqCst), reads);
        assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
        let status = s.status().await.unwrap();
        let id = status.inbox.unwrap().id;
        assert_eq!(
            s.read_inbox(id).await.unwrap().unwrap(),
            "synthetic notification body"
        );
        assert_eq!(remote.data.lock().unwrap().acks, ["skipped"]);
        remote.publish("ordinary foreground text", false);
        assert_eq!(s.receive_inbox().await.unwrap(), SyncOutcome::Skipped);
        assert_eq!(s.status().await.unwrap().cursor, status.cursor);
        s.update_policy(SensitivePolicy::AutoWrite).await.unwrap();
        remote.publish("marked but no inbox", true);
        assert_eq!(s.receive_inbox().await.unwrap(), SyncOutcome::Skipped);
        s.shutdown().await.unwrap();
    });
}

#[test]
fn background_write_binding_requires_opt_in_and_reads_no_platform_snapshot() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::EncryptedInbox);
        s.check().await.unwrap();
        let reads = platform.reads.load(Ordering::SeqCst);
        remote.publish("synthetic ordinary background write", false);
        assert_eq!(
            s.receive_background(false).await.unwrap(),
            SyncOutcome::Skipped
        );
        assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
        assert_eq!(
            s.receive_background(true).await.unwrap(),
            SyncOutcome::Downloaded
        );
        assert_eq!(platform.reads.load(Ordering::SeqCst), reads);
        assert_eq!(platform.writes.load(Ordering::SeqCst), 1);
        s.shutdown().await.unwrap();
    });
}

#[test]
fn configured_filter_survives_sensitive_policy_changes_and_rejects_invalid_rules() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        let cfg = crate::FilterConfig {
            mode: crate::TextFilterMode::Blacklist,
            patterns: vec!["^blocked".into()],
            downloads: true,
        };
        s.update_filter(cfg.clone()).await.unwrap();
        s.update_policy(SensitivePolicy::EncryptedInbox)
            .await
            .unwrap();
        *platform.clipboard.lock().unwrap() = Some(text("blocked synthetic text", false));
        assert_eq!(s.send_clipboard().await.unwrap(), SyncOutcome::Filtered);
        assert!(remote.data.lock().unwrap().sends.is_empty());
        let invalid = crate::FilterConfig {
            patterns: vec!["[".into()],
            ..cfg
        };
        assert!(matches!(
            s.update_filter(invalid).await,
            Err(MobileError::InvalidInput)
        ));
        *platform.clipboard.lock().unwrap() = Some(text("blocked again", false));
        assert_eq!(s.send_clipboard().await.unwrap(), SyncOutcome::Filtered);
        remote.publish("blocked remote text", false);
        assert_eq!(s.receive().await.unwrap(), SyncOutcome::Filtered);
        assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
        remote.publish("blocked sensitive", true);
        assert_eq!(s.receive().await.unwrap(), SyncOutcome::Filtered);
        assert!(s.status().await.unwrap().inbox.is_none());
        s.shutdown().await.unwrap();
    });
}

#[test]
fn filter_sample_uses_core_semantics_and_device_uuid_survives_reopen() {
    let cfg = crate::FilterConfig {
        mode: crate::TextFilterMode::Whitelist,
        patterns: vec!["^allowed".into()],
        downloads: false,
    };
    assert!(!crate::test_filter(cfg.clone(), "other".into(), false).unwrap());
    assert!(crate::test_filter(cfg, "other".into(), true).unwrap());
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        s.check().await.unwrap();
        let id = s.status().await.unwrap().device_id;
        assert!(!id.is_empty());
        s.shutdown().await.unwrap();
        let reopened = start(&platform, &remote, SensitivePolicy::Disabled);
        assert_eq!(id, reopened.status().await.unwrap().device_id);
        reopened.shutdown().await.unwrap();
    });
}

#[test]
fn directions_block_disallowed_transfers_and_never_retry_a_receive_only_upload() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        s.check().await.unwrap();
        s.update_direction(crate::SyncDirection::Upload)
            .await
            .unwrap();
        remote.publish("remote synthetic", false);
        assert_eq!(s.receive().await.unwrap(), SyncOutcome::Paused);
        assert_eq!(
            s.receive_background(true).await.unwrap(),
            SyncOutcome::Paused
        );
        assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
        *platform.clipboard.lock().unwrap() = Some(text("queued synthetic", false));
        remote.fail_next_send.store(true, Ordering::SeqCst);
        assert!(matches!(
            s.send_clipboard().await,
            Err(MobileError::Network)
        ));
        let attempts = remote.data.lock().unwrap().attempts.len();
        s.update_direction(crate::SyncDirection::Download)
            .await
            .unwrap();
        assert_eq!(s.send_clipboard().await.unwrap(), SyncOutcome::Paused);
        s.retry_pending().await.unwrap();
        assert_eq!(remote.data.lock().unwrap().attempts.len(), attempts);
        assert_eq!(s.receive().await.unwrap(), SyncOutcome::Downloaded);
        s.shutdown().await.unwrap();
    });
}

#[test]
fn protocol3_reports_direction_before_data_without_network_on_status() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        s.status().await.unwrap();
        assert_eq!(
            *remote.direction.lock().unwrap(),
            None,
            "metadata must not activate direction"
        );
        s.check().await.unwrap();
        assert_eq!(
            *remote.direction.lock().unwrap(),
            Some((CoreDirection::Bidirectional, false)),
            "first connection must report the selected direction"
        );
        s.update_direction(crate::SyncDirection::Upload)
            .await
            .unwrap();
        assert_eq!(
            *remote.direction.lock().unwrap(),
            Some((CoreDirection::PushOnly, false))
        );
        s.update_direction(crate::SyncDirection::Download)
            .await
            .unwrap();
        assert_eq!(
            *remote.direction.lock().unwrap(),
            Some((CoreDirection::ReceiveOnly, false))
        );
        s.shutdown().await.unwrap();
    });
}

#[test]
fn protocol3_failed_direction_ack_prevents_clipboard_capture_and_upload() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        s.check().await.unwrap();
        let reads = platform.reads.load(Ordering::SeqCst);
        remote.fail_direction.store(true, Ordering::SeqCst);
        assert!(
            matches!(
                s.update_direction(crate::SyncDirection::Upload).await,
                Err(MobileError::ProtocolUnsupported)
            ),
            "failed report must be surfaced"
        );
        assert!(
            matches!(
                s.send_clipboard().await,
                Err(MobileError::ProtocolUnsupported)
            ),
            "unacknowledged direction must deny data"
        );
        assert_eq!(
            platform.reads.load(Ordering::SeqCst),
            reads,
            "failed direction ACK must not read the clipboard"
        );
        assert!(remote.data.lock().unwrap().attempts.is_empty());
        remote.fail_direction.store(false, Ordering::SeqCst);
        s.check().await.unwrap();
        s.shutdown().await.unwrap();
    });
}

#[test]
fn protocol3_authorized_delivery_can_precede_the_global_revision() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::Disabled);
        s.check().await.unwrap();
        remote.publish("authorized synthetic delivery", false);
        remote.data.lock().unwrap().revision = 9;
        assert_eq!(
            s.receive().await.unwrap(),
            SyncOutcome::Downloaded,
            "global revision must not skip an older authorized delivery"
        );
        assert_eq!(s.status().await.unwrap().cursor, 1);
        assert_eq!(
            platform.clipboard.lock().unwrap().as_ref().unwrap().text,
            "authorized synthetic delivery"
        );
        s.shutdown().await.unwrap();
    });
}

#[test]
fn protocol3_errors_distinguish_an_incompatible_peer_from_retryable_network_errors() {
    assert_eq!(
        map_error(&SyncError::Remote(Error::Status(426))),
        MobileError::ProtocolUnsupported,
        "a protocol gate must not be reported as an ordinary network retry"
    );
    for (code, expected) in [
        (
            DirectionErrorCode::ProtocolUnsupported,
            MobileError::ProtocolUnsupported,
        ),
        (DirectionErrorCode::SessionRevoked, MobileError::Pairing),
        (DirectionErrorCode::SessionOffline, MobileError::Network),
        (DirectionErrorCode::RateLimited, MobileError::Network),
    ] {
        let error = SyncError::Remote(Error::Protocol(
            rustclipsync_core::direction::SyncDirectionError {
                code,
                version: None,
                minimum_protocol_version: Some(3),
            },
        ));
        assert_eq!(map_error(&error), expected, "classification for {code:?}");
    }
}

#[test]
fn protocol3_disabling_sensitive_content_keeps_ordinary_receives_working() {
    for direction in [crate::SyncDirection::Both, crate::SyncDirection::Download] {
        block_on(async {
            let platform = Arc::new(FakePlatform::default());
            let remote = Remote::default();
            let s = start(&platform, &remote, SensitivePolicy::AutoWrite);
            s.update_direction(direction).await.unwrap();
            s.check().await.unwrap();
            remote.publish("synthetic marked selection", true);
            assert_eq!(s.receive().await.unwrap(), SyncOutcome::Downloaded);
            assert!(
                platform
                    .clipboard
                    .lock()
                    .unwrap()
                    .as_ref()
                    .unwrap()
                    .sensitive
            );
            s.update_policy(SensitivePolicy::Disabled).await.unwrap();
            remote.publish("123456", false);
            assert_eq!(
                s.receive().await.unwrap(),
                SyncOutcome::Downloaded,
                "a marked local selection must not block an ordinary receive after disabling sensitive sync"
            );
            assert_eq!(
                platform.clipboard.lock().unwrap().as_ref().unwrap().text,
                "123456"
            );
            remote.publish("synthetic blocked remote", true);
            assert_eq!(s.receive().await.unwrap(), SyncOutcome::Filtered);
            assert_eq!(platform.writes.load(Ordering::SeqCst), 2);
            assert!(remote.data.lock().unwrap().sends.is_empty());
            s.shutdown().await.unwrap();
        });
    }
}

#[test]
fn background_sync_continues_owned_upload_and_obeys_all_directions_without_reading_clipboard() {
    block_on(async {
        for direction in [
            crate::SyncDirection::Both,
            crate::SyncDirection::Upload,
            crate::SyncDirection::Download,
        ] {
            let platform = Arc::new(FakePlatform::default());
            let remote = Remote::default();
            let s = start(&platform, &remote, SensitivePolicy::EncryptedInbox);
            // Background calls must never initialize a session by inspecting the clipboard.
            assert_eq!(
                s.sync_background(crate::BackgroundClipboardSource::PendingOnly)
                    .await
                    .unwrap()
                    .sent,
                SyncOutcome::Paused
            );
            s.check().await.unwrap();
            *platform.clipboard.lock().unwrap() =
                Some(text("synthetic pending background upload", false));
            remote.fail_next_send.store(true, Ordering::SeqCst);
            assert!(matches!(
                s.send_clipboard().await,
                Err(MobileError::Network)
            ));
            let attempts = remote.data.lock().unwrap().attempts.len();
            s.update_direction(direction).await.unwrap();
            let reads = platform.reads.load(Ordering::SeqCst);
            platform.forbid_clipboard.store(true, Ordering::SeqCst);
            s.sync_background(crate::BackgroundClipboardSource::PendingOnly)
                .await
                .unwrap();
            s.sync_background(crate::BackgroundClipboardSource::PendingOnly)
                .await
                .unwrap(); // Send/ack or cancellation finishes through core.
            assert_eq!(platform.reads.load(Ordering::SeqCst), reads);
            assert_eq!(
                remote.data.lock().unwrap().attempts.len(),
                attempts + usize::from(direction != crate::SyncDirection::Download)
            );
            remote.publish("synthetic background sensitive inbox", true);
            let tick = s
                .sync_background(crate::BackgroundClipboardSource::PendingOnly)
                .await
                .unwrap();
            if direction == crate::SyncDirection::Upload {
                assert_eq!(tick.received, SyncOutcome::Paused);
                assert!(s.status().await.unwrap().inbox.is_none());
            } else {
                assert_eq!(tick.received, SyncOutcome::Stored);
                assert!(s.status().await.unwrap().inbox.is_some());
            }
            assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
            s.shutdown().await.unwrap();
        }
    });
}

#[test]
fn privileged_background_capture_does_not_reupload_unchanged_content_or_replace_retry_uuid() {
    block_on(async {
        let platform = Arc::new(FakePlatform::default());
        let remote = Remote::default();
        let s = start(&platform, &remote, SensitivePolicy::EncryptedInbox);
        s.check().await.unwrap();
        *platform.clipboard.lock().unwrap() = Some(text("synthetic Shizuku copy", false));
        remote.fail_next_send.store(true, Ordering::SeqCst);
        assert!(matches!(
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await,
            Err(MobileError::Network)
        ));
        for _ in 0..4 {
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap();
        }
        {
            let data = remote.data.lock().unwrap();
            assert_eq!(data.sends, ["synthetic Shizuku copy"]);
            assert_eq!(data.attempts.len(), 2);
            assert_eq!(data.attempts[0], data.attempts[1]);
        }
        // A genuine new OS copy has a fresh platform observation marker, even for identical text.
        *platform.clipboard.lock().unwrap() = Some(ClipboardText {
            text: "synthetic Shizuku copy".into(),
            sensitive: false,
            origin: Some("synthetic-local:2".into()),
        });
        for _ in 0..3 {
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap();
        }
        assert_eq!(remote.data.lock().unwrap().sends.len(), 2);
        // An actual successful incoming OS write is an observation too, even after echo timeout.
        remote.publish("synthetic received ordinary", false);
        assert_eq!(
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap()
                .received,
            SyncOutcome::Downloaded
        );
        for _ in 0..3 {
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap();
        }
        assert_eq!(remote.data.lock().unwrap().sends.len(), 2);
        s.shutdown().await.unwrap();
    });
}

#[test]
fn unavailable_privileged_capture_preserves_receiving_and_sensitive_policy() {
    block_on(async {
        for policy in [
            SensitivePolicy::Disabled,
            SensitivePolicy::EncryptedInbox,
            SensitivePolicy::AutoWrite,
        ] {
            let platform = Arc::new(FakePlatform::default());
            let remote = Remote::default();
            let s = start(&platform, &remote, policy);
            s.check().await.unwrap();
            *platform.clipboard.lock().unwrap() =
                Some(text("synthetic already-owned upload", false));
            remote.fail_next_send.store(true, Ordering::SeqCst);
            assert!(matches!(
                s.send_clipboard().await,
                Err(MobileError::Network)
            ));
            platform.fail_read.store(true, Ordering::SeqCst);
            for _ in 0..2 {
                s.sync_background(crate::BackgroundClipboardSource::Privileged)
                    .await
                    .unwrap();
            }
            {
                let data = remote.data.lock().unwrap();
                assert_eq!(data.sends, ["synthetic already-owned upload"]);
                assert_eq!(data.attempts.len(), 2);
                assert_eq!(data.attempts[0], data.attempts[1]);
            }
            remote.publish("synthetic marked incoming", true);
            let result = s
                .sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap();
            assert_eq!(result.capture, crate::BackgroundCapture::Unavailable);
            assert_eq!(
                result.received,
                match policy {
                    SensitivePolicy::Disabled => SyncOutcome::Filtered,
                    SensitivePolicy::EncryptedInbox => SyncOutcome::Stored,
                    SensitivePolicy::AutoWrite => SyncOutcome::Downloaded,
                }
            );
            assert_eq!(
                s.status().await.unwrap().inbox.is_some(),
                policy == SensitivePolicy::EncryptedInbox
            );
            assert_eq!(
                platform.writes.load(Ordering::SeqCst),
                u64::from(policy == SensitivePolicy::AutoWrite)
            );
            if policy == SensitivePolicy::AutoWrite {
                assert!(
                    platform
                        .clipboard
                        .lock()
                        .unwrap()
                        .as_ref()
                        .unwrap()
                        .sensitive
                );
            }
            s.update_direction(crate::SyncDirection::Download)
                .await
                .unwrap();
            let reads = platform.reads.load(Ordering::SeqCst);
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap();
            assert_eq!(platform.reads.load(Ordering::SeqCst), reads);
            s.shutdown().await.unwrap();
        }
    });
}

#[test]
fn stale_upload_recovery_unblocks_receives_without_reuploading_the_old_clipboard() {
    for expired in [false, true] {
        block_on(async {
            let platform = Arc::new(FakePlatform::default());
            let remote = Remote::default();
            let s = start(&platform, &remote, SensitivePolicy::EncryptedInbox);
            s.check().await.unwrap();
            *platform.clipboard.lock().unwrap() = Some(text("synthetic stale local", false));
            remote.fail_next_send.store(true, Ordering::SeqCst);
            assert_eq!(s.send_clipboard().await, Err(MobileError::Network));
            s.shutdown().await.unwrap();
            if expired {
                let mut saved =
                    state::open(&[7; 32], platform.bytes.lock().unwrap().clone().unwrap()).unwrap();
                saved.state.pending.as_mut().unwrap().copy.created_at = 1;
                *platform.bytes.lock().unwrap() = Some(state::seal(&[7; 32], &saved).unwrap());
            }
            let s = start(&platform, &remote, SensitivePolicy::EncryptedInbox);
            s.check().await.unwrap();
            remote.publish("synthetic remote at recovery", true);
            remote.conflict_next_send.store(!expired, Ordering::SeqCst);
            let recovered = if expired {
                s.retry_pending().await.unwrap()
            } else {
                s.sync_background(crate::BackgroundClipboardSource::PendingOnly)
                    .await
                    .unwrap()
                    .sent
            };
            assert_eq!(recovered, SyncOutcome::Skipped, "expired={expired}");
            assert!(!s.status().await.unwrap().has_pending);
            assert_eq!(
                platform.clipboard.lock().unwrap().as_ref().unwrap().text,
                "synthetic stale local"
            );
            assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
            // Rebinding Shizuku must establish a baseline, not reinterpret the preserved old copy.
            s.sync_background(crate::BackgroundClipboardSource::Privileged)
                .await
                .unwrap();
            assert!(remote.data.lock().unwrap().sends.is_empty());
            remote.publish("synthetic fresh sensitive receive", true);
            assert_eq!(
                s.sync_background(crate::BackgroundClipboardSource::Privileged)
                    .await
                    .unwrap()
                    .received,
                SyncOutcome::Stored
            );
            assert!(s.status().await.unwrap().inbox.is_some());
            assert_eq!(platform.writes.load(Ordering::SeqCst), 0);
            s.shutdown().await.unwrap();
        });
    }
}
