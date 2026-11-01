//! Binding-safe values; status and errors never carry clipboard text or credentials.

/// Handling of explicitly marked sensitive content; unmarked text is ordinary text.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SensitivePolicy {
    /// Skip marked sends and receives.
    Disabled,
    /// Allow sends; retain marked receives in core's encrypted inbox.
    EncryptedInbox,
    /// Explicit opt-in to actual platform clipboard writes.
    AutoWrite,
}

/// Credentials and platform state key; callers must never log this record.
#[derive(uniffi::Record)]
pub struct MobileConfig {
    /// HTTPS server endpoint without embedded credentials.
    pub server: String,
    /// Account name used by the core HTTPS client.
    pub username: String,
    /// Account secret, held in memory only by this session.
    pub password: String,
    /// Exactly 32 bytes, supplied by Keystore or Keychain adaptation.
    pub state_key: Vec<u8>,
    /// Explicit sensitive content policy.
    pub sensitive_policy: SensitivePolicy,
}

/// A platform clipboard snapshot, including only trusted source metadata.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ClipboardText {
    /// UTF-8 text, limited by core's maximum size.
    pub text: String,
    /// True only for an explicit source mark or user choice.
    pub sensitive: bool,
    /// Origin set when this application wrote a received message.
    pub origin: Option<String>,
}

/// Inbox metadata available without exposing its sensitive body.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct InboxMetadata {
    /// Identifier used for explicit copy or deletion.
    pub id: String,
    /// Core's receive time in Unix seconds.
    pub received_at: u64,
}

/// Metadata returned after a serialized operation.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SessionStatus {
    /// Stable core device UUID, not the registration token or credentials.
    pub device_id: String,
    /// Whether device registration has completed.
    pub paired: bool,
    /// Latest durable consumed revision.
    pub cursor: u64,
    /// Pending upload or acknowledgement retained by core.
    pub has_pending: bool,
    /// Metadata only; read/copy requires a separate explicit operation.
    pub inbox: Option<InboxMetadata>,
}

/// The core operation result, without body or remote error strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SyncOutcome {
    /// Retained in the encrypted inbox.
    Stored,
    /// Uploaded successfully.
    Uploaded,
    /// Already current.
    Current,
    /// Written to the OS clipboard successfully.
    Downloaded,
    /// Superseded, ambiguous, or otherwise skipped by core.
    Skipped,
    /// Rejected by sensitive or text policy.
    Filtered,
    /// Synchronization paused.
    Paused,
    /// Unsupported input.
    Unsupported,
    /// Invalid input.
    Invalid,
    /// Capacity exceeded.
    Capacity,
}

/// Sanitized adapter failures; never embed platform exception messages.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum AdapterError {
    /// Platform clipboard cannot currently be used.
    #[error("clipboard unavailable")]
    Unavailable,
    /// A main-thread request expired and must not execute later.
    #[error("clipboard request timed out")]
    Timeout,
    /// Marked content cannot be read under current policy.
    #[error("sensitive content disabled")]
    Sensitive,
    /// State/key cannot be read or durably replaced.
    #[error("secure storage failed")]
    Storage,
    /// A foreign implementation threw an unexpected error.
    #[error("platform operation failed")]
    Platform,
}

impl From<uniffi::UnexpectedUniFFICallbackError> for AdapterError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Platform
    }
}

/// Stable, safe-to-display failures returned by the mobile boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum MobileError {
    /// No further work is accepted; reopen after shutdown completes.
    #[error("session closed")]
    Closed,
    /// The bounded command queue is full.
    #[error("session busy")]
    Busy,
    /// Invalid HTTPS endpoint, key length, or input size.
    #[error("invalid configuration or input")]
    InvalidInput,
    /// Storage failed; this session is stopped and must reopen from durable state.
    #[error("secure state unavailable; reopen session")]
    Storage,
    /// Clipboard access failed or is unavailable.
    #[error("clipboard unavailable")]
    Clipboard,
    /// Authentication, registration, or epoch changed.
    #[error("pairing required")]
    Pairing,
    /// Core requires an explicit conflict decision.
    #[error("synchronization conflict")]
    Conflict,
    /// Core's retained upload expired.
    #[error("pending upload expired")]
    Expired,
    /// A bounded network request failed.
    #[error("network unavailable")]
    Network,
    /// Server data failed core validation.
    #[error("invalid server response")]
    Protocol,
    /// An internal failure stopped the executor.
    #[error("session failed; reopen session")]
    Internal,
}

/// Desktop-compatible admission of new clipboard transfers.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Enum)]
pub enum SyncDirection {
    /// Send and receive.
    #[default]
    Both,
    /// Send only; no remote content is consumed.
    Upload,
    /// Receive only; old upload candidates are cleared through core.
    Download,
}
