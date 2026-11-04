//! Backend policy: which silicon runs the model.
//!
//! The ggml device list is the single source of truth for what is *available*
//! (a backend module that failed to dlopen simply does not appear). This module
//! only decides which of the available devices to *prefer* for a given model.

use serde::{Deserialize, Serialize};

/// The three execution targets on a Snapdragon 8 Elite Gen 5.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Backend {
    /// Hexagon Tensor Processor (NPU) through `ggml-hexagon`. Fastest per watt.
    HexagonNpu,
    /// Adreno GPU through `ggml-opencl`. Best for models too large for the NPU.
    AdrenoGpu,
    /// Oryon / Kryo CPU through `ggml-cpu` (NEON, dotprod, i8mm, SME where present).
    Cpu,
}

impl Backend {
    /// ggml backend registry name that owns this device class.
    pub fn ggml_backend_name(self) -> &'static str {
        match self {
            Backend::HexagonNpu => "HTP",
            Backend::AdrenoGpu => "OpenCL",
            Backend::Cpu => "CPU",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Backend::HexagonNpu => "Hexagon NPU",
            Backend::AdrenoGpu => "Adreno GPU (OpenCL)",
            Backend::Cpu => "CPU",
        }
    }
}

/// What the user asked for. `Auto` lets [`resolve`] pick.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum BackendRequest {
    Auto,
    Only(Backend),
}

/// A ggml device as enumerated at runtime, reduced to what policy needs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceSummary {
    /// Index into the ggml device table (what `llama_model_params.devices` wants).
    pub index: usize,
    /// e.g. `HTP0`, `GPUOpenCL`, `CPU`.
    pub name: String,
    /// Registry name, e.g. `HTP`, `OpenCL`, `CPU`.
    pub backend: String,
    pub description: String,
    pub memory_total_bytes: u64,
}

impl DeviceSummary {
    pub fn class(&self) -> Option<Backend> {
        match self.backend.as_str() {
            "HTP" => Some(Backend::HexagonNpu),
            "OpenCL" => Some(Backend::AdrenoGpu),
            "CPU" => Some(Backend::Cpu),
            _ => None,
        }
    }
}

/// Models above this size do not fit comfortably in the HTP's addressable
/// memory window alongside a usable KV cache; they go to the GPU instead.
pub const NPU_MAX_MODEL_RAM_MB: u32 = 4000;

/// The device plan the engine hands to llama.cpp.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackendPlan {
    pub backend: Backend,
    /// ggml device index to pin, `None` for CPU.
    pub device_index: Option<usize>,
    /// Layers to offload. `0` for CPU, "all" (999) otherwise.
    pub gpu_layers: u32,
    /// Human-readable reason, logged and surfaced in diagnostics.
    pub reason: String,
}

const ALL_LAYERS: u32 = 999;

/// Pick a backend for a model of `model_ram_mb` given the devices ggml found.
///
/// Preference order for `Auto` is NPU → GPU → CPU, with the NPU skipped for
/// models over [`NPU_MAX_MODEL_RAM_MB`]. An explicit request that is not
/// available degrades to the next best option and says so in `reason`.
pub fn resolve(
    request: BackendRequest,
    model_ram_mb: u32,
    devices: &[DeviceSummary],
) -> BackendPlan {
    let find = |b: Backend| devices.iter().find(|d| d.class() == Some(b));
    let plan = |b: Backend, dev: Option<&DeviceSummary>, reason: String| BackendPlan {
        backend: b,
        device_index: dev.map(|d| d.index),
        gpu_layers: if b == Backend::Cpu { 0 } else { ALL_LAYERS },
        reason,
    };

    let npu = find(Backend::HexagonNpu);
    let gpu = find(Backend::AdrenoGpu);

    match request {
        BackendRequest::Only(Backend::HexagonNpu) => match npu {
            Some(d) => plan(Backend::HexagonNpu, Some(d), "requested".into()),
            None => fallback(gpu, "Hexagon backend not available (no HTP device)"),
        },
        BackendRequest::Only(Backend::AdrenoGpu) => match gpu {
            Some(d) => plan(Backend::AdrenoGpu, Some(d), "requested".into()),
            None => plan(
                Backend::Cpu,
                None,
                "OpenCL backend not available; using CPU".into(),
            ),
        },
        BackendRequest::Only(Backend::Cpu) => plan(Backend::Cpu, None, "requested".into()),
        BackendRequest::Auto => {
            if let Some(d) = npu {
                if model_ram_mb <= NPU_MAX_MODEL_RAM_MB {
                    return plan(Backend::HexagonNpu, Some(d), "auto: fits the NPU".into());
                }
                if let Some(g) = gpu {
                    return plan(
                        Backend::AdrenoGpu,
                        Some(g),
                        format!("auto: {model_ram_mb} MB exceeds NPU budget of {NPU_MAX_MODEL_RAM_MB} MB"),
                    );
                }
            }
            match gpu {
                Some(g) => plan(
                    Backend::AdrenoGpu,
                    Some(g),
                    "auto: no NPU, GPU available".into(),
                ),
                None => plan(Backend::Cpu, None, "auto: only CPU available".into()),
            }
        }
    }
}

fn fallback(gpu: Option<&DeviceSummary>, why: &str) -> BackendPlan {
    match gpu {
        Some(g) => BackendPlan {
            backend: Backend::AdrenoGpu,
            device_index: Some(g.index),
            gpu_layers: ALL_LAYERS,
            reason: format!("{why}; using GPU"),
        },
        None => BackendPlan {
            backend: Backend::Cpu,
            device_index: None,
            gpu_layers: 0,
            reason: format!("{why}; using CPU"),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dev(index: usize, name: &str, backend: &str) -> DeviceSummary {
        DeviceSummary {
            index,
            name: name.into(),
            backend: backend.into(),
            description: String::new(),
            memory_total_bytes: 0,
        }
    }
    fn full() -> Vec<DeviceSummary> {
        vec![
            dev(0, "HTP0", "HTP"),
            dev(1, "GPUOpenCL", "OpenCL"),
            dev(2, "CPU", "CPU"),
        ]
    }

    #[test]
    fn auto_prefers_npu_when_model_fits() {
        let p = resolve(BackendRequest::Auto, 3000, &full());
        assert_eq!(p.backend, Backend::HexagonNpu);
        assert_eq!(p.device_index, Some(0));
        assert_eq!(p.gpu_layers, 999);
    }

    #[test]
    fn auto_falls_to_gpu_when_model_too_large_for_npu() {
        let p = resolve(BackendRequest::Auto, 5000, &full());
        assert_eq!(p.backend, Backend::AdrenoGpu);
        assert_eq!(p.device_index, Some(1));
    }

    #[test]
    fn npu_threshold_is_inclusive() {
        assert_eq!(
            resolve(BackendRequest::Auto, 4000, &full()).backend,
            Backend::HexagonNpu
        );
        assert_eq!(
            resolve(BackendRequest::Auto, 4001, &full()).backend,
            Backend::AdrenoGpu
        );
    }

    #[test]
    fn auto_uses_gpu_without_npu() {
        let devs = vec![dev(0, "GPUOpenCL", "OpenCL"), dev(1, "CPU", "CPU")];
        assert_eq!(
            resolve(BackendRequest::Auto, 3000, &devs).backend,
            Backend::AdrenoGpu
        );
    }

    #[test]
    fn auto_uses_cpu_when_alone() {
        let devs = vec![dev(0, "CPU", "CPU")];
        let p = resolve(BackendRequest::Auto, 3000, &devs);
        assert_eq!(p.backend, Backend::Cpu);
        assert_eq!(p.device_index, None);
        assert_eq!(p.gpu_layers, 0);
    }

    #[test]
    fn explicit_npu_degrades_honestly() {
        let devs = vec![dev(0, "GPUOpenCL", "OpenCL"), dev(1, "CPU", "CPU")];
        let p = resolve(BackendRequest::Only(Backend::HexagonNpu), 1000, &devs);
        assert_eq!(p.backend, Backend::AdrenoGpu);
        assert!(p.reason.contains("not available"));
    }

    #[test]
    fn explicit_gpu_without_opencl_uses_cpu() {
        let devs = vec![dev(0, "CPU", "CPU")];
        let p = resolve(BackendRequest::Only(Backend::AdrenoGpu), 1000, &devs);
        assert_eq!(p.backend, Backend::Cpu);
    }

    #[test]
    fn device_classes_map_registry_names() {
        assert_eq!(dev(0, "HTP0", "HTP").class(), Some(Backend::HexagonNpu));
        assert_eq!(
            dev(0, "GPUOpenCL", "OpenCL").class(),
            Some(Backend::AdrenoGpu)
        );
        assert_eq!(dev(0, "Vulkan0", "Vulkan").class(), None);
    }
}
