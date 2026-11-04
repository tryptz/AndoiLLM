//! # neuron-core
//!
//! The inference heart of Neuron, written in Rust. It drives llama.cpp through
//! [`llama-cpp-2`](https://crates.io/crates/llama-cpp-2) and knows about the
//! Snapdragon 8 Elite Gen 5 (OnePlus 15): the Hexagon HTP v81 NPU reached via
//! the `ggml-hexagon` backend, the Adreno 840 GPU via the `ggml-opencl` backend,
//! and the Oryon CPU as the universal fallback.
//!
//! Everything that does not need a model file is pure Rust and unit-tested on
//! the host: backend policy, SoC detection tables, GGUF header parsing, chat
//! templates, thermal policy, sampling parameters.
//!
//! The engine itself lives behind the `llama` feature so the pure modules can
//! be tested without compiling llama.cpp.

pub mod backend;
pub mod error;
pub mod gguf;
pub mod sampling;
pub mod soc;
pub mod sysinfo;
pub mod telemetry;
pub mod template;
pub mod thermal;

#[cfg(feature = "llama")]
pub mod engine;

pub use backend::{Backend, BackendRequest, DeviceSummary};
pub use error::NeuronError;
pub use sampling::SamplingParams;
pub use soc::SocInfo;
pub use telemetry::Telemetry;
pub use template::{ChatTemplate, Message, ReasoningEffort, Role};
pub use thermal::{ThermalPolicy, ThermalState};

/// Semantic version of the core, surfaced to the app for diagnostics.
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");
