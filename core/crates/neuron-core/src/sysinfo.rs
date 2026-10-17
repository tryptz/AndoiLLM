//! Cheap, allocation-light system probes used every token: SoC temperature,
//! available RAM, and the thread count heuristic for the Oryon CPU cluster.

use std::fs;
use std::path::Path;

/// Thermal zones that report the SoC/CPU on Qualcomm kernels, in preference order.
const THERMAL_ZONES: &[&str] = &[
    "/sys/class/thermal/thermal_zone0/temp",
    "/sys/devices/virtual/thermal/thermal_zone0/temp",
];

/// CPU temperature in °C, `None` when sysfs is unreadable (SELinux on some ROMs).
pub fn read_cpu_temperature() -> Option<f32> {
    THERMAL_ZONES
        .iter()
        .find_map(|p| read_temp_file(Path::new(p)))
}

fn read_temp_file(path: &Path) -> Option<f32> {
    let raw = fs::read_to_string(path).ok()?;
    parse_temp(&raw)
}

/// Kernel reports millidegrees; a few report degrees. Negative/absurd values are
/// sensor errors, not cold silicon.
pub fn parse_temp(raw: &str) -> Option<f32> {
    let v: f32 = raw.trim().parse().ok()?;
    let c = if v > 1000.0 { v / 1000.0 } else { v };
    (c > 0.0 && c < 150.0).then_some(c)
}

/// `MemAvailable` from /proc/meminfo in MB, 0 when unavailable.
pub fn read_available_ram_mb() -> u32 {
    fs::read_to_string("/proc/meminfo")
        .ok()
        .and_then(|s| parse_meminfo_available_mb(&s))
        .unwrap_or(0)
}

pub fn parse_meminfo_available_mb(meminfo: &str) -> Option<u32> {
    let line = meminfo.lines().find(|l| l.starts_with("MemAvailable:"))?;
    let kb: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some((kb / 1024) as u32)
}

/// Decode threads for a big.LITTLE Snapdragon: use the performance cluster only.
/// The 8 Elite Gen 5 has 2 prime + 6 performance Oryon cores; the efficiency
/// cores of older parts only slow a matmul down. `requested` = 0 means auto.
pub fn resolve_thread_count(requested: u32, online_cpus: usize) -> i32 {
    if requested > 0 {
        return requested.min(16) as i32;
    }
    let big = online_cpus.saturating_sub(2).clamp(2, 6);
    big as i32
}

pub fn online_cpus() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn temp_parsing_handles_millidegrees_and_degrees() {
        assert_eq!(parse_temp("45000\n"), Some(45.0));
        assert_eq!(parse_temp("45"), Some(45.0));
        assert_eq!(parse_temp("-274000"), None);
        assert_eq!(parse_temp("garbage"), None);
    }

    #[test]
    fn meminfo_available() {
        let s = "MemTotal:       16000000 kB\nMemAvailable:    8192000 kB\n";
        assert_eq!(parse_meminfo_available_mb(s), Some(8000));
        assert_eq!(parse_meminfo_available_mb("MemTotal: 1 kB"), None);
    }

    #[test]
    fn thread_heuristic() {
        assert_eq!(resolve_thread_count(0, 8), 6);
        assert_eq!(resolve_thread_count(0, 4), 2);
        assert_eq!(resolve_thread_count(0, 12), 6);
        assert_eq!(resolve_thread_count(3, 8), 3);
        assert_eq!(resolve_thread_count(99, 8), 16);
    }
}
