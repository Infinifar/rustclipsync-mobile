//! Shared mobile session boundary; all blocking core operations run on one owned thread.
mod adapters;
mod session;
mod types;

pub use adapters::Platform;
pub use session::MobileSession;
pub use types::{
    AdapterError, ClipboardText, InboxMetadata, MobileConfig, MobileError, SensitivePolicy,
    SessionStatus, SyncOutcome,
};

uniffi::setup_scaffolding!();
