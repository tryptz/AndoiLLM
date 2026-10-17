//! Snapdragon SoC identification and the Hexagon (HTP) architecture table.
//!
//! `ggml-hexagon` autodetects the DSP arch through FastRPC at session start, but
//! the app wants to know *before* loading anything whether the phone has an NPU
//! worth targeting, and which `libggml-htp-vNN.so` skel it must stage. This
//! table answers that from Android system properties.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SocInfo {
    /// Raw `ro.soc.model` (e.g. `SM8850`), or the best fallback property found.
    pub model: String,
    /// Raw `ro.board.platform` (e.g. `kaanapali`), may be empty off-device.
    pub platform: String,
    /// Marketing name when the SoC is in our table.
    pub marketing_name: Option<String>,
    /// Hexagon Tensor Processor architecture version (73/75/79/81), if known.
    pub htp_arch: Option<u32>,
}

impl SocInfo {
    /// File name of the HTP skeleton library this SoC needs on the DSP side.
    pub fn htp_skel_filename(&self) -> Option<String> {
        self.htp_arch.map(|v| format!("libggml-htp-v{v}.so"))
    }

    /// True when `ggml-hexagon` can drive this part (v73 and newer).
    pub fn supports_hexagon_backend(&self) -> bool {
        matches!(self.htp_arch, Some(v) if v >= 73)
    }
}

/// (soc model prefix, board platform codename, marketing name, HTP arch)
const TABLE: &[(&str, &str, &str, u32)] = &[
    ("SM8850", "kaanapali", "Snapdragon 8 Elite Gen 5", 81),
    ("SM8845", "kaanapali", "Snapdragon 8 Gen 5", 81),
    ("SM8750", "sun", "Snapdragon 8 Elite", 79),
    ("SM8735", "sun", "Snapdragon 8s Gen 4", 79),
    ("SM8650", "pineapple", "Snapdragon 8 Gen 3", 75),
    ("SM8635", "cliffs", "Snapdragon 8s Gen 3", 73),
    ("SM8550", "kalama", "Snapdragon 8 Gen 2", 73),
    ("SM7675", "parrot", "Snapdragon 7+ Gen 3", 73),
    ("SM7635", "parrot", "Snapdragon 7s Gen 3", 73),
];

/// Look a SoC up by model string and/or board platform.
pub fn classify(model: &str, platform: &str) -> SocInfo {
    let model_up = model.trim().to_ascii_uppercase();
    let platform_lc = platform.trim().to_ascii_lowercase();
    let hit = TABLE
        .iter()
        .find(|(m, _, _, _)| !model_up.is_empty() && model_up.starts_with(m))
        .or_else(|| {
            TABLE
                .iter()
                .find(|(_, p, _, _)| !platform_lc.is_empty() && platform_lc == *p)
        });
    SocInfo {
        model: model.trim().to_string(),
        platform: platform.trim().to_string(),
        marketing_name: hit.map(|h| h.2.to_string()),
        htp_arch: hit.map(|h| h.3),
    }
}

/// Detect the SoC of the device we are running on.
pub fn detect() -> SocInfo {
    let model = system_property("ro.soc.model")
        .filter(|s| !s.is_empty())
        .or_else(|| system_property("ro.hardware.chipname"))
        .unwrap_or_default();
    let platform = system_property("ro.board.platform").unwrap_or_default();
    classify(&model, &platform)
}

/// Read an Android system property. Returns `None` off Android or when unset.
#[cfg(target_os = "android")]
pub fn system_property(name: &str) -> Option<String> {
    use std::ffi::CString;
    extern "C" {
        fn __system_property_get(
            name: *const libc::c_char,
            value: *mut libc::c_char,
        ) -> libc::c_int;
    }
    const PROP_VALUE_MAX: usize = 92;
    let cname = CString::new(name).ok()?;
    let mut buf = [0u8; PROP_VALUE_MAX];
    // SAFETY: buffer is PROP_VALUE_MAX bytes as the API requires; name is NUL terminated.
    let len =
        unsafe { __system_property_get(cname.as_ptr(), buf.as_mut_ptr() as *mut libc::c_char) };
    if len <= 0 {
        return None;
    }
    Some(String::from_utf8_lossy(&buf[..len as usize]).into_owned())
}

#[cfg(not(target_os = "android"))]
pub fn system_property(_name: &str) -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn oneplus_15_is_v81() {
        let soc = classify("SM8850", "kaanapali");
        assert_eq!(soc.htp_arch, Some(81));
        assert_eq!(
            soc.htp_skel_filename().as_deref(),
            Some("libggml-htp-v81.so")
        );
        assert_eq!(
            soc.marketing_name.as_deref(),
            Some("Snapdragon 8 Elite Gen 5")
        );
        assert!(soc.supports_hexagon_backend());
    }

    #[test]
    fn model_variants_match_by_prefix_case_insensitively() {
        assert_eq!(classify("sm8750-ab", "").htp_arch, Some(79));
        assert_eq!(
            classify("SM8650", "").marketing_name.as_deref(),
            Some("Snapdragon 8 Gen 3")
        );
    }

    #[test]
    fn platform_codename_is_a_fallback() {
        assert_eq!(classify("", "sun").htp_arch, Some(79));
        assert_eq!(classify("unknown", "pineapple").htp_arch, Some(75));
    }

    #[test]
    fn unknown_soc_has_no_npu() {
        let soc = classify("MT6989", "mt6989");
        assert_eq!(soc.htp_arch, None);
        assert!(!soc.supports_hexagon_backend());
        assert_eq!(soc.htp_skel_filename(), None);
    }

    #[test]
    fn host_has_no_system_properties() {
        if !cfg!(target_os = "android") {
            assert_eq!(system_property("ro.soc.model"), None);
        }
    }
}
