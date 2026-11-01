//! Bounded binding input; the core remains the sole regex implementation.
use crate::MobileError;
use rustclipsync_core::{Filter, FilterMode};

/// Desktop-compatible text matching policy.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Enum)]
pub enum TextFilterMode {
    /// Do not reject content by regex.
    #[default]
    Off,
    /// Reject content matching any expression.
    Blacklist,
    /// Allow content matching at least one expression.
    Whitelist,
}

/// Non-secret user rules; never contains clipboard samples.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct FilterConfig {
    /// How a core regex match affects admission.
    pub mode: TextFilterMode,
    /// At most 32 Rust regular expressions, each at most 512 UTF-8 bytes.
    pub patterns: Vec<String>,
    /// Also apply rules to incoming messages.
    pub downloads: bool,
}

pub(crate) fn compile(config: &FilterConfig) -> Result<Filter, MobileError> {
    if config.patterns.len() > 32 || config.patterns.iter().any(|p| p.len() > 512) {
        return Err(MobileError::InvalidInput);
    }
    let mode = match config.mode {
        TextFilterMode::Off => FilterMode::Off,
        TextFilterMode::Blacklist => FilterMode::Blacklist,
        TextFilterMode::Whitelist => FilterMode::Whitelist,
    };
    Filter::new(mode, &config.patterns, config.downloads).map_err(|_| MobileError::InvalidInput)
}

/// Test a synthetic sample using the same core filter as synchronization.
///
/// Call off the UI thread. Samples are not stored or logged.
///
/// # Errors
/// Returns invalid input for oversized samples or invalid/oversized rules.
#[uniffi::export]
pub fn test_filter(
    config: FilterConfig,
    sample: String,
    download: bool,
) -> Result<bool, MobileError> {
    if sample.len() > 8192 {
        return Err(MobileError::InvalidInput);
    }
    Ok(compile(&config)?.allows(&sample, download))
}
