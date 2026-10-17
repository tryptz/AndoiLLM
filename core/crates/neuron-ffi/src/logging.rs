//! Route `tracing` (ours and llama.cpp's, which the engine forwards) to logcat
//! under the tag `NeuronCore`, so `adb logcat -s NeuronCore Neuron.*` shows the
//! whole stack. Off Android the same subscriber writes to stderr.

use std::sync::Once;

static INIT: Once = Once::new();

pub fn init(verbose: bool) {
    INIT.call_once(|| {
        let level = if verbose {
            tracing::Level::DEBUG
        } else {
            tracing::Level::INFO
        };
        let builder = tracing_subscriber::fmt()
            .with_max_level(level)
            .with_ansi(false)
            .with_target(false)
            .without_time();
        #[cfg(target_os = "android")]
        let result = builder.with_writer(logcat::Writer).try_init();
        #[cfg(not(target_os = "android"))]
        let result = builder.with_writer(std::io::stderr).try_init();
        if let Err(e) = result {
            // A subscriber is already installed (host tests): keep it.
            eprintln!("neuron: tracing subscriber not replaced: {e}");
        }
    });
}

#[cfg(target_os = "android")]
mod logcat {
    use std::ffi::CString;
    use std::io;

    const ANDROID_LOG_INFO: libc::c_int = 4;
    const TAG: &[u8] = b"NeuronCore\0";

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(
            prio: libc::c_int,
            tag: *const libc::c_char,
            text: *const libc::c_char,
        ) -> libc::c_int;
    }

    #[derive(Clone, Copy)]
    pub struct Writer;

    impl io::Write for Writer {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            let text = String::from_utf8_lossy(buf);
            for line in text.lines().filter(|l| !l.trim().is_empty()) {
                if let Ok(c) = CString::new(line.replace('\0', " ")) {
                    // SAFETY: both strings are valid, NUL-terminated C strings.
                    unsafe {
                        __android_log_write(ANDROID_LOG_INFO, TAG.as_ptr() as *const _, c.as_ptr());
                    }
                }
            }
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for Writer {
        type Writer = Writer;
        fn make_writer(&'a self) -> Self::Writer {
            *self
        }
    }
}
