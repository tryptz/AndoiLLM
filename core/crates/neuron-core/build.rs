//! Collects the shared libraries that `llama-cpp-sys-2` produces in
//! `dynamic-backends` mode (libllama / libggml* and the per-backend modules)
//! into one predictable folder: `target/<triple>/<profile>/neuron-libs/`.
//!
//! The Android packaging step (Gradle) and `scripts/build-android.sh` copy that
//! folder into `jniLibs/arm64-v8a`. Without this step the libraries would be
//! buried under a hashed `build/llama-cpp-sys-2-<hash>/out` directory.
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=DEP_LLAMA_BACKENDS_DIR");
    let Ok(backends) = env::var("DEP_LLAMA_BACKENDS_DIR") else {
        // Static build or the `llama` feature is off: nothing to collect.
        return;
    };
    let backends = PathBuf::from(backends);
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR set by cargo"));
    // OUT_DIR = <target>/<triple>/<profile>/build/<crate-hash>/out
    let profile_dir = out_dir
        .ancestors()
        .nth(3)
        .expect("OUT_DIR has the standard cargo layout")
        .to_path_buf();
    let dest = profile_dir.join("neuron-libs");
    // Start clean: a stale library from an earlier configuration (a backend
    // that is now off, a skel from a failed run) must never reach the APK.
    let _ = fs::remove_dir_all(&dest);
    fs::create_dir_all(&dest).expect("create neuron-libs");

    // llama-cpp-sys-2 installs libllama/libggml* next to the backends folder.
    let out = backends.parent().map(Path::to_path_buf);
    for dir in [Some(backends.clone()), out.as_ref().map(|o| o.join("lib"))]
        .into_iter()
        .flatten()
    {
        copy_shared_libs(&dir, &dest, |name| !name.starts_with("libggml-htp-"));
    }
    // Hexagon DSP skels (see cmake/snapdragon.cmake): shipped as app assets.
    if let Some(htp) = out.as_ref().map(|o| o.join("htp")).filter(|p| p.is_dir()) {
        let htp_dest = dest.join("htp");
        fs::create_dir_all(&htp_dest).expect("create neuron-libs/htp");
        copy_shared_libs(&htp, &htp_dest, |name| name.starts_with("libggml-htp-"));
    }
    println!("cargo:neuron_libs_dir={}", dest.display());
    println!(
        "cargo:warning=neuron-core: native libraries collected in {}",
        dest.display()
    );
}

fn copy_shared_libs(from: &Path, to: &Path, keep: impl Fn(&str) -> bool) {
    let Ok(entries) = fs::read_dir(from) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name();
        let name = name.to_string_lossy();
        let is_shared = name.ends_with(".so") || name.contains(".so.") || name.ends_with(".dylib");
        // `common` is llama.cpp's CLI helper library; nothing in libneuron links it.
        let is_wanted = is_shared && !name.contains("llama-common") && keep(&name);
        if path.is_file() && is_wanted {
            let target = to.join(&*name);
            if let Err(e) = fs::copy(&path, &target) {
                println!(
                    "cargo:warning=neuron-core: could not copy {}: {e}",
                    path.display()
                );
            }
        }
    }
}
