//! Thermal classification and throttling policy.
//!
//! Thresholds are the single source of truth for both the Rust generation loop
//! (which sleeps between tokens when hot) and the Kotlin UI (which colours the
//! thermal indicator); the Kotlin copy mirrors these constants and is tested
//! against the same values.

use serde::{Deserialize, Serialize};
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum ThermalState {
    #[default]
    Nominal,
    Warm,
    Hot,
    Critical,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum ThermalPolicy {
    /// Stop decoding for a second whenever the SoC is critical.
    Pause,
    /// Insert a short pause per token once the SoC is hot.
    #[default]
    SlowDown,
    Ignore,
}

pub const WARM_THRESHOLD_C: f32 = 65.0;
pub const HOT_THRESHOLD_C: f32 = 75.0;
pub const CRITICAL_THRESHOLD_C: f32 = 85.0;

pub fn classify(temp_c: Option<f32>) -> ThermalState {
    match temp_c {
        None => ThermalState::Nominal,
        Some(t) if t > CRITICAL_THRESHOLD_C => ThermalState::Critical,
        Some(t) if t > HOT_THRESHOLD_C => ThermalState::Hot,
        Some(t) if t > WARM_THRESHOLD_C => ThermalState::Warm,
        Some(_) => ThermalState::Nominal,
    }
}

/// How long to pause after a token under `policy` at `temp_c`.
pub fn throttle_delay(policy: ThermalPolicy, temp_c: Option<f32>) -> Option<Duration> {
    let t = temp_c?;
    match policy {
        ThermalPolicy::Pause if t > CRITICAL_THRESHOLD_C => Some(Duration::from_millis(1000)),
        ThermalPolicy::SlowDown if t > HOT_THRESHOLD_C => Some(Duration::from_millis(50)),
        _ => None,
    }
}

/// Pause needed to hold `current_tok_sec` under `cap` (0 = uncapped).
pub fn tok_sec_cap_delay(current_tok_sec: f32, cap: u32) -> Option<Duration> {
    if cap == 0 || current_tok_sec <= cap as f32 {
        return None;
    }
    Some(Duration::from_secs_f32(1.0 / cap as f32))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classification_boundaries_are_exclusive() {
        assert_eq!(classify(None), ThermalState::Nominal);
        assert_eq!(classify(Some(40.0)), ThermalState::Nominal);
        assert_eq!(classify(Some(65.0)), ThermalState::Nominal);
        assert_eq!(classify(Some(65.1)), ThermalState::Warm);
        assert_eq!(classify(Some(75.0)), ThermalState::Warm);
        assert_eq!(classify(Some(75.1)), ThermalState::Hot);
        assert_eq!(classify(Some(85.0)), ThermalState::Hot);
        assert_eq!(classify(Some(85.1)), ThermalState::Critical);
        assert_eq!(classify(Some(120.0)), ThermalState::Critical);
    }

    #[test]
    fn slow_down_only_kicks_in_when_hot() {
        assert_eq!(throttle_delay(ThermalPolicy::SlowDown, Some(70.0)), None);
        assert_eq!(
            throttle_delay(ThermalPolicy::SlowDown, Some(80.0)),
            Some(Duration::from_millis(50))
        );
    }

    #[test]
    fn pause_only_kicks_in_when_critical() {
        assert_eq!(throttle_delay(ThermalPolicy::Pause, Some(80.0)), None);
        assert_eq!(
            throttle_delay(ThermalPolicy::Pause, Some(90.0)),
            Some(Duration::from_millis(1000))
        );
    }

    #[test]
    fn ignore_and_unknown_never_throttle() {
        assert_eq!(throttle_delay(ThermalPolicy::Ignore, Some(99.0)), None);
        assert_eq!(throttle_delay(ThermalPolicy::Pause, None), None);
    }

    #[test]
    fn tok_sec_cap() {
        assert_eq!(tok_sec_cap_delay(50.0, 0), None);
        assert_eq!(tok_sec_cap_delay(15.0, 20), None);
        assert_eq!(
            tok_sec_cap_delay(40.0, 20),
            Some(Duration::from_secs_f32(0.05))
        );
    }
}
