//! Minimal GGUF v2/v3 header reader.
//!
//! Reads only the metadata key/value section and skips tensor info, so a
//! multi-gigabyte file costs a few hundred kilobytes of IO. The tokenizer
//! vocabulary arrays (128k+ entries) are skipped element-exactly: a capped skip
//! would leave the cursor mid-array and corrupt every later key.

use crate::template::ChatTemplate;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{self, BufReader, Read, Seek, SeekFrom};
use std::path::Path;

const MAGIC: &[u8; 4] = b"GGUF";
const MAX_KV: u64 = 4096;
const MAX_STRING: u64 = 1 << 20;
const MAX_ARRAY: u64 = 100_000_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct GgufInfo {
    pub version: u32,
    pub tensor_count: u64,
    pub name: Option<String>,
    pub architecture: Option<String>,
    pub context_length: Option<u32>,
    pub block_count: Option<u32>,
    /// `general.file_type` as stored.
    pub file_type: Option<u32>,
    /// Raw Jinja template, if the file carries one.
    pub chat_template_raw: Option<String>,
}

impl GgufInfo {
    /// Human label for the file-level quantization (`Q4_K_M`, `F16`, ...).
    pub fn quantization_label(&self) -> Option<&'static str> {
        self.file_type.and_then(file_type_label)
    }

    /// Best-effort template: explicit Jinja first, then architecture.
    pub fn chat_template(&self) -> ChatTemplate {
        self.chat_template_raw
            .as_deref()
            .and_then(ChatTemplate::detect)
            .or_else(|| {
                self.architecture
                    .as_deref()
                    .map(ChatTemplate::for_architecture)
            })
            .unwrap_or_default()
    }

    /// Coarse parameter estimate from the layer count (order of magnitude).
    pub fn estimated_params(&self) -> Option<u64> {
        let blocks = self.block_count? as u64;
        let per_block = match self.architecture.as_deref() {
            Some("gemma") | Some("gemma2") | Some("gemma3") => 7_000_000_000 / 28,
            _ => 7_000_000_000 / 32,
        };
        Some(blocks * per_block)
    }
}

/// llama.cpp `llama_ftype` values that matter for a phone.
pub fn file_type_label(ft: u32) -> Option<&'static str> {
    Some(match ft {
        0 => "F32",
        1 => "F16",
        2 => "Q4_0",
        3 => "Q4_1",
        7 => "Q8_0",
        8 => "Q5_0",
        9 => "Q5_1",
        10 => "Q2_K",
        11 => "Q3_K_S",
        12 => "Q3_K_M",
        13 => "Q3_K_L",
        14 => "Q4_K_S",
        15 => "Q4_K_M",
        16 => "Q5_K_S",
        17 => "Q5_K_M",
        18 => "Q6_K",
        19 => "IQ2_XXS",
        20 => "IQ2_XS",
        24 => "IQ4_NL",
        25 => "IQ3_S",
        26 => "IQ3_M",
        30 => "IQ4_XS",
        32 => "BF16",
        38 => "MXFP4",
        _ => return None,
    })
}

pub fn read_file(path: impl AsRef<Path>) -> io::Result<GgufInfo> {
    let f = File::open(path)?;
    read(BufReader::with_capacity(256 * 1024, f))
}

pub fn read<R: Read + Seek>(mut r: R) -> io::Result<GgufInfo> {
    let mut magic = [0u8; 4];
    r.read_exact(&mut magic)?;
    if &magic != MAGIC {
        return Err(bad("not a GGUF file"));
    }
    let version = u32_le(&mut r)?;
    if !(2..=3).contains(&version) {
        return Err(bad("unsupported GGUF version"));
    }
    let tensor_count = u64_le(&mut r)?;
    let kv_count = u64_le(&mut r)?;
    if kv_count > MAX_KV {
        return Err(bad("implausible metadata count"));
    }

    let mut info = GgufInfo {
        version,
        tensor_count,
        ..Default::default()
    };
    for _ in 0..kv_count {
        let key = string(&mut r)?;
        let ty = u32_le(&mut r)?;
        match key.as_str() {
            "general.architecture" => info.architecture = typed_string(&mut r, ty)?,
            "general.name" => info.name = typed_string(&mut r, ty)?,
            "general.file_type" => info.file_type = typed_u32(&mut r, ty)?,
            "tokenizer.chat_template" => info.chat_template_raw = typed_string(&mut r, ty)?,
            k if k.ends_with(".context_length") => info.context_length = typed_u32(&mut r, ty)?,
            k if k.ends_with(".block_count") => info.block_count = typed_u32(&mut r, ty)?,
            _ => skip_value(&mut r, ty)?,
        }
    }
    Ok(info)
}

// GGUF value types
const T_UINT8: u32 = 0;
const T_INT8: u32 = 1;
const T_UINT16: u32 = 2;
const T_INT16: u32 = 3;
const T_UINT32: u32 = 4;
const T_INT32: u32 = 5;
const T_FLOAT32: u32 = 6;
const T_BOOL: u32 = 7;
const T_STRING: u32 = 8;
const T_ARRAY: u32 = 9;
const T_UINT64: u32 = 10;
const T_INT64: u32 = 11;
const T_FLOAT64: u32 = 12;

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

fn u32_le<R: Read>(r: &mut R) -> io::Result<u32> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(u32::from_le_bytes(b))
}

fn u64_le<R: Read>(r: &mut R) -> io::Result<u64> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    Ok(u64::from_le_bytes(b))
}

fn string<R: Read>(r: &mut R) -> io::Result<String> {
    let len = u64_le(r)?;
    if len > MAX_STRING {
        return Err(bad("string too long"));
    }
    let mut buf = vec![0u8; len as usize];
    r.read_exact(&mut buf)?;
    Ok(String::from_utf8_lossy(&buf).into_owned())
}

fn typed_string<R: Read + Seek>(r: &mut R, ty: u32) -> io::Result<Option<String>> {
    if ty == T_STRING {
        string(r).map(Some)
    } else {
        skip_value(r, ty)?;
        Ok(None)
    }
}

fn typed_u32<R: Read + Seek>(r: &mut R, ty: u32) -> io::Result<Option<u32>> {
    Ok(match ty {
        T_UINT8 | T_INT8 => {
            let mut b = [0u8; 1];
            r.read_exact(&mut b)?;
            Some(b[0] as u32)
        }
        T_UINT16 | T_INT16 => {
            let mut b = [0u8; 2];
            r.read_exact(&mut b)?;
            Some(u16::from_le_bytes(b) as u32)
        }
        T_UINT32 | T_INT32 => Some(u32_le(r)?),
        T_UINT64 | T_INT64 => Some(u64_le(r)? as u32),
        _ => {
            skip_value(r, ty)?;
            None
        }
    })
}

fn fixed_size(ty: u32) -> Option<u64> {
    Some(match ty {
        T_UINT8 | T_INT8 | T_BOOL => 1,
        T_UINT16 | T_INT16 => 2,
        T_UINT32 | T_INT32 | T_FLOAT32 => 4,
        T_UINT64 | T_INT64 | T_FLOAT64 => 8,
        _ => return None,
    })
}

fn skip_value<R: Read + Seek>(r: &mut R, ty: u32) -> io::Result<()> {
    if let Some(n) = fixed_size(ty) {
        r.seek(SeekFrom::Current(n as i64))?;
        return Ok(());
    }
    match ty {
        T_STRING => {
            let len = u64_le(r)?;
            if len > MAX_STRING {
                return Err(bad("string too long"));
            }
            r.seek(SeekFrom::Current(len as i64))?;
            Ok(())
        }
        T_ARRAY => {
            let elem_ty = u32_le(r)?;
            let len = u64_le(r)?;
            if len > MAX_ARRAY {
                return Err(bad("array too long"));
            }
            if let Some(n) = fixed_size(elem_ty) {
                r.seek(SeekFrom::Current((n * len) as i64))?;
            } else {
                for _ in 0..len {
                    skip_value(r, elem_ty)?;
                }
            }
            Ok(())
        }
        _ => Err(bad("unknown GGUF value type")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    /// Tiny GGUF writer for tests.
    struct W(Vec<u8>);
    impl W {
        fn new(kv: u64) -> Self {
            let mut w = W(Vec::new());
            w.0.extend_from_slice(MAGIC);
            w.0.extend_from_slice(&3u32.to_le_bytes());
            w.0.extend_from_slice(&0u64.to_le_bytes());
            w.0.extend_from_slice(&kv.to_le_bytes());
            w
        }
        fn str(&mut self, s: &str) {
            self.0.extend_from_slice(&(s.len() as u64).to_le_bytes());
            self.0.extend_from_slice(s.as_bytes());
        }
        fn kv_str(&mut self, k: &str, v: &str) {
            self.str(k);
            self.0.extend_from_slice(&T_STRING.to_le_bytes());
            self.str(v);
        }
        fn kv_u32(&mut self, k: &str, v: u32) {
            self.str(k);
            self.0.extend_from_slice(&T_UINT32.to_le_bytes());
            self.0.extend_from_slice(&v.to_le_bytes());
        }
        fn kv_str_array(&mut self, k: &str, items: &[&str]) {
            self.str(k);
            self.0.extend_from_slice(&T_ARRAY.to_le_bytes());
            self.0.extend_from_slice(&T_STRING.to_le_bytes());
            self.0
                .extend_from_slice(&(items.len() as u64).to_le_bytes());
            for i in items {
                self.str(i);
            }
        }
        fn kv_f32_array(&mut self, k: &str, n: usize) {
            self.str(k);
            self.0.extend_from_slice(&T_ARRAY.to_le_bytes());
            self.0.extend_from_slice(&T_FLOAT32.to_le_bytes());
            self.0.extend_from_slice(&(n as u64).to_le_bytes());
            for _ in 0..n {
                self.0.extend_from_slice(&1.0f32.to_le_bytes());
            }
        }
    }

    #[test]
    fn parses_core_keys_and_skips_arrays_exactly() {
        let mut w = W::new(7);
        w.kv_str("general.architecture", "qwen3");
        w.kv_str("general.name", "Qwen3 8B");
        w.kv_str_array("tokenizer.ggml.tokens", &["a", "bb", "ccc"]);
        w.kv_f32_array("tokenizer.ggml.scores", 3);
        w.kv_u32("qwen3.context_length", 32768);
        w.kv_u32("general.file_type", 15);
        w.kv_str("tokenizer.chat_template", "{% ... <|im_start|> ... %}");
        let info = read(Cursor::new(w.0)).unwrap();
        assert_eq!(info.architecture.as_deref(), Some("qwen3"));
        assert_eq!(info.name.as_deref(), Some("Qwen3 8B"));
        assert_eq!(info.context_length, Some(32768));
        assert_eq!(info.quantization_label(), Some("Q4_K_M"));
        assert_eq!(info.chat_template(), ChatTemplate::ChatMl);
    }

    #[test]
    fn falls_back_to_architecture_for_template() {
        let mut w = W::new(2);
        w.kv_str("general.architecture", "llama");
        w.kv_u32("llama.block_count", 32);
        let info = read(Cursor::new(w.0)).unwrap();
        assert_eq!(info.chat_template(), ChatTemplate::Llama3);
        assert_eq!(info.estimated_params(), Some(7_000_000_000));
    }

    #[test]
    fn rejects_non_gguf_and_bad_versions() {
        assert!(read(Cursor::new(b"NOPE\0\0\0\0".to_vec())).is_err());
        let mut bytes = MAGIC.to_vec();
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&[0u8; 16]);
        assert!(read(Cursor::new(bytes)).is_err());
    }

    #[test]
    fn truncated_file_is_an_error_not_a_panic() {
        let mut w = W::new(1);
        w.kv_str("general.architecture", "llama");
        let mut bytes = w.0;
        bytes.truncate(bytes.len() - 3);
        assert!(read(Cursor::new(bytes)).is_err());
    }

    #[test]
    fn file_type_labels() {
        assert_eq!(file_type_label(2), Some("Q4_0"));
        assert_eq!(file_type_label(7), Some("Q8_0"));
        assert_eq!(file_type_label(38), Some("MXFP4"));
        assert_eq!(file_type_label(999), None);
    }
}
