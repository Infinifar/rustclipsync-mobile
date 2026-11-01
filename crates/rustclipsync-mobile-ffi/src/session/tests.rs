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
    fail_save: AtomicBool,
    fail_write: AtomicBool,
    threads: Mutex<Vec<thread::ThreadId>>,
}
impl Platform for FakePlatform {
    fn clipboard_snapshot(&self) -> Result<Option<ClipboardText>, AdapterError> {
        Ok(self.clipboard.lock().unwrap().clone())
    }
    fn clipboard_write(&self, value: ClipboardText) -> Result<(), AdapterError> {
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
}
type StateGate = (Sender<()>, Receiver<()>);

#[derive(Clone, Default)]
struct Remote {
    data: Arc<Mutex<Data>>,
    gate: Arc<Mutex<Option<StateGate>>>,
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
