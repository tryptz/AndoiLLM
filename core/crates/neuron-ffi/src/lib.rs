//! # libneuron — the Kotlin-facing surface
//!
//! Everything here is a thin, typed façade over `neuron-core` and
//! `neuron-sandbox`. UniFFI generates the Kotlin side (package
//! `com.tryptz.neuron.core`) from these declarations at build time.
//!
//! Design rules:
//! * Records/enums are plain data; the only object is [`NeuronEngine`].
//! * The engine lives behind a mutex, so loads and generations serialize.
//!   `cancel()` is lock-free so a running generation can always be stopped.
//! * Streaming goes through the [`TokenSink`] callback interface, implemented
//!   in Kotlin; returning `false` stops the generation.

mod logging;

use neuron_core::engine as core_engine;
use neuron_core::engine::{CancelToken, Engine};
use parking_lot::Mutex;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

uniffi::setup_scaffolding!("neuron");

// ───────────────────────── errors ─────────────────────────

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum NeuronError {
    #[error("{0}")]
    NoModel(String),
    #[error("{0}")]
    Busy(String),
    #[error("{0}")]
    Cancelled(String),
    #[error("{0}")]
    ModelNotFound(String),
    #[error("{0}")]
    ModelLoad(String),
    #[error("{0}")]
    PromptTooLong(String),
    #[error("{0}")]
    BackendUnavailable(String),
    #[error("{0}")]
    InvalidConfig(String),
    #[error("{0}")]
    Internal(String),
}

impl From<neuron_core::NeuronError> for NeuronError {
    fn from(e: neuron_core::NeuronError) -> Self {
        use neuron_core::NeuronError as C;
        let msg = e.to_string();
        match e {
            C::NoModel => NeuronError::NoModel(msg),
            C::Busy => NeuronError::Busy(msg),
            C::Cancelled => NeuronError::Cancelled(msg),
            C::ModelNotFound(_) => NeuronError::ModelNotFound(msg),
            C::ModelLoad(_) | C::Context(_) => NeuronError::ModelLoad(msg),
            C::PromptTooLong { .. } => NeuronError::PromptTooLong(msg),
            C::BackendUnavailable(_) => NeuronError::BackendUnavailable(msg),
            C::InvalidConfig(_) => NeuronError::InvalidConfig(msg),
            C::Tokenize(_) | C::Decode(_) | C::Io(_) => NeuronError::Internal(msg),
        }
    }
}

// ───────────────────────── enums ─────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Backend {
    HexagonNpu,
    AdrenoGpu,
    Cpu,
}

impl From<neuron_core::Backend> for Backend {
    fn from(b: neuron_core::Backend) -> Self {
        match b {
            neuron_core::Backend::HexagonNpu => Backend::HexagonNpu,
            neuron_core::Backend::AdrenoGpu => Backend::AdrenoGpu,
            neuron_core::Backend::Cpu => Backend::Cpu,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BackendRequest {
    Auto,
    HexagonNpu,
    AdrenoGpu,
    Cpu,
}

impl From<BackendRequest> for neuron_core::BackendRequest {
    fn from(r: BackendRequest) -> Self {
        use neuron_core::Backend as B;
        match r {
            BackendRequest::Auto => Self::Auto,
            BackendRequest::HexagonNpu => Self::Only(B::HexagonNpu),
            BackendRequest::AdrenoGpu => Self::Only(B::AdrenoGpu),
            BackendRequest::Cpu => Self::Only(B::Cpu),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KvCacheQuant {
    F16,
    Q8_0,
    Q4_0,
}

impl From<KvCacheQuant> for core_engine::KvCacheQuant {
    fn from(k: KvCacheQuant) -> Self {
        match k {
            KvCacheQuant::F16 => Self::F16,
            KvCacheQuant::Q8_0 => Self::Q8_0,
            KvCacheQuant::Q4_0 => Self::Q4_0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatTemplate {
    ChatMl,
    Llama3,
    Gemma,
    Phi,
    Mistral,
    Generic,
}

impl From<ChatTemplate> for neuron_core::ChatTemplate {
    fn from(t: ChatTemplate) -> Self {
        match t {
            ChatTemplate::ChatMl => Self::ChatMl,
            ChatTemplate::Llama3 => Self::Llama3,
            ChatTemplate::Gemma => Self::Gemma,
            ChatTemplate::Phi => Self::Phi,
            ChatTemplate::Mistral => Self::Mistral,
            ChatTemplate::Generic => Self::Generic,
        }
    }
}

impl From<neuron_core::ChatTemplate> for ChatTemplate {
    fn from(t: neuron_core::ChatTemplate) -> Self {
        use neuron_core::ChatTemplate as C;
        match t {
            C::ChatMl => Self::ChatMl,
            C::Llama3 => Self::Llama3,
            C::Gemma => Self::Gemma,
            C::Phi => Self::Phi,
            C::Mistral => Self::Mistral,
            C::Generic => Self::Generic,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Role {
    System,
    User,
    Assistant,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReasoningEffort {
    None,
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ThermalPolicy {
    Pause,
    SlowDown,
    Ignore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ThermalState {
    Nominal,
    Warm,
    Hot,
    Critical,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StopReason {
    EndOfGeneration,
    MaxTokens,
    ContextFull,
    Cancelled,
    SinkStopped,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum JsErrorKind {
    Syntax,
    Runtime,
    Timeout,
    Memory,
    Security,
}

// ───────────────────────── records ─────────────────────────

#[derive(Debug, Clone, uniffi::Record)]
pub struct EngineConfig {
    /// App `nativeLibraryDir`: where the ggml backend modules were unpacked.
    pub backends_dir: Option<String>,
    /// Directory holding `libggml-htp-vNN.so` for the Hexagon DSP side.
    pub hexagon_skel_dir: Option<String>,
    /// `GGML_HEXAGON_POWER_MODE`, e.g. "default", "balanced", "sustained_high_performance".
    pub hexagon_power_mode: Option<String>,
    pub verbose_logging: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Message {
    pub role: Role,
    pub content: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SamplingParams {
    pub temperature: f32,
    pub top_p: f32,
    pub top_k: i32,
    pub min_p: f32,
    pub repeat_penalty: f32,
    pub penalty_last_n: i32,
    pub seed: Option<u32>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ModelConfig {
    pub path: String,
    pub context_length: u32,
    pub batch_size: u32,
    pub threads: u32,
    pub backend: BackendRequest,
    pub kv_cache: KvCacheQuant,
    pub model_ram_mb: u32,
    pub use_mmap: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LoadReport {
    pub backend: Backend,
    pub backend_reason: String,
    pub device_name: Option<String>,
    pub n_ctx: u32,
    pub n_ctx_train: u32,
    pub n_params: u64,
    pub n_layer: u32,
    pub model_size_bytes: u64,
    pub threads: i32,
    pub detected_template: Option<ChatTemplate>,
    pub load_ms: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GenerationRequest {
    pub messages: Vec<Message>,
    pub system_prompt: String,
    pub template: ChatTemplate,
    pub reasoning: ReasoningEffort,
    pub reasoning_capable: bool,
    pub sampling: SamplingParams,
    pub max_tokens: u32,
    pub thermal_policy: ThermalPolicy,
    pub tok_sec_cap: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GenerationSummary {
    pub prompt_tokens: u32,
    pub tokens: u32,
    pub elapsed_ms: u64,
    pub avg_tok_sec: f32,
    pub stop: StopReason,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Telemetry {
    pub tok_sec: f32,
    pub avg_tok_sec: f32,
    pub tokens_generated: u32,
    pub context_used: u32,
    pub context_size: u32,
    pub cpu_temp_c: Option<f32>,
    pub thermal_state: ThermalState,
    pub available_ram_mb: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DeviceInfo {
    pub index: u32,
    pub name: String,
    pub backend: String,
    pub description: String,
    pub memory_total_bytes: u64,
    pub class: Option<Backend>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SocInfo {
    pub model: String,
    pub platform: String,
    pub marketing_name: Option<String>,
    pub htp_arch: Option<u32>,
    pub htp_skel_filename: Option<String>,
    pub supports_hexagon: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GgufInfo {
    pub name: Option<String>,
    pub architecture: Option<String>,
    pub context_length: Option<u32>,
    pub block_count: Option<u32>,
    pub file_type: Option<u32>,
    pub quantization_label: Option<String>,
    pub chat_template: ChatTemplate,
    pub estimated_params: Option<u64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct JsLimits {
    pub memory_limit_bytes: u64,
    pub timeout_ms: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct JsError {
    pub kind: JsErrorKind,
    pub message: String,
    pub line: Option<u32>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct JsRunResult {
    pub stdout: String,
    pub stderr: String,
    pub return_value: Option<String>,
    pub execution_time_ms: u64,
    pub memory_used_bytes: u64,
    pub error: Option<JsError>,
}

// ───────────────────────── callbacks ─────────────────────────

/// Implemented in Kotlin. Return `false` to stop the generation.
#[uniffi::export(with_foreign)]
pub trait TokenSink: Send + Sync {
    fn on_token(&self, text: String, tok_sec: f32) -> bool;
}

struct SinkAdapter(Arc<dyn TokenSink>);

impl core_engine::TokenSink for SinkAdapter {
    fn on_token(&self, text: &str, tok_sec: f32) -> bool {
        self.0.on_token(text.to_owned(), tok_sec)
    }
}

// ───────────────────────── engine object ─────────────────────────

#[derive(uniffi::Object)]
pub struct NeuronEngine {
    inner: Mutex<Engine>,
    cancel: CancelToken,
    generating: AtomicBool,
    telemetry: Arc<Mutex<neuron_core::Telemetry>>,
}

#[uniffi::export]
impl NeuronEngine {
    /// Initialise ggml and load every backend module found in `backends_dir`.
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Result<Arc<Self>, NeuronError> {
        logging::init(config.verbose_logging);
        let cfg = core_engine::EngineConfig {
            backends_dir: config.backends_dir.map(PathBuf::from),
            hexagon: core_engine::HexagonConfig {
                skel_dir: config.hexagon_skel_dir.map(PathBuf::from),
                power_mode: config.hexagon_power_mode,
                devices: None,
            },
        };
        let engine = Engine::new(cfg)?;
        let telemetry = engine.telemetry_handle();
        Ok(Arc::new(Self {
            inner: Mutex::new(engine),
            cancel: CancelToken::new(),
            generating: AtomicBool::new(false),
            telemetry,
        }))
    }

    pub fn devices(&self) -> Vec<DeviceInfo> {
        self.inner
            .lock()
            .devices()
            .iter()
            .map(|d| DeviceInfo {
                index: d.index as u32,
                name: d.name.clone(),
                backend: d.backend.clone(),
                description: d.description.clone(),
                memory_total_bytes: d.memory_total_bytes,
                class: d.class().map(Into::into),
            })
            .collect()
    }

    pub fn available_backends(&self) -> Vec<Backend> {
        self.inner
            .lock()
            .available_backends()
            .into_iter()
            .map(Into::into)
            .collect()
    }

    pub fn is_loaded(&self) -> bool {
        self.inner.lock().is_loaded()
    }

    pub fn is_generating(&self) -> bool {
        self.generating.load(Ordering::SeqCst)
    }

    pub fn load_model(&self, config: ModelConfig) -> Result<LoadReport, NeuronError> {
        let cfg = core_engine::ModelConfig {
            path: PathBuf::from(config.path),
            context_length: config.context_length,
            batch_size: config.batch_size,
            threads: config.threads,
            backend: config.backend.into(),
            kv_cache: config.kv_cache.into(),
            model_ram_mb: config.model_ram_mb,
            use_mmap: config.use_mmap,
        };
        let r = self.inner.lock().load(cfg)?;
        Ok(LoadReport {
            backend: r.backend.into(),
            backend_reason: r.backend_reason,
            device_name: r.device_name,
            n_ctx: r.n_ctx,
            n_ctx_train: r.n_ctx_train,
            n_params: r.n_params,
            n_layer: r.n_layer,
            model_size_bytes: r.model_size_bytes,
            threads: r.threads,
            detected_template: r.detected_template.map(Into::into),
            load_ms: r.load_ms,
        })
    }

    pub fn unload_model(&self) {
        self.inner.lock().unload();
    }

    /// Blocking: runs the whole generation on the calling thread, streaming
    /// pieces to `sink`. Call from a background dispatcher.
    pub fn generate(
        &self,
        request: GenerationRequest,
        sink: Arc<dyn TokenSink>,
    ) -> Result<GenerationSummary, NeuronError> {
        if self.generating.swap(true, Ordering::SeqCst) {
            return Err(NeuronError::Busy("a generation is already running".into()));
        }
        self.cancel.reset();
        let result = {
            let mut engine = self.inner.lock();
            engine.generate(to_core_request(request), &self.cancel, &SinkAdapter(sink))
        };
        self.generating.store(false, Ordering::SeqCst);
        let s = result?;
        Ok(GenerationSummary {
            prompt_tokens: s.prompt_tokens,
            tokens: s.tokens,
            elapsed_ms: s.elapsed_ms,
            avg_tok_sec: s.avg_tok_sec,
            stop: match s.stop {
                core_engine::StopReason::EndOfGeneration => StopReason::EndOfGeneration,
                core_engine::StopReason::MaxTokens => StopReason::MaxTokens,
                core_engine::StopReason::ContextFull => StopReason::ContextFull,
                core_engine::StopReason::Cancelled => StopReason::Cancelled,
                core_engine::StopReason::SinkStopped => StopReason::SinkStopped,
            },
        })
    }

    /// Lock-free: stops a running generation at the next token.
    pub fn cancel(&self) {
        self.cancel.cancel();
    }

    pub fn tokenize(&self, text: String) -> Result<Vec<i32>, NeuronError> {
        Ok(self.inner.lock().tokenize(&text)?)
    }

    pub fn detokenize(&self, tokens: Vec<i32>) -> Result<String, NeuronError> {
        Ok(self.inner.lock().detokenize(&tokens)?)
    }

    /// Safe to call while a generation runs: reads the shared telemetry slot
    /// plus live sysfs probes, without taking the engine lock.
    pub fn telemetry(&self) -> Telemetry {
        let t = self.telemetry.lock().clone();
        let cpu_temp_c = neuron_core::sysinfo::read_cpu_temperature();
        Telemetry {
            tok_sec: t.tok_sec,
            avg_tok_sec: t.avg_tok_sec,
            tokens_generated: t.tokens_generated,
            context_used: t.context_used,
            context_size: t.context_size,
            cpu_temp_c,
            thermal_state: match neuron_core::thermal::classify(cpu_temp_c) {
                neuron_core::ThermalState::Nominal => ThermalState::Nominal,
                neuron_core::ThermalState::Warm => ThermalState::Warm,
                neuron_core::ThermalState::Hot => ThermalState::Hot,
                neuron_core::ThermalState::Critical => ThermalState::Critical,
            },
            available_ram_mb: neuron_core::sysinfo::read_available_ram_mb(),
        }
    }
}

fn to_core_request(r: GenerationRequest) -> core_engine::GenerationRequest {
    core_engine::GenerationRequest {
        messages: r.messages.into_iter().map(to_core_message).collect(),
        system_prompt: r.system_prompt,
        template: r.template.into(),
        reasoning: to_core_reasoning(r.reasoning),
        reasoning_capable: r.reasoning_capable,
        sampling: neuron_core::SamplingParams {
            temperature: r.sampling.temperature,
            top_p: r.sampling.top_p,
            top_k: r.sampling.top_k,
            min_p: r.sampling.min_p,
            repeat_penalty: r.sampling.repeat_penalty,
            penalty_last_n: r.sampling.penalty_last_n,
            seed: r.sampling.seed,
        },
        max_tokens: r.max_tokens,
        thermal_policy: match r.thermal_policy {
            ThermalPolicy::Pause => neuron_core::ThermalPolicy::Pause,
            ThermalPolicy::SlowDown => neuron_core::ThermalPolicy::SlowDown,
            ThermalPolicy::Ignore => neuron_core::ThermalPolicy::Ignore,
        },
        tok_sec_cap: r.tok_sec_cap,
    }
}

fn to_core_message(m: Message) -> neuron_core::Message {
    neuron_core::Message {
        role: match m.role {
            Role::System => neuron_core::Role::System,
            Role::User => neuron_core::Role::User,
            Role::Assistant => neuron_core::Role::Assistant,
        },
        content: m.content,
    }
}

fn to_core_reasoning(r: ReasoningEffort) -> neuron_core::ReasoningEffort {
    match r {
        ReasoningEffort::None => neuron_core::ReasoningEffort::None,
        ReasoningEffort::Low => neuron_core::ReasoningEffort::Low,
        ReasoningEffort::Medium => neuron_core::ReasoningEffort::Medium,
        ReasoningEffort::High => neuron_core::ReasoningEffort::High,
    }
}

// ───────────────────────── free functions ─────────────────────────

#[uniffi::export]
pub fn core_version() -> String {
    neuron_core::CORE_VERSION.to_string()
}

/// Identify the SoC from Android system properties (empty off-device).
#[uniffi::export]
pub fn detect_soc() -> SocInfo {
    let s = neuron_core::soc::detect();
    SocInfo {
        htp_skel_filename: s.htp_skel_filename(),
        supports_hexagon: s.supports_hexagon_backend(),
        model: s.model,
        platform: s.platform,
        marketing_name: s.marketing_name,
        htp_arch: s.htp_arch,
    }
}

/// Parse a GGUF header without loading the model.
#[uniffi::export]
pub fn read_gguf_metadata(path: String) -> Result<GgufInfo, NeuronError> {
    let info = neuron_core::gguf::read_file(&path)
        .map_err(|e| NeuronError::Internal(format!("gguf: {e}")))?;
    Ok(GgufInfo {
        quantization_label: info.quantization_label().map(str::to_owned),
        chat_template: info.chat_template().into(),
        estimated_params: info.estimated_params(),
        name: info.name,
        architecture: info.architecture,
        context_length: info.context_length,
        block_count: info.block_count,
        file_type: info.file_type,
    })
}

/// Render the exact prompt string the engine would feed the model.
#[uniffi::export]
pub fn format_prompt(
    template: ChatTemplate,
    system_prompt: String,
    messages: Vec<Message>,
    reasoning: ReasoningEffort,
    reasoning_capable: bool,
) -> String {
    let msgs: Vec<neuron_core::Message> = messages.into_iter().map(to_core_message).collect();
    neuron_core::template::format_prompt(
        template.into(),
        &system_prompt,
        &msgs,
        to_core_reasoning(reasoning),
        reasoning_capable,
    )
}

/// Run JavaScript in the QuickJS sandbox. Blocking; call off the main thread.
#[uniffi::export]
pub fn run_javascript(code: String, limits: JsLimits) -> JsRunResult {
    let r = neuron_sandbox::run_javascript(
        &code,
        neuron_sandbox::JsLimits {
            memory_limit_bytes: limits.memory_limit_bytes,
            timeout_ms: limits.timeout_ms,
            ..Default::default()
        },
    );
    JsRunResult {
        stdout: r.stdout,
        stderr: r.stderr,
        return_value: r.return_value,
        execution_time_ms: r.execution_time_ms,
        memory_used_bytes: r.memory_used_bytes,
        error: r.error.map(|e| JsError {
            kind: match e.kind {
                neuron_sandbox::JsErrorKind::Syntax => JsErrorKind::Syntax,
                neuron_sandbox::JsErrorKind::Runtime => JsErrorKind::Runtime,
                neuron_sandbox::JsErrorKind::Timeout => JsErrorKind::Timeout,
                neuron_sandbox::JsErrorKind::Memory => JsErrorKind::Memory,
                neuron_sandbox::JsErrorKind::Security => JsErrorKind::Security,
            },
            message: e.message,
            line: e.line,
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errors_flatten_to_messages() {
        let e: NeuronError = neuron_core::NeuronError::PromptTooLong {
            prompt_tokens: 10,
            n_ctx: 4,
        }
        .into();
        assert!(matches!(e, NeuronError::PromptTooLong(_)));
        assert!(e.to_string().contains("10 tokens"));
    }

    #[test]
    fn format_prompt_round_trips_through_ffi_types() {
        let p = format_prompt(
            ChatTemplate::ChatMl,
            "sys".into(),
            vec![Message {
                role: Role::User,
                content: "hi".into(),
            }],
            ReasoningEffort::None,
            false,
        );
        assert!(p.starts_with("<|im_start|>system\nsys<|im_end|>\n"));
    }

    #[test]
    fn javascript_runs_through_ffi_types() {
        let r = run_javascript(
            "1 + 1".into(),
            JsLimits {
                memory_limit_bytes: 16 << 20,
                timeout_ms: 1000,
            },
        );
        assert_eq!(r.return_value.as_deref(), Some("2"));
        assert!(r.error.is_none());
    }
}
