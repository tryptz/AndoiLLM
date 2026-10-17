//! The llama.cpp-backed engine.
//!
//! One [`Engine`] owns the ggml backend registry, at most one loaded model and
//! its context, and runs streaming generations. It is `Send` so the FFI layer
//! can park it behind a mutex and call it from any Kotlin thread; llama.cpp
//! contexts tolerate being driven from different threads as long as only one
//! thread touches them at a time, which the mutex guarantees.

use crate::backend::{self, Backend, BackendPlan, BackendRequest, DeviceSummary};
use crate::error::{NeuronError, Result};
use crate::sampling::SamplingParams;
use crate::sysinfo;
use crate::telemetry::Telemetry;
use crate::template::{self, ChatTemplate, Message, ReasoningEffort};
use crate::thermal::{self, ThermalPolicy};

use llama_cpp_2::context::params::{KvCacheType, LlamaContextParams};
use llama_cpp_2::context::LlamaContext;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::params::LlamaModelParams;
use llama_cpp_2::model::LlamaModel;
use llama_cpp_2::sampling::LlamaSampler;
use llama_cpp_2::token::LlamaToken;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::num::NonZeroU32;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Once};
use std::time::Instant;

/// KV-cache precision. Q8_0 halves cache memory at near-zero quality cost and is
/// the default above 8K context; Q4_0 quarters it for very long contexts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum KvCacheQuant {
    F16,
    #[default]
    Q8_0,
    Q4_0,
}

/// Where the Hexagon runtime finds its pieces.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct HexagonConfig {
    /// Directory holding `libggml-htp-vNN.so` (the DSP-side skel). FastRPC
    /// resolves the backend's `file:///libggml-htp-vNN.so` URI against the
    /// `ADSP_LIBRARY_PATH` / `DSP_LIBRARY_PATH` search path, so this directory
    /// is exported there before the backend module is loaded.
    pub skel_dir: Option<PathBuf>,
    /// `GGML_HEXAGON_POWER_MODE`: `default` (= burst), `balanced`,
    /// `sustained_high_performance`, `low_power_saver`, ...
    pub power_mode: Option<String>,
    /// `GGML_HEXAGON_DEVICES`: number of HTP sessions (1 on phones).
    pub devices: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct EngineConfig {
    /// Directory holding the dlopen-able ggml backend modules
    /// (`libggml-cpu-*.so`, `libggml-opencl.so`, `libggml-hexagon.so`).
    /// On Android this is the app's `nativeLibraryDir`.
    pub backends_dir: Option<PathBuf>,
    pub hexagon: HexagonConfig,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ModelConfig {
    pub path: PathBuf,
    pub context_length: u32,
    pub batch_size: u32,
    /// 0 = auto (performance cluster only).
    pub threads: u32,
    pub backend: BackendRequest,
    pub kv_cache: KvCacheQuant,
    /// Used by the auto policy to keep oversized models off the NPU.
    pub model_ram_mb: u32,
    pub use_mmap: bool,
}

impl Default for ModelConfig {
    fn default() -> Self {
        Self {
            path: PathBuf::new(),
            context_length: 4096,
            batch_size: 512,
            threads: 0,
            backend: BackendRequest::Auto,
            kv_cache: KvCacheQuant::Q8_0,
            model_ram_mb: 0,
            use_mmap: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
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
    /// Template guessed from the GGUF's own Jinja template (if any).
    pub detected_template: Option<ChatTemplate>,
    pub load_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GenerationRequest {
    pub messages: Vec<Message>,
    pub system_prompt: String,
    pub template: ChatTemplate,
    pub reasoning: ReasoningEffort,
    pub reasoning_capable: bool,
    pub sampling: SamplingParams,
    /// 0 = until the context is full.
    pub max_tokens: u32,
    pub thermal_policy: ThermalPolicy,
    /// 0 = uncapped.
    pub tok_sec_cap: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum StopReason {
    EndOfGeneration,
    MaxTokens,
    ContextFull,
    Cancelled,
    /// The consumer returned `false` from `on_token`.
    SinkStopped,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GenerationSummary {
    pub prompt_tokens: u32,
    pub tokens: u32,
    pub elapsed_ms: u64,
    pub avg_tok_sec: f32,
    pub stop: StopReason,
}

/// Receives decoded text as it is produced. Return `false` to stop.
pub trait TokenSink {
    fn on_token(&self, text: &str, tok_sec: f32) -> bool;
}

impl<F: Fn(&str, f32) -> bool> TokenSink for F {
    fn on_token(&self, text: &str, tok_sec: f32) -> bool {
        self(text, tok_sec)
    }
}

/// Cheap, cloneable flag that aborts a running generation from another thread.
#[derive(Debug, Clone, Default)]
pub struct CancelToken(Arc<AtomicBool>);

impl CancelToken {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }
    pub fn reset(&self) {
        self.0.store(false, Ordering::SeqCst);
    }
    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

struct LoadedModel {
    // Declared before `model` so it is dropped first: the context borrows the model.
    ctx: Option<LlamaContext<'static>>,
    model: Box<LlamaModel>,
    report: LoadReport,
    n_batch: u32,
}

// SAFETY: llama.cpp objects have no thread affinity; the owning `Engine` is only
// ever used behind a mutex, so no two threads touch them concurrently.
unsafe impl Send for LoadedModel {}

pub struct Engine {
    _backend: LlamaBackend,
    loaded: Option<LoadedModel>,
    telemetry: Arc<Mutex<Telemetry>>,
    devices: Vec<DeviceSummary>,
}

static LOGGING: Once = Once::new();

impl Engine {
    /// Initialise ggml, load every backend module found in `backends_dir`, and
    /// enumerate devices. Hexagon environment is exported first so the FastRPC
    /// session can find the skel when `libggml-hexagon.so` registers.
    pub fn new(config: EngineConfig) -> Result<Self> {
        LOGGING.call_once(|| {
            llama_cpp_2::send_logs_to_tracing(llama_cpp_2::LogOptions::default());
        });
        apply_hexagon_env(&config.hexagon);
        if let Some(dir) = &config.backends_dir {
            tracing::info!(dir = %dir.display(), "loading ggml backend modules");
            llama_cpp_2::llama_backend::load_backends_from_path(dir);
        } else {
            llama_cpp_2::llama_backend::load_backends();
        }
        let backend = LlamaBackend::init()
            .map_err(|e| NeuronError::BackendUnavailable(format!("llama_backend_init: {e}")))?;
        let devices = enumerate_devices();
        for d in &devices {
            tracing::info!(name = %d.name, backend = %d.backend, desc = %d.description, "ggml device");
        }
        Ok(Self {
            _backend: backend,
            loaded: None,
            telemetry: Arc::default(),
            devices,
        })
    }

    pub fn devices(&self) -> &[DeviceSummary] {
        &self.devices
    }

    /// Backends that actually have a device right now.
    pub fn available_backends(&self) -> Vec<Backend> {
        let mut v: Vec<Backend> = self
            .devices
            .iter()
            .filter_map(DeviceSummary::class)
            .collect();
        v.dedup();
        v
    }

    pub fn telemetry_handle(&self) -> Arc<Mutex<Telemetry>> {
        Arc::clone(&self.telemetry)
    }

    pub fn telemetry(&self) -> Telemetry {
        let mut t = self.telemetry.lock().clone();
        t.cpu_temp_c = sysinfo::read_cpu_temperature();
        t.thermal_state = thermal::classify(t.cpu_temp_c);
        t.available_ram_mb = sysinfo::read_available_ram_mb();
        t.context_used = self.context_used();
        t
    }

    pub fn is_loaded(&self) -> bool {
        self.loaded.is_some()
    }

    pub fn load_report(&self) -> Option<&LoadReport> {
        self.loaded.as_ref().map(|l| &l.report)
    }

    pub fn unload(&mut self) {
        if self.loaded.take().is_some() {
            tracing::info!("model unloaded");
        }
        *self.telemetry.lock() = Telemetry::default();
    }

    pub fn load(&mut self, cfg: ModelConfig) -> Result<LoadReport> {
        let started = Instant::now();
        if !cfg.path.is_file() {
            return Err(NeuronError::ModelNotFound(cfg.path.display().to_string()));
        }
        if cfg.context_length < 256 {
            return Err(NeuronError::InvalidConfig(
                "context_length must be >= 256".into(),
            ));
        }
        self.unload();

        let plan = backend::resolve(cfg.backend, cfg.model_ram_mb, &self.devices);
        tracing::info!(backend = ?plan.backend, reason = %plan.reason, "backend plan");

        let mut mparams = LlamaModelParams::default()
            .with_n_gpu_layers(plan.gpu_layers)
            .with_use_mmap(cfg.use_mmap);
        if let Some(idx) = plan.device_index {
            mparams = mparams
                .with_devices(&[idx])
                .map_err(|e| NeuronError::InvalidConfig(format!("device selection: {e}")))?;
        }

        let model = LlamaModel::load_from_file(&self._backend, &cfg.path, &mparams)
            .map_err(|e| NeuronError::ModelLoad(e.to_string()))?;
        let model = Box::new(model);

        let threads = sysinfo::resolve_thread_count(cfg.threads, sysinfo::online_cpus());
        let n_batch = cfg.batch_size.clamp(32, 4096);
        let (type_k, type_v, flash) = match cfg.kv_cache {
            KvCacheQuant::F16 => (
                KvCacheType::F16,
                KvCacheType::F16,
                llama_cpp_sys_2::LLAMA_FLASH_ATTN_TYPE_AUTO,
            ),
            // A quantized V cache needs the flash-attention kernel; the standard
            // path only supports F16 V.
            KvCacheQuant::Q8_0 => (
                KvCacheType::Q8_0,
                KvCacheType::Q8_0,
                llama_cpp_sys_2::LLAMA_FLASH_ATTN_TYPE_ENABLED,
            ),
            KvCacheQuant::Q4_0 => (
                KvCacheType::Q4_0,
                KvCacheType::Q4_0,
                llama_cpp_sys_2::LLAMA_FLASH_ATTN_TYPE_ENABLED,
            ),
        };
        let cparams = LlamaContextParams::default()
            .with_n_ctx(NonZeroU32::new(cfg.context_length))
            .with_n_batch(n_batch)
            .with_n_ubatch(n_batch.min(512))
            .with_n_threads(threads)
            .with_n_threads_batch(threads)
            .with_type_k(type_k)
            .with_type_v(type_v)
            .with_flash_attention_policy(flash)
            .with_offload_kqv(plan.backend != Backend::Cpu);

        let ctx = model
            .new_context(&self._backend, cparams)
            .map_err(|e| NeuronError::Context(e.to_string()))?;
        // SAFETY: `ctx` borrows `model`, which is boxed (stable address) and
        // stored in the same `LoadedModel`, whose field order drops `ctx` first.
        let ctx: LlamaContext<'static> = unsafe { std::mem::transmute(ctx) };

        let detected_template = model
            .meta_val_str("tokenizer.chat_template")
            .ok()
            .and_then(|t| ChatTemplate::detect(&t));

        let report = LoadReport {
            backend: plan.backend,
            backend_reason: plan.reason.clone(),
            device_name: device_name(&plan, &self.devices),
            n_ctx: ctx.n_ctx(),
            n_ctx_train: model.n_ctx_train(),
            n_params: model.n_params(),
            n_layer: model.n_layer(),
            model_size_bytes: model.size(),
            threads,
            detected_template,
            load_ms: started.elapsed().as_millis() as u64,
        };
        tracing::info!(?report, "model loaded");
        {
            let mut t = self.telemetry.lock();
            *t = Telemetry {
                context_size: report.n_ctx,
                ..Telemetry::default()
            };
        }
        self.loaded = Some(LoadedModel {
            ctx: Some(ctx),
            model,
            report: report.clone(),
            n_batch,
        });
        Ok(report)
    }

    pub fn tokenize(&self, text: &str) -> Result<Vec<i32>> {
        let loaded = self.loaded.as_ref().ok_or(NeuronError::NoModel)?;
        Ok(loaded
            .model
            .vocab()
            .tokenize(text.as_bytes(), true, true)
            .into_iter()
            .map(|t| t.0)
            .collect())
    }

    pub fn detokenize(&self, tokens: &[i32]) -> Result<String> {
        let loaded = self.loaded.as_ref().ok_or(NeuronError::NoModel)?;
        let vocab = loaded.model.vocab();
        let mut out = Vec::new();
        for t in tokens {
            out.extend(vocab.token_to_piece(LlamaToken(*t), true, None));
        }
        Ok(String::from_utf8_lossy(&out).into_owned())
    }

    /// Tokens currently resident in the KV cache.
    pub fn context_used(&self) -> u32 {
        self.loaded
            .as_ref()
            .and_then(|l| l.ctx.as_ref())
            .map(|c| (c.kv_cache_seq_pos_max(0) + 1).max(0) as u32)
            .unwrap_or(0)
    }

    /// Run one streaming generation. Each turn receives the full formatted
    /// conversation, so the cache is cleared first (otherwise turn N would be
    /// appended after turn N-1's cells at the wrong positions).
    pub fn generate(
        &mut self,
        req: GenerationRequest,
        cancel: &CancelToken,
        sink: &dyn TokenSink,
    ) -> Result<GenerationSummary> {
        req.sampling.validate()?;
        let loaded = self.loaded.as_mut().ok_or(NeuronError::NoModel)?;
        let ctx = loaded.ctx.as_mut().ok_or(NeuronError::NoModel)?;
        let vocab = loaded.model.vocab();
        let n_ctx = ctx.n_ctx() as usize;

        let prompt = template::format_prompt(
            req.template,
            &req.system_prompt,
            &req.messages,
            req.reasoning,
            req.reasoning_capable,
        );
        // add_special=true lets the vocab decide whether a BOS belongs at the front.
        let tokens = vocab.tokenize(prompt.as_bytes(), true, true);
        if tokens.is_empty() {
            return Err(NeuronError::Tokenize("prompt produced no tokens".into()));
        }
        if tokens.len() >= n_ctx {
            return Err(NeuronError::PromptTooLong {
                prompt_tokens: tokens.len(),
                n_ctx,
            });
        }
        tracing::info!(
            prompt_tokens = tokens.len(),
            prompt_chars = prompt.len(),
            "generation start"
        );

        ctx.clear_kv_cache();
        {
            let mut t = self.telemetry.lock();
            t.tok_sec = 0.0;
            t.avg_tok_sec = 0.0;
            t.tokens_generated = 0;
            t.context_used = tokens.len() as u32;
        }

        // Prompt evaluation in n_batch chunks: llama_decode rejects larger batches.
        let n_batch = loaded.n_batch as usize;
        let mut batch = LlamaBatch::new(n_batch, 1);
        let mut pos: i32 = 0;
        for chunk in tokens.chunks(n_batch) {
            batch.clear();
            for (i, tok) in chunk.iter().enumerate() {
                let is_last_of_prompt = pos as usize == tokens.len() - 1;
                let _ = i;
                batch
                    .add(*tok, pos, &[0], is_last_of_prompt)
                    .map_err(|e| NeuronError::Decode(format!("batch add: {e}")))?;
                pos += 1;
            }
            if cancel.is_cancelled() {
                return Ok(summary(
                    tokens.len(),
                    0,
                    Instant::now(),
                    StopReason::Cancelled,
                ));
            }
            ctx.decode(&mut batch)
                .map_err(|e| NeuronError::Decode(format!("prompt eval: {e}")))?;
        }

        let mut sampler = build_sampler(&req.sampling, loaded.model.n_vocab());
        let budget = n_ctx - tokens.len() - 1;
        let max_tokens = if req.max_tokens == 0 {
            budget
        } else {
            (req.max_tokens as usize).min(budget)
        };

        let started = Instant::now();
        let mut generated = 0usize;
        let mut utf8 = Utf8Accumulator::default();
        let stop: StopReason;

        loop {
            if cancel.is_cancelled() {
                stop = StopReason::Cancelled;
                break;
            }
            if generated >= max_tokens {
                stop = if max_tokens == budget {
                    StopReason::ContextFull
                } else {
                    StopReason::MaxTokens
                };
                break;
            }
            let t0 = Instant::now();
            // sample() also accepts the token into the chain (penalty window).
            let token = sampler.sample(ctx, -1);
            if vocab.is_eog(token) {
                stop = StopReason::EndOfGeneration;
                break;
            }
            let piece = vocab.token_to_piece(token, true, None);

            batch.clear();
            batch
                .add(token, pos, &[0], true)
                .map_err(|e| NeuronError::Decode(format!("batch add: {e}")))?;
            pos += 1;
            ctx.decode(&mut batch)
                .map_err(|e| NeuronError::Decode(format!("decode: {e}")))?;
            generated += 1;

            let dt = t0.elapsed().as_secs_f32().max(1e-6);
            let tok_sec = 1.0 / dt;
            let avg = generated as f32 / started.elapsed().as_secs_f32().max(1e-6);
            {
                let mut t = self.telemetry.lock();
                t.tok_sec = tok_sec;
                t.avg_tok_sec = avg;
                t.tokens_generated = generated as u32;
                t.context_used = pos.max(0) as u32;
            }

            if let Some(text) = utf8.push(&piece) {
                if !sink.on_token(&text, tok_sec) {
                    stop = StopReason::SinkStopped;
                    break;
                }
            }

            // Thermal + throughput policy, applied per token.
            let temp = sysinfo::read_cpu_temperature();
            if let Some(d) = thermal::throttle_delay(req.thermal_policy, temp) {
                std::thread::sleep(d);
            }
            if let Some(d) = thermal::tok_sec_cap_delay(tok_sec, req.tok_sec_cap) {
                std::thread::sleep(d);
            }
        }
        if let Some(rest) = utf8.flush() {
            sink.on_token(&rest, 0.0);
        }
        let s = summary(tokens.len(), generated, started, stop);
        tracing::info!(?s, "generation end");
        Ok(s)
    }
}

fn summary(
    prompt_tokens: usize,
    tokens: usize,
    started: Instant,
    stop: StopReason,
) -> GenerationSummary {
    let elapsed = started.elapsed();
    GenerationSummary {
        prompt_tokens: prompt_tokens as u32,
        tokens: tokens as u32,
        elapsed_ms: elapsed.as_millis() as u64,
        avg_tok_sec: if tokens == 0 {
            0.0
        } else {
            tokens as f32 / elapsed.as_secs_f32().max(1e-6)
        },
        stop,
    }
}

/// Canonical llama.cpp chain: penalties → top_k → top_p → min_p → temp → dist.
/// The terminal sampler is mandatory: filters only mask the distribution.
fn build_sampler(p: &SamplingParams, n_vocab: i32) -> LlamaSampler {
    let seed = p.seed.unwrap_or_else(|| {
        let t = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        (t.as_nanos() & 0xffff_ffff) as u32
    });
    if p.is_greedy() {
        return LlamaSampler::chain_simple([
            LlamaSampler::penalties(n_vocab, p.penalty_last_n, p.repeat_penalty, 0.0, 0.0),
            LlamaSampler::greedy(),
        ]);
    }
    LlamaSampler::chain_simple([
        LlamaSampler::penalties(n_vocab, p.penalty_last_n, p.repeat_penalty, 0.0, 0.0),
        LlamaSampler::top_k(p.top_k),
        LlamaSampler::top_p(p.top_p, 1),
        LlamaSampler::min_p(p.min_p, 1),
        LlamaSampler::temp(p.temperature),
        LlamaSampler::dist(seed),
    ])
}

fn device_name(plan: &BackendPlan, devices: &[DeviceSummary]) -> Option<String> {
    plan.device_index
        .and_then(|i| devices.iter().find(|d| d.index == i))
        .map(|d| d.name.clone())
}

fn enumerate_devices() -> Vec<DeviceSummary> {
    llama_cpp_2::list_llama_ggml_backend_devices()
        .into_iter()
        .map(|d| DeviceSummary {
            index: d.index,
            name: d.name,
            backend: d.backend,
            description: d.description,
            memory_total_bytes: d.memory_total as u64,
        })
        .collect()
}

fn apply_hexagon_env(h: &HexagonConfig) {
    if let Some(dir) = &h.skel_dir {
        export_path_var("ADSP_LIBRARY_PATH", dir);
        export_path_var("DSP_LIBRARY_PATH", dir);
    }
    if let Some(mode) = &h.power_mode {
        std::env::set_var("GGML_HEXAGON_POWER_MODE", mode);
    }
    if let Some(devs) = &h.devices {
        std::env::set_var("GGML_HEXAGON_DEVICES", devs);
    }
}

fn export_path_var(var: &str, dir: &Path) {
    let dir = dir.display().to_string();
    let joined = match std::env::var(var) {
        Ok(existing) if !existing.is_empty() && !existing.split(';').any(|p| p == dir) => {
            format!("{dir};{existing}")
        }
        Ok(existing) if !existing.is_empty() => existing,
        _ => dir,
    };
    std::env::set_var(var, joined);
}

/// Emits only complete UTF-8 sequences; a token boundary can split a multibyte
/// character and the UI must never see a replacement glyph for a real one.
#[derive(Default)]
struct Utf8Accumulator {
    pending: Vec<u8>,
}

impl Utf8Accumulator {
    /// Append raw bytes; returns every complete character now available.
    fn push(&mut self, bytes: &[u8]) -> Option<String> {
        self.pending.extend_from_slice(bytes);
        let mut out = String::new();
        loop {
            match std::str::from_utf8(&self.pending) {
                Ok(s) => {
                    out.push_str(s);
                    self.pending.clear();
                    break;
                }
                Err(e) => {
                    let valid = e.valid_up_to();
                    out.push_str(std::str::from_utf8(&self.pending[..valid]).unwrap_or_default());
                    match e.error_len() {
                        // Genuinely invalid byte(s): replace them and keep going.
                        Some(bad) => {
                            out.push('\u{FFFD}');
                            self.pending.drain(..valid + bad);
                        }
                        // Incomplete trailing sequence: wait for the next token.
                        None => {
                            self.pending.drain(..valid);
                            break;
                        }
                    }
                }
            }
        }
        (!out.is_empty()).then_some(out)
    }

    fn flush(&mut self) -> Option<String> {
        if self.pending.is_empty() {
            return None;
        }
        let out = String::from_utf8_lossy(&self.pending).into_owned();
        self.pending.clear();
        Some(out)
    }
}

#[allow(dead_code)]
fn _assert_send() {
    fn is_send<T: Send>() {}
    is_send::<LoadedModel>();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_accumulator_waits_for_complete_sequences() {
        let mut a = Utf8Accumulator::default();
        let euro = "€".as_bytes(); // e2 82 ac
        assert_eq!(a.push(&euro[..1]), None);
        assert_eq!(a.push(&euro[1..2]), None);
        assert_eq!(a.push(&euro[2..]).as_deref(), Some("€"));
        assert_eq!(a.push(b"ok").as_deref(), Some("ok"));
        assert_eq!(a.flush(), None);
    }

    #[test]
    fn utf8_accumulator_replaces_invalid_bytes() {
        let mut a = Utf8Accumulator::default();
        assert_eq!(a.push(&[b'a', 0xff, b'b']).as_deref(), Some("a\u{FFFD}b"));
    }

    #[test]
    fn export_path_var_prepends_once() {
        std::env::remove_var("NEURON_TEST_PATH");
        export_path_var("NEURON_TEST_PATH", Path::new("/a"));
        assert_eq!(std::env::var("NEURON_TEST_PATH").unwrap(), "/a");
        export_path_var("NEURON_TEST_PATH", Path::new("/b"));
        assert_eq!(std::env::var("NEURON_TEST_PATH").unwrap(), "/b;/a");
        export_path_var("NEURON_TEST_PATH", Path::new("/a"));
        assert_eq!(std::env::var("NEURON_TEST_PATH").unwrap(), "/b;/a");
    }

    #[test]
    fn model_config_defaults_are_phone_friendly() {
        let c = ModelConfig::default();
        assert_eq!(c.context_length, 4096);
        assert_eq!(c.kv_cache, KvCacheQuant::Q8_0);
        assert_eq!(c.backend, BackendRequest::Auto);
    }

    #[test]
    fn engine_refuses_missing_model_file() {
        // Needs llama.cpp compiled; backend init is cheap on the host.
        let mut e = Engine::new(EngineConfig::default()).expect("engine init");
        let err = e.load(ModelConfig {
            path: "/nonexistent/model.gguf".into(),
            ..Default::default()
        });
        assert!(matches!(err, Err(NeuronError::ModelNotFound(_))));
        assert!(!e.is_loaded());
        assert!(matches!(e.tokenize("x"), Err(NeuronError::NoModel)));
    }
}
