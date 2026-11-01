//! Shared mobile session boundary; all blocking core operations run on one owned thread.
mod adapters;
mod filtering;
mod session;
mod types;

pub use adapters::Platform;
pub use filtering::{FilterConfig, TextFilterMode, test_filter};
pub use session::MobileSession;
pub use types::{
    AdapterError, ClipboardText, InboxMetadata, MobileConfig, MobileError, SensitivePolicy,
    SessionStatus, SyncDirection, SyncOutcome,
};

uniffi::setup_scaffolding!();
