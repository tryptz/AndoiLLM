use thiserror::Error;

/// Every failure the core can report to the app. The FFI layer flattens this to a
/// message string, so each variant carries enough context to be shown to a user.
#[derive(Debug, Error)]
pub enum NeuronError {
    #[error("no model is loaded")]
    NoModel,
    #[error("a generation is already running")]
    Busy,
    #[error("generation cancelled")]
    Cancelled,
    #[error("model file not found: {0}")]
    ModelNotFound(String),
    #[error("failed to load model: {0}")]
    ModelLoad(String),
    #[error("failed to create inference context: {0}")]
    Context(String),
    #[error("prompt of {prompt_tokens} tokens does not fit in a context of {n_ctx}")]
    PromptTooLong { prompt_tokens: usize, n_ctx: usize },
    #[error("tokenization failed: {0}")]
    Tokenize(String),
    #[error("decode failed: {0}")]
    Decode(String),
    #[error("backend unavailable: {0}")]
    BackendUnavailable(String),
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, NeuronError>;
