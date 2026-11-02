//! Single-owner core executor with bounded admission and nonblocking close requests.
use crate::{
    InboxMetadata, MobileConfig, MobileError, Platform, SensitivePolicy, SessionStatus,
    SyncOutcome, adapters::Host,
};
use futures::{
    FutureExt,
    channel::oneshot,
    future::{BoxFuture, Shared},
};
use rustclipsync_core::{
    Error, Filter, FilterMode, Outcome,
    adapters::{Clipboard, NoHistory},
    client::Client,
    session::{Session, SessionConfig, SyncError},
    transport::Transport,
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender, TrySendError},
    },
    thread,
    time::Instant,
};

type CoreSession<R> = Session<Host, Host, NoHistory, R>;
type Reply = oneshot::Sender<Result<Response, MobileError>>;
const QUEUE_CAPACITY: usize = 16;

enum Command {
    Status,
    Check,
    Send,
    Retry,
    Receive,
    ReceiveInbox,
    ReceiveBackground(bool),
    BackgroundSync(crate::BackgroundClipboardSource),
    Policy(SensitivePolicy),
    Filter(crate::FilterConfig),
    Direction(crate::SyncDirection),
    ReadInbox(String),
    CopyInbox(String),
    ClearInbox(String),
    Wake,
}
enum Response {
    Background(crate::BackgroundSyncResult),
    Status(SessionStatus),
    Outcome(SyncOutcome),
    Text(Option<String>),
    Done,
}
struct Request {
    command: Command,
    reply: Reply,
}

/// Owns a serialized core session; explicit shutdown must precede replacing its storage owner.
#[derive(uniffi::Object)]
pub struct MobileSession {
    tx: SyncSender<Request>,
    closing: Arc<AtomicBool>,
    generation: Arc<AtomicU64>,
    finished: Shared<BoxFuture<'static, Result<(), MobileError>>>,
}

#[uniffi::export]
impl MobileSession {
    /// Starts an executor without doing network, disk, clipboard, or join work on the caller.
    ///
    /// # Errors
    /// Returns invalid input for an invalid key length, or internal on thread creation failure.
    #[uniffi::constructor]
    pub fn new(
        config: MobileConfig,
        platform: Arc<dyn Platform>,
    ) -> Result<Arc<Self>, MobileError> {
        Self::spawn(config, platform, |c| {
            Client::new(&c.server, c.username.clone(), c.password.clone(), false)
        })
    }

    /// Waits asynchronously for initialization and returns only durable metadata.
    ///
    /// # Errors
    /// Returns initialization, closed, or queue admission errors.
    pub async fn status(&self) -> Result<SessionStatus, MobileError> {
        match self.request(Command::Status).await? {
            Response::Status(v) => Ok(v),
            _ => Err(MobileError::Internal),
        }
    }

    /// Registers or checks the device using the core protocol.
    ///
    /// # Errors
    /// Returns sanitized core or lifecycle errors.
    pub async fn check(&self) -> Result<(), MobileError> {
        self.request(Command::Check).await.map(|_| ())
    }

    /// Takes a fresh platform snapshot, records it through core, and flushes the outbox.
    ///
    /// This never silently overrides a core conflict. Restored pending uploads are
    /// checked by core against the current clipboard before they can be sent.
    ///
    /// # Errors
    /// Returns clipboard, storage, network, conflict, or lifecycle errors.
    pub async fn send_clipboard(&self) -> Result<SyncOutcome, MobileError> {
        self.outcome(Command::Send).await
    }

    /// Retries core's saved transaction without recording another clipboard event or UUID.
    ///
    /// # Errors
    /// Returns network, conflict, expiry, clipboard, storage, or lifecycle errors from core.
    pub async fn retry_pending(&self) -> Result<SyncOutcome, MobileError> {
        self.outcome(Command::Retry).await
    }

    /// Receives through core, including its journal and sensitive policy rules.
    ///
    /// # Errors
    /// Returns sanitized core or lifecycle errors.
    pub async fn receive(&self) -> Result<SyncOutcome, MobileError> {
        self.outcome(Command::Receive).await
    }

    /// Receives marked messages into core's enabled inbox without accessing the clipboard.
    ///
    /// Ordinary messages and pending uploads await foreground handling. Pairing
    /// and restored-state validation must finish via `check` while foregrounded.
    ///
    /// # Errors
    /// Returns sanitized core transport, storage, validation, or lifecycle errors.
    pub async fn receive_inbox(&self) -> Result<SyncOutcome, MobileError> {
        self.outcome(Command::ReceiveInbox).await
    }

    /// Receives in the background without reading the clipboard.
    ///
    /// A separate platform opt-in must authorize `write_clipboard = true`.
    /// Marked messages still obey core's skip/inbox/auto-write policy. The host
    /// must revoke writes and invalidate generation on lifecycle/policy changes.
    ///
    /// # Errors
    /// Returns sanitized core transport, clipboard, storage, validation or lifecycle errors.
    pub async fn receive_background(
        &self,
        write_clipboard: bool,
    ) -> Result<SyncOutcome, MobileError> {
        self.outcome(Command::ReceiveBackground(write_clipboard))
            .await
    }

    /// Continue authorized background work in the configured direction.
    ///
    /// A successful foreground check must precede this call. PendingOnly never
    /// reads the clipboard. Privileged requires a user-authorized host adapter;
    /// a failed capture does not discard queued content or prevent receiving.
    /// All state transitions, retries, filtering and inbox handling remain in core.
    ///
    /// # Errors
    /// Returns sanitized core network, storage, conflict or lifecycle errors.
    pub async fn sync_background(
        &self,
        source: crate::BackgroundClipboardSource,
    ) -> Result<crate::BackgroundSyncResult, MobileError> {
        match self.request(Command::BackgroundSync(source)).await? {
            Response::Background(value) => Ok(value),
            _ => Err(MobileError::Internal),
        }
    }

    /// Updates explicit policy without replaying previously consumed messages.
    ///
    /// # Errors
    /// Returns closed or queue admission errors.
    pub async fn update_policy(&self, policy: SensitivePolicy) -> Result<(), MobileError> {
        self.request(Command::Policy(policy)).await.map(|_| ())
    }

    /// Change transfer admission on the owned executor.
    ///
    /// Selecting receive-only invalidates local upload candidates through core.
    /// Already committed requests cannot be undone by this preference.
    ///
    /// # Errors
    /// Returns core storage, lifecycle, or queue admission errors.
    pub async fn update_direction(
        &self,
        direction: crate::SyncDirection,
    ) -> Result<(), MobileError> {
        self.clipboard_changed();
        self.request(Command::Direction(direction))
            .await
            .map(|_| ())
    }

    /// Replace filtering rules through core on the session executor.
    ///
    /// Invalid rules leave the previous policy intact. Changed rules clear stale
    /// local candidates through core rather than sending them under a new policy.
    ///
    /// # Errors
    /// Returns invalid input, storage, lifecycle, or queue admission errors.
    pub async fn update_filter(&self, config: crate::FilterConfig) -> Result<(), MobileError> {
        self.request(Command::Filter(config)).await.map(|_| ())
    }

    /// Reads the sensitive inbox body only for an explicit UI request and matching identifier.
    ///
    /// # Errors
    /// Returns closed or queue admission errors.
    pub async fn read_inbox(&self, id: String) -> Result<Option<String>, MobileError> {
        match self.request(Command::ReadInbox(id)).await? {
            Response::Text(v) => Ok(v),
            _ => Err(MobileError::Internal),
        }
    }

    /// Copies a matching inbox item explicitly, preserving its sensitive mark.
    ///
    /// Successful copy does not acknowledge it as an automatic receive or clear it.
    ///
    /// # Errors
    /// Returns clipboard or lifecycle errors.
    pub async fn copy_inbox(&self, id: String) -> Result<(), MobileError> {
        self.request(Command::CopyInbox(id)).await.map(|_| ())
    }

    /// Deletes a matching inbox item through core's encrypted persistence.
    ///
    /// # Errors
    /// Returns storage or lifecycle errors.
    pub async fn clear_inbox(&self, id: String) -> Result<(), MobileError> {
        self.request(Command::ClearInbox(id)).await.map(|_| ())
    }

    /// Invalidates in-flight work when the platform clipboard changes; never reads its contents.
    pub fn clipboard_changed(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
    }

    /// Revokes command admission immediately; repeated calls are harmless and never join threads.
    pub fn request_close(&self) {
        self.closing.store(true, Ordering::SeqCst);
        self.clipboard_changed();
        let (reply, _) = oneshot::channel();
        let _ = self.tx.try_send(Request {
            command: Command::Wake,
            reply,
        });
    }

    /// Requests close and asynchronously waits until the executor and adapters have been released.
    ///
    /// Repeated and concurrent calls share completion. An in-flight bounded core
    /// request may finish; queued requests and late results return closed. Canceling
    /// this future does not cancel shutdown. Never synchronously wait for it on UI.
    ///
    /// # Errors
    /// Returns internal if the executor panicked; it must then be reopened.
    pub async fn shutdown(&self) -> Result<(), MobileError> {
        self.request_close();
        self.finished.clone().await
    }
}

#[cfg(feature = "disposable-tests")]
#[uniffi::export]
impl MobileSession {
    /// Opens an isolated loopback TLS fixture; omitted from ordinary builds and bindings.
    ///
    /// Only `https://127.0.0.1:<nonzero-port>/` is accepted. The self-signed
    /// certificate exception belongs exclusively to the disposable test artifact.
    ///
    /// # Errors
    /// Returns invalid input for other destinations, invalid keys, or internal on spawn failure.
    #[uniffi::constructor]
    pub fn for_disposable_test(
        config: MobileConfig,
        platform: Arc<dyn Platform>,
    ) -> Result<Arc<Self>, MobileError> {
        let port = config
            .server
            .strip_prefix("https://127.0.0.1:")
            .and_then(|value| value.strip_suffix('/'))
            .and_then(|value| value.parse::<std::num::NonZeroU16>().ok());
        if port.is_none() {
            return Err(MobileError::InvalidInput);
        }
        Self::spawn(config, platform, |c| {
            Client::new(&c.server, c.username.clone(), c.password.clone(), true)
        })
    }
}

impl MobileSession {
    fn spawn<R, F>(
        config: MobileConfig,
        platform: Arc<dyn Platform>,
        remote: F,
    ) -> Result<Arc<Self>, MobileError>
    where
        R: Transport + 'static,
        F: FnOnce(&MobileConfig) -> Result<R, Error> + Send + 'static,
    {
        if config.state_key.len() != 32 {
            return Err(MobileError::InvalidInput);
        }
        let (tx, rx) = mpsc::sync_channel::<Request>(QUEUE_CAPACITY);
        let closing = Arc::new(AtomicBool::new(false));
        let stopped = Arc::clone(&closing);
        let generation = Arc::new(AtomicU64::new(0));
        let observed_generation = Arc::clone(&generation);
        let (done_tx, done_rx) = oneshot::channel();
        thread::Builder::new()
            .name("rcs-mobile-session".into())
            .spawn(move || {
                let result = catch_unwind(AssertUnwindSafe(|| {
                    let host = Host {
                        platform,
                        observed_clipboard: Arc::default(),
                        closing: Arc::clone(&stopped),
                    };
                    let mut session = open(
                        config,
                        host.clone(),
                        Arc::clone(&observed_generation),
                        remote,
                    );
                    // Preserve the original failure chain on the Rust side, never expose its text.
                    let mut last_failure = None;
                    while let Ok(request) = rx.recv() {
                        if stopped.load(Ordering::SeqCst) {
                            break;
                        }
                        let result = match &mut session {
                            Ok(core) => execute(core, &host, &observed_generation, request.command),
                            Err(error) => Err(map_error(error)),
                        };
                        let fatal = matches!(result, Err(MobileError::Storage));
                        let response = if stopped.load(Ordering::SeqCst) {
                            Err(MobileError::Closed)
                        } else {
                            result
                        };
                        let _ = request.reply.send(response);
                        if let Ok(core) = &mut session {
                            last_failure = core.failure.take().or(last_failure);
                        }
                        if fatal || session.is_err() {
                            break;
                        }
                    }
                    drop(last_failure);
                }));
                stopped.store(true, Ordering::SeqCst);
                // The closure's session and foreign adapters have dropped before completion.
                let _ = done_tx.send(result.map_err(|_| MobileError::Internal));
            })
            .map_err(|_| MobileError::Internal)?;
        Ok(Arc::new(Self {
            tx,
            closing,
            generation,
            finished: async move { done_rx.await.unwrap_or(Err(MobileError::Internal)) }
                .boxed()
                .shared(),
        }))
    }

    async fn request(&self, command: Command) -> Result<Response, MobileError> {
        if self.closing.load(Ordering::SeqCst) {
            return Err(MobileError::Closed);
        }
        let (reply, receiver) = oneshot::channel();
        self.tx
            .try_send(Request { command, reply })
            .map_err(|error| match error {
                TrySendError::Full(_) => MobileError::Busy,
                TrySendError::Disconnected(_) => MobileError::Closed,
            })?;
        receiver.await.unwrap_or(Err(MobileError::Closed))
    }
    async fn outcome(&self, command: Command) -> Result<SyncOutcome, MobileError> {
        match self.request(command).await? {
            Response::Outcome(v) => Ok(v),
            _ => Err(MobileError::Internal),
        }
    }
}

impl Drop for MobileSession {
    fn drop(&mut self) {
        self.request_close();
    }
}

struct Engine<R> {
    background_ready: bool,
    direction: crate::SyncDirection,
    filter_config: crate::FilterConfig,
    policy: SensitivePolicy,
    session: CoreSession<R>,
    failure: Option<SyncError>,
}

fn open<R: Transport, F: FnOnce(&MobileConfig) -> Result<R, Error>>(
    config: MobileConfig,
    host: Host,
    generation: Arc<AtomicU64>,
    remote: F,
) -> Result<Engine<R>, SyncError> {
    let client = Arc::new(remote(&config).map_err(SyncError::Remote)?);
    let key: [u8; 32] = config
        .state_key
        .as_slice()
        .try_into()
        .map_err(|_| SyncError::Credentials)?;
    let policy = config.sensitive_policy;
    let mut session = Session::open(
        SessionConfig {
            server: config.server,
            encryption_key: key,
            filter: filter(),
            allow_sensitive: policy != SensitivePolicy::Disabled,
        },
        client,
        host.clone(),
        host,
        NoHistory,
        generation,
    )?;
    apply_policy(&mut session, policy);
    Ok(Engine {
        background_ready: false,
        direction: crate::SyncDirection::Both,
        filter_config: crate::FilterConfig::default(),
        policy,
        session,
        failure: None,
    })
}

fn filter() -> Filter {
    // An empty RegexSet is always valid; no user-supplied pattern is compiled here.
    Filter::new(FilterMode::Off, &[], false).expect("empty core filter must compile")
}
fn apply_policy<R: Transport>(session: &mut CoreSession<R>, policy: SensitivePolicy) {
    session.update_policy(filter(), policy != SensitivePolicy::Disabled);
    session.use_sensitive_inbox(policy == SensitivePolicy::EncryptedInbox);
}

fn execute<R: Transport>(
    engine: &mut Engine<R>,
    host: &Host,
    generation: &AtomicU64,
    command: Command,
) -> Result<Response, MobileError> {
    if matches!(
        (&command, engine.direction),
        (Command::Send, crate::SyncDirection::Download)
            | (
                Command::Receive | Command::ReceiveInbox | Command::ReceiveBackground(_),
                crate::SyncDirection::Upload
            )
    ) {
        return Ok(Response::Outcome(SyncOutcome::Paused));
    }
    let mut prepared_filter = match &command {
        Command::Filter(config) => Some(crate::filtering::compile(config)?),
        Command::Policy(_) => Some(crate::filtering::compile(&engine.filter_config)?),
        _ => None,
    };
    let observed = generation.load(Ordering::SeqCst);
    let session = &mut engine.session;
    let result = (|| -> Result<Response, SyncError> {
        Ok(match command {
            Command::Status => Response::Status(SessionStatus {
                device_id: session.persisted.state.device_id.clone(),
                paired: session.persisted.paired,
                cursor: session.persisted.state.cursor,
                has_pending: session.has_pending(),
                inbox: session.persisted.inbox.as_ref().map(|item| InboxMetadata {
                    id: item.id.clone(),
                    received_at: item.received_at,
                }),
            }),
            Command::Check => {
                session.check()?;
                engine.background_ready = true;
                Response::Done
            }
            Command::Send => {
                let snapshot = host.read_snapshot().map_err(SyncError::Clipboard)?;
                let recorded = match &snapshot {
                    Some(value) => session.record_local(
                        &value.text,
                        value.origin.as_deref(),
                        value.sensitive,
                        Instant::now(),
                    )?,
                    None => session.record_clear()?,
                };
                host.remember_snapshot(snapshot)
                    .map_err(SyncError::Clipboard)?;
                let flushed = session.flush(observed)?;
                Response::Outcome(outcome(if matches!(recorded, Outcome::Filtered) {
                    recorded
                } else {
                    flushed
                }))
            }
            Command::Receive => Response::Outcome(outcome(session.pull(observed, Instant::now())?)),
            Command::ReceiveInbox => Response::Outcome(outcome(session.pull_inbox(observed)?)),
            Command::ReceiveBackground(write) => {
                Response::Outcome(outcome(session.pull_background(observed, write)?))
            }
            Command::BackgroundSync(source) => {
                use crate::{
                    BackgroundCapture, BackgroundClipboardSource, BackgroundSyncResult,
                    SyncDirection,
                };
                if !engine.background_ready {
                    return Ok(Response::Background(BackgroundSyncResult {
                        sent: SyncOutcome::Paused,
                        received: SyncOutcome::Paused,
                        capture: BackgroundCapture::NotRequested,
                    }));
                }
                let capture = if source == BackgroundClipboardSource::Privileged
                    && engine.direction != SyncDirection::Download
                {
                    match host.read_snapshot() {
                        Ok(value) => {
                            if host
                                .snapshot_changed(&value)
                                .map_err(SyncError::Clipboard)?
                            {
                                match &value {
                                    Some(value) => {
                                        session.record_local(
                                            &value.text,
                                            value.origin.as_deref(),
                                            value.sensitive,
                                            Instant::now(),
                                        )?;
                                    }
                                    None => {
                                        session.record_clear()?;
                                    }
                                }
                                host.remember_snapshot(value)
                                    .map_err(SyncError::Clipboard)?;
                            }
                            BackgroundCapture::Captured
                        }
                        Err(_) => BackgroundCapture::Unavailable,
                    }
                } else {
                    BackgroundCapture::NotRequested
                };
                if engine.direction == SyncDirection::Download
                    && (session.persisted.state.pending.is_some()
                        || session.persisted.state.queued.is_some())
                {
                    session.record_clear()?;
                }
                let sent = outcome(session.flush(observed)?);
                let received = if engine.direction == SyncDirection::Upload {
                    SyncOutcome::Paused
                } else {
                    outcome(session.pull_background(observed, true)?)
                };
                Response::Background(BackgroundSyncResult {
                    sent,
                    received,
                    capture,
                })
            }
            Command::Retry => {
                if engine.direction == crate::SyncDirection::Download {
                    session.record_clear()?;
                }
                Response::Outcome(outcome(session.flush(observed)?))
            }
            Command::Direction(direction) => {
                if direction == crate::SyncDirection::Download && direction != engine.direction {
                    session.record_clear()?;
                }
                engine.direction = direction;
                Response::Done
            }
            Command::Policy(policy) => {
                session.update_policy(
                    prepared_filter
                        .take()
                        .expect("policy command has a validated filter"),
                    policy != SensitivePolicy::Disabled,
                );
                session.use_sensitive_inbox(policy == SensitivePolicy::EncryptedInbox);
                engine.policy = policy;
                Response::Done
            }
            Command::Filter(config) => {
                if config != engine.filter_config {
                    session.record_clear()?;
                    session.update_policy(
                        prepared_filter
                            .take()
                            .expect("filter command has a validated filter"),
                        engine.policy != SensitivePolicy::Disabled,
                    );
                    engine.filter_config = config;
                }
                Response::Done
            }
            Command::ReadInbox(id) => Response::Text(
                session
                    .persisted
                    .inbox
                    .as_ref()
                    .filter(|v| v.id == id)
                    .map(|v| v.text.clone()),
            ),
            Command::CopyInbox(id) => {
                if let Some(item) = session.persisted.inbox.as_ref().filter(|v| v.id == id) {
                    host.write(&item.text, &item.id, true)
                        .map_err(SyncError::Clipboard)?;
                }
                Response::Done
            }
            Command::ClearInbox(id) => {
                session.clear_inbox(&id)?;
                Response::Done
            }
            Command::Wake => Response::Done,
        })
    })();
    result.map_err(|error| {
        let mapped = map_error(&error);
        engine.failure = Some(error);
        mapped
    })
}

fn map_error(error: &SyncError) -> MobileError {
    match error {
        SyncError::Storage(_) => MobileError::Storage,
        SyncError::Clipboard(_) => MobileError::Clipboard,
        SyncError::Credentials | SyncError::Epoch => MobileError::Pairing,
        SyncError::Conflict => MobileError::Conflict,
        SyncError::Expired => MobileError::Expired,
        SyncError::Remote(error) => match error {
            Error::InvalidUrl | Error::TooLarge => MobileError::InvalidInput,
            Error::Status(401 | 403) => MobileError::Pairing,
            Error::Status(409) => MobileError::Conflict,
            Error::Http(_) | Error::Io(_) | Error::Status(_) => MobileError::Network,
            Error::Json(_) | Error::InvalidProfile | Error::HashMismatch | Error::Filtered => {
                MobileError::Protocol
            }
        },
    }
}
fn outcome(value: Outcome) -> SyncOutcome {
    match value {
        Outcome::Stored => SyncOutcome::Stored,
        Outcome::Uploaded => SyncOutcome::Uploaded,
        Outcome::Current => SyncOutcome::Current,
        Outcome::Downloaded => SyncOutcome::Downloaded,
        Outcome::Skipped => SyncOutcome::Skipped,
        Outcome::Filtered => SyncOutcome::Filtered,
        Outcome::Paused => SyncOutcome::Paused,
        Outcome::Unsupported => SyncOutcome::Unsupported,
        Outcome::Invalid => SyncOutcome::Invalid,
        Outcome::Capacity => SyncOutcome::Capacity,
    }
}

#[cfg(test)]
mod tests;
