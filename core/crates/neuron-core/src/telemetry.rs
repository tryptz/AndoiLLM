use crate::thermal::ThermalState;
use serde::{Deserialize, Serialize};

/// Live numbers the UI shows while a model is loaded or generating.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct Telemetry {
    /// Instantaneous decode speed of the last token.
    pub tok_sec: f32,
    /// Average decode speed over the current generation.
    pub avg_tok_sec: f32,
    pub tokens_generated: u32,
    /// Tokens currently resident in the KV cache (prompt + generated).
    pub context_used: u32,
    pub context_size: u32,
    pub cpu_temp_c: Option<f32>,
    pub thermal_state: ThermalState,
    pub available_ram_mb: u32,
}
