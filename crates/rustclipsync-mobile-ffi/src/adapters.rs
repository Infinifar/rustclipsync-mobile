//! Synchronous foreign adapters called only from the session executor.
use crate::{AdapterError, ClipboardText};
use rustclipsync_core::{
    MAX_TEXT_BYTES,
    adapters::{Clipboard, ClipboardError, StateStore},
    state::MAX_STATE,
};
use std::{
    io,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

/// Platform operations; implementations must be bounded and must not retain UI objects.
///
/// Clipboard implementations dispatch to the UI thread, wait only on this worker,
/// and revoke expired/closed requests before they touch the OS clipboard. Storage
/// must preserve the exact core ciphertext with atomic, durable replacement.
#[uniffi::export(with_foreign)]
pub trait Platform: Send + Sync {
    /// Return a fresh snapshot, never an old cached value when OS access fails.
    fn clipboard_snapshot(&self) -> Result<Option<ClipboardText>, AdapterError>;
    /// Return success only after the OS accepts text and metadata.
    fn clipboard_write(&self, value: ClipboardText) -> Result<(), AdapterError>;
    /// None means absent state only; errors must not create a new identity.
    fn load_state(&self) -> Result<Option<Vec<u8>>, AdapterError>;
    /// Replace exact encrypted bytes; failures terminate the current session.
    fn save_state(&self, ciphertext: Vec<u8>) -> Result<(), AdapterError>;
}

#[derive(Clone)]
pub(crate) struct Host {
    pub platform: Arc<dyn Platform>,
    pub closing: Arc<AtomicBool>,
    // Session-local observation only; core owns outbox IDs, retries and persistence.
    pub observed_clipboard: Arc<Mutex<Option<Option<ClipboardText>>>>,
}

impl Host {
    pub fn snapshot_changed(&self, value: &Option<ClipboardText>) -> Result<bool, ClipboardError> {
        let previous = self
            .observed_clipboard
            .lock()
            .map_err(|_| ClipboardError::Platform)?;
        Ok(previous.as_ref() != Some(value))
    }

    pub fn remember_snapshot(&self, value: Option<ClipboardText>) -> Result<(), ClipboardError> {
        *self
            .observed_clipboard
            .lock()
            .map_err(|_| ClipboardError::Platform)? = Some(value);
        Ok(())
    }

    pub fn read_snapshot(&self) -> Result<Option<ClipboardText>, ClipboardError> {
        if self.closing.load(Ordering::SeqCst) {
            return Err(ClipboardError::Unavailable);
        }
        let value = self
            .platform
            .clipboard_snapshot()
            .map_err(clipboard_error)?;
        if value
            .as_ref()
            .is_some_and(|v| v.text.len() > MAX_TEXT_BYTES)
        {
            return Err(ClipboardError::TooLarge);
        }
        Ok(value)
    }
}

impl Clipboard for Host {
    fn snapshot(&self, allow_sensitive: bool) -> Result<Option<(String, bool)>, ClipboardError> {
        let value = self.read_snapshot()?;
        if !allow_sensitive && value.as_ref().is_some_and(|v| v.sensitive) {
            return Err(ClipboardError::Sensitive);
        }
        Ok(value.map(|v| (v.text, v.sensitive)))
    }
    fn write(&self, text: &str, message_id: &str, sensitive: bool) -> Result<(), ClipboardError> {
        if self.closing.load(Ordering::SeqCst) {
            return Err(ClipboardError::Unavailable);
        }
        let value = ClipboardText {
            text: text.to_owned(),
            sensitive,
            origin: Some(message_id.to_owned()),
        };
        self.platform
            .clipboard_write(value.clone())
            .map_err(clipboard_error)?;
        // A successful receive/copy is already observed, even if the OS listener fires late.
        self.remember_snapshot(Some(value))
    }
}

impl StateStore for Host {
    fn load(&self) -> io::Result<Option<Vec<u8>>> {
        let value = self.platform.load_state().map_err(io::Error::other)?;
        if value
            .as_ref()
            .is_some_and(|bytes| bytes.len() as u64 > MAX_STATE)
        {
            return Err(io::Error::other("state too large"));
        }
        Ok(value)
    }
    fn save(&self, ciphertext: &[u8]) -> io::Result<()> {
        // Persistence needed to finish an in-flight transaction is allowed during close.
        self.platform
            .save_state(ciphertext.to_vec())
            .map_err(io::Error::other)
    }
}

fn clipboard_error(error: AdapterError) -> ClipboardError {
    match error {
        AdapterError::Timeout => ClipboardError::Timeout,
        AdapterError::Sensitive => ClipboardError::Sensitive,
        AdapterError::Unavailable => ClipboardError::Unavailable,
        AdapterError::Storage | AdapterError::Platform => ClipboardError::Platform,
    }
}
