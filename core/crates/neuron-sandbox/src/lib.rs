//! # neuron-sandbox
//!
//! Runs model-written JavaScript inside an isolated QuickJS runtime.
//!
//! Isolation model:
//! * A fresh `Runtime` + `Context` per run; nothing survives between runs.
//! * Only the ECMAScript intrinsics are installed (`Context::full`). QuickJS'
//!   `std`/`os` modules, which expose files, sockets and processes, are not
//!   linked in at all, so there is nothing to "disable".
//! * Hard memory ceiling (`set_memory_limit`), 1 MB stack ceiling, and a
//!   wall-clock deadline enforced through the interpreter's interrupt hook.
//! * `console.*` is captured into stdout/stderr buffers instead of reaching
//!   the process' streams.

use rquickjs::{CatchResultExt, Coerced, Context, Function, Object, Runtime, Value};
use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct JsLimits {
    pub memory_limit_bytes: u64,
    pub timeout_ms: u64,
    pub max_stack_bytes: u64,
}

impl Default for JsLimits {
    fn default() -> Self {
        Self {
            memory_limit_bytes: 64 * 1024 * 1024,
            timeout_ms: 10_000,
            max_stack_bytes: 1024 * 1024,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum JsErrorKind {
    Syntax,
    Runtime,
    Timeout,
    Memory,
    Security,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct JsError {
    pub kind: JsErrorKind,
    pub message: String,
    pub line: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct JsRunResult {
    pub stdout: String,
    pub stderr: String,
    /// `String(completionValue)` unless it was `undefined`.
    pub return_value: Option<String>,
    pub execution_time_ms: u64,
    pub memory_used_bytes: u64,
    pub error: Option<JsError>,
}

const PRELUDE: &str = r#"
globalThis.__neuron_out = [];
globalThis.__neuron_err = [];
(function () {
  const join = (args) => Array.prototype.map.call(args, (a) => {
    if (typeof a === 'string') return a;
    try { return JSON.stringify(a); } catch (_) { return String(a); }
  }).join(' ');
  globalThis.console = {
    log:   function () { __neuron_out.push(join(arguments)); },
    info:  function () { __neuron_out.push(join(arguments)); },
    debug: function () { __neuron_out.push(join(arguments)); },
    error: function () { __neuron_err.push(join(arguments)); },
    warn:  function () { __neuron_err.push('WARN: ' + join(arguments)); },
  };
})();
"#;

const MAX_CAPTURE_CHARS: usize = 1024 * 1024;

/// Execute `code` and report what it printed, returned, or threw.
pub fn run_javascript(code: &str, limits: JsLimits) -> JsRunResult {
    let started = Instant::now();
    let mut result = JsRunResult::default();

    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            result.error = Some(JsError {
                kind: JsErrorKind::Runtime,
                message: format!("runtime: {e}"),
                line: None,
            });
            return result;
        }
    };
    rt.set_memory_limit(limits.memory_limit_bytes as usize);
    rt.set_max_stack_size(limits.max_stack_bytes as usize);
    let deadline = started + Duration::from_millis(limits.timeout_ms.max(1));
    rt.set_interrupt_handler(Some(Box::new(move || Instant::now() >= deadline)));

    let ctx = match Context::full(&rt) {
        Ok(c) => c,
        Err(e) => {
            result.error = Some(JsError {
                kind: JsErrorKind::Runtime,
                message: format!("context: {e}"),
                line: None,
            });
            return result;
        }
    };

    ctx.with(|ctx| {
        if let Err(e) = ctx.eval::<(), _>(PRELUDE) {
            result.error = Some(JsError {
                kind: JsErrorKind::Runtime,
                message: format!("prelude: {e}"),
                line: None,
            });
            return;
        }
        let outcome = ctx.eval::<Value, _>(code).catch(&ctx);
        match outcome {
            Ok(v) => result.return_value = completion_value(&ctx, v),
            Err(caught) => {
                result.error = Some(classify_error(
                    caught,
                    started >= deadline || Instant::now() >= deadline,
                ))
            }
        }
        let globals = ctx.globals();
        result.stdout = join_captured(&globals, "__neuron_out");
        result.stderr = join_captured(&globals, "__neuron_err");
    });

    result.memory_used_bytes = rt.memory_usage().memory_used_size.max(0) as u64;
    result.execution_time_ms = started.elapsed().as_millis() as u64;
    result
}

fn completion_value<'js>(ctx: &rquickjs::Ctx<'js>, v: Value<'js>) -> Option<String> {
    if v.is_undefined() {
        return None;
    }
    let to_string: Function = ctx.globals().get("String").ok()?;
    let s: Coerced<String> = to_string.call((v,)).ok()?;
    Some(s.0)
}

fn join_captured(globals: &Object<'_>, key: &str) -> String {
    let Ok(arr) = globals.get::<_, rquickjs::Array>(key) else {
        return String::new();
    };
    let mut out = String::new();
    for item in arr.iter::<Coerced<String>>() {
        let Ok(s) = item else { continue };
        if !out.is_empty() {
            out.push('\n');
        }
        if out.len() + s.0.len() > MAX_CAPTURE_CHARS {
            out.push_str("… [output truncated]");
            break;
        }
        out.push_str(&s.0);
    }
    out
}

fn classify_error(caught: rquickjs::CaughtError<'_>, timed_out: bool) -> JsError {
    use rquickjs::CaughtError;
    let (message, name, stack) = match &caught {
        CaughtError::Exception(ex) => (
            ex.message().unwrap_or_else(|| "error".into()),
            ex.as_object()
                .get::<_, Coerced<String>>("name")
                .map(|c| c.0)
                .unwrap_or_default(),
            ex.stack().unwrap_or_default(),
        ),
        // QuickJS has no heap left to build an Error object when the memory
        // limit trips: the thrown value is a bare `null`.
        CaughtError::Value(v) if v.is_null() || v.is_undefined() => {
            ("out of memory".to_string(), String::new(), String::new())
        }
        CaughtError::Value(v) => (format!("{:?}", v), String::new(), String::new()),
        CaughtError::Error(e) => (e.to_string(), String::new(), String::new()),
    };
    let lower = format!("{name} {message}").to_ascii_lowercase();
    let kind = if timed_out || lower.contains("interrupted") {
        JsErrorKind::Timeout
    } else if lower.contains("out of memory") || lower.contains("memory limit") {
        JsErrorKind::Memory
    } else if name == "SyntaxError" {
        JsErrorKind::Syntax
    } else {
        JsErrorKind::Runtime
    };
    let message = match kind {
        JsErrorKind::Timeout => "Execution timed out".to_string(),
        JsErrorKind::Memory => "Out of memory: the sandbox memory limit was exceeded".to_string(),
        _ if name.is_empty() => message,
        _ => format!("{name}: {message}"),
    };
    JsError {
        kind,
        message,
        line: parse_line(&stack),
    }
}

/// QuickJS stacks look like `    at <eval> (eval_script:3:7)`.
fn parse_line(stack: &str) -> Option<u32> {
    let idx = stack.find("eval_script:")?;
    let rest = &stack[idx + "eval_script:".len()..];
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(code: &str) -> JsRunResult {
        run_javascript(code, JsLimits::default())
    }

    #[test]
    fn captures_console_and_return_value() {
        let r =
            run("console.log('hello', 1, {a: 2}); console.error('bad'); console.warn('w'); 40 + 2");
        assert_eq!(r.error, None);
        assert_eq!(r.stdout, "hello 1 {\"a\":2}");
        assert_eq!(r.stderr, "bad\nWARN: w");
        assert_eq!(r.return_value.as_deref(), Some("42"));
        assert!(r.memory_used_bytes > 0);
    }

    #[test]
    fn undefined_completion_has_no_return_value() {
        let r = run("let x = 1;");
        assert_eq!(r.return_value, None);
        assert_eq!(r.error, None);
    }

    #[test]
    fn syntax_errors_are_classified() {
        let r = run("function (");
        let e = r.error.expect("error");
        assert_eq!(e.kind, JsErrorKind::Syntax);
        assert!(e.message.starts_with("SyntaxError"));
    }

    #[test]
    fn runtime_errors_carry_name_and_line() {
        let r = run("const a = 1;\nnull.foo;");
        let e = r.error.expect("error");
        assert_eq!(e.kind, JsErrorKind::Runtime);
        assert!(e.message.starts_with("TypeError"), "{}", e.message);
        assert_eq!(e.line, Some(2));
    }

    #[test]
    fn infinite_loops_hit_the_deadline() {
        let r = run_javascript(
            "while (true) {}",
            JsLimits {
                timeout_ms: 100,
                ..Default::default()
            },
        );
        let e = r.error.expect("error");
        assert_eq!(e.kind, JsErrorKind::Timeout);
        assert!(r.execution_time_ms < 5_000);
    }

    #[test]
    fn memory_bombs_hit_the_ceiling() {
        let r = run_javascript(
            "let a = []; for (let i = 0; i < 1e8; i++) a.push('x'.repeat(1024));",
            JsLimits {
                memory_limit_bytes: 8 * 1024 * 1024,
                timeout_ms: 5_000,
                ..Default::default()
            },
        );
        let e = r.error.expect("error");
        assert_eq!(e.kind, JsErrorKind::Memory, "{e:?}");
    }

    #[test]
    fn no_host_access_globals() {
        for g in [
            "std",
            "os",
            "require",
            "process",
            "fetch",
            "XMLHttpRequest",
            "Deno",
            "Bun",
        ] {
            let r = run(&format!("typeof {g}"));
            assert_eq!(
                r.return_value.as_deref(),
                Some("undefined"),
                "{g} must not exist"
            );
        }
    }

    #[test]
    fn runs_are_isolated_from_each_other() {
        run("globalThis.leak = 42;");
        let r = run("typeof leak");
        assert_eq!(r.return_value.as_deref(), Some("undefined"));
    }
}
