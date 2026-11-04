//! Sampling parameters, validated before they reach llama.cpp.

use crate::error::{NeuronError, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SamplingParams {
    pub temperature: f32,
    pub top_p: f32,
    pub top_k: i32,
    pub min_p: f32,
    pub repeat_penalty: f32,
    /// Window (in tokens) the repeat penalty looks back over. llama.cpp CLI default.
    pub penalty_last_n: i32,
    /// `None` = fresh random seed per generation.
    pub seed: Option<u32>,
}

impl Default for SamplingParams {
    fn default() -> Self {
        Self {
            temperature: 0.7,
            top_p: 0.9,
            top_k: 40,
            min_p: 0.05,
            repeat_penalty: 1.1,
            penalty_last_n: 64,
            seed: None,
        }
    }
}

impl SamplingParams {
    pub fn validate(&self) -> Result<()> {
        let bad = |what: &str| Err(NeuronError::InvalidConfig(format!("sampling: {what}")));
        if !(0.0..=5.0).contains(&self.temperature) || self.temperature.is_nan() {
            return bad("temperature must be within 0..=5");
        }
        if !(0.0..=1.0).contains(&self.top_p) {
            return bad("top_p must be within 0..=1");
        }
        if !(0.0..=1.0).contains(&self.min_p) {
            return bad("min_p must be within 0..=1");
        }
        if self.top_k < 0 {
            return bad("top_k must be >= 0");
        }
        if !(0.0..=10.0).contains(&self.repeat_penalty) {
            return bad("repeat_penalty must be within 0..=10");
        }
        Ok(())
    }

    /// Greedy decoding is "temperature zero"; llama.cpp wants an explicit greedy sampler.
    pub fn is_greedy(&self) -> bool {
        self.temperature <= f32::EPSILON
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_validate() {
        SamplingParams::default().validate().unwrap();
    }

    #[test]
    fn rejects_out_of_range() {
        assert!(SamplingParams {
            temperature: -1.0,
            ..Default::default()
        }
        .validate()
        .is_err());
        assert!(SamplingParams {
            top_p: 1.5,
            ..Default::default()
        }
        .validate()
        .is_err());
        assert!(SamplingParams {
            top_k: -3,
            ..Default::default()
        }
        .validate()
        .is_err());
        assert!(SamplingParams {
            temperature: f32::NAN,
            ..Default::default()
        }
        .validate()
        .is_err());
    }

    #[test]
    fn zero_temperature_is_greedy() {
        assert!(SamplingParams {
            temperature: 0.0,
            ..Default::default()
        }
        .is_greedy());
        assert!(!SamplingParams::default().is_greedy());
    }
}
