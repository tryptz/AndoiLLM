//! Chat prompt templates.
//!
//! The templates never emit a literal BOS (`<|begin_of_text|>`, `<s>`): the
//! tokenizer adds it (`add_special = true`) and a second one degrades output.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
pub enum ChatTemplate {
    #[default]
    ChatMl,
    Llama3,
    Gemma,
    Phi,
    Mistral,
    /// `ROLE: text` lines; last resort for unknown models.
    Generic,
}

impl ChatTemplate {
    /// Stable identifiers shared with the Kotlin `ChatTemplate` enum.
    pub fn id(self) -> &'static str {
        match self {
            ChatTemplate::ChatMl => "chatml",
            ChatTemplate::Llama3 => "llama3",
            ChatTemplate::Gemma => "gemma",
            ChatTemplate::Phi => "phi",
            ChatTemplate::Mistral => "mistral",
            ChatTemplate::Generic => "generic",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Some(match id {
            "chatml" => ChatTemplate::ChatMl,
            "llama3" => ChatTemplate::Llama3,
            "gemma" => ChatTemplate::Gemma,
            "phi" => ChatTemplate::Phi,
            "mistral" => ChatTemplate::Mistral,
            "generic" => ChatTemplate::Generic,
            _ => return None,
        })
    }

    /// Guess the template from a GGUF `tokenizer.chat_template` Jinja string.
    pub fn detect(jinja: &str) -> Option<Self> {
        Some(match () {
            _ if jinja.contains("im_start") => ChatTemplate::ChatMl,
            _ if jinja.contains("start_header_id") => ChatTemplate::Llama3,
            _ if jinja.contains("start_of_turn") => ChatTemplate::Gemma,
            _ if jinja.contains("<|system|>") || jinja.contains("<|user|>") => ChatTemplate::Phi,
            _ if jinja.contains("[INST]") => ChatTemplate::Mistral,
            _ => return None,
        })
    }

    /// Fallback from `general.architecture` when the GGUF carries no template.
    pub fn for_architecture(arch: &str) -> Self {
        match arch.to_ascii_lowercase().as_str() {
            "llama" | "llama4" => ChatTemplate::Llama3,
            "gemma" | "gemma2" | "gemma3" | "gemma4" => ChatTemplate::Gemma,
            "phi" | "phi2" | "phi3" | "phimoe" => ChatTemplate::Phi,
            "mistral" | "mixtral" => ChatTemplate::Mistral,
            _ => ChatTemplate::ChatMl,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Role {
    System,
    User,
    Assistant,
}

impl Role {
    fn tag(self) -> &'static str {
        match self {
            Role::System => "system",
            Role::User => "user",
            Role::Assistant => "assistant",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Message {
    pub role: Role,
    pub content: String,
}

impl Message {
    pub fn new(role: Role, content: impl Into<String>) -> Self {
        Self {
            role,
            content: content.into(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum ReasoningEffort {
    #[default]
    None,
    Low,
    Medium,
    High,
}

/// Build the prompt string for one turn.
///
/// `reasoning_capable` models (Qwen3, DeepSeek-R1 distills) honour `/no_think`
/// appended to the last user message when the effort is `None`.
pub fn format_prompt(
    template: ChatTemplate,
    system_prompt: &str,
    messages: &[Message],
    reasoning: ReasoningEffort,
    reasoning_capable: bool,
) -> String {
    let mut owned: Vec<Message>;
    let messages: &[Message] = if reasoning_capable
        && reasoning == ReasoningEffort::None
        && messages
            .last()
            .map(|m| m.role == Role::User)
            .unwrap_or(false)
    {
        owned = messages.to_vec();
        if let Some(last) = owned.last_mut() {
            last.content.push_str(" /no_think");
        }
        &owned
    } else {
        messages
    };
    let system = system_prompt.trim();
    match template {
        ChatTemplate::ChatMl => chatml(system, messages),
        ChatTemplate::Llama3 => llama3(system, messages),
        ChatTemplate::Gemma => gemma(system, messages),
        ChatTemplate::Phi => phi(system, messages),
        ChatTemplate::Mistral => mistral(system, messages),
        ChatTemplate::Generic => generic(system, messages),
    }
}

fn chatml(system: &str, messages: &[Message]) -> String {
    let mut s = String::new();
    if !system.is_empty() {
        s.push_str(&format!("<|im_start|>system\n{system}<|im_end|>\n"));
    }
    for m in messages {
        s.push_str(&format!(
            "<|im_start|>{}\n{}<|im_end|>\n",
            m.role.tag(),
            m.content
        ));
    }
    s.push_str("<|im_start|>assistant\n");
    s
}

fn llama3(system: &str, messages: &[Message]) -> String {
    let mut s = String::new();
    if !system.is_empty() {
        s.push_str(&format!(
            "<|start_header_id|>system<|end_header_id|>\n\n{system}<|eot_id|>"
        ));
    }
    for m in messages {
        s.push_str(&format!(
            "<|start_header_id|>{}<|end_header_id|>\n\n{}<|eot_id|>",
            m.role.tag(),
            m.content
        ));
    }
    s.push_str("<|start_header_id|>assistant<|end_header_id|>\n\n");
    s
}

fn gemma(system: &str, messages: &[Message]) -> String {
    let mut s = String::new();
    if !system.is_empty() {
        s.push_str(&format!(
            "<start_of_turn>user\n[System: {system}]\n<end_of_turn>\n"
        ));
    }
    for m in messages {
        let role = if m.role == Role::User {
            "user"
        } else {
            "model"
        };
        s.push_str(&format!(
            "<start_of_turn>{role}\n{}<end_of_turn>\n",
            m.content
        ));
    }
    s.push_str("<start_of_turn>model\n");
    s
}

fn phi(system: &str, messages: &[Message]) -> String {
    let mut s = String::new();
    if !system.is_empty() {
        s.push_str(&format!("<|system|>\n{system}<|end|>\n"));
    }
    for m in messages {
        s.push_str(&format!("<|{}|>\n{}<|end|>\n", m.role.tag(), m.content));
    }
    s.push_str("<|assistant|>\n");
    s
}

fn mistral(system: &str, messages: &[Message]) -> String {
    let mut s = String::new();
    if !system.is_empty() {
        s.push_str(&format!("[INST] {system}\n\n"));
    }
    for (i, m) in messages.iter().enumerate() {
        match m.role {
            Role::User => {
                let opens_inst =
                    (i == 0 && system.is_empty()) || (i > 0 && messages[i - 1].role != Role::User);
                if opens_inst {
                    s.push_str("[INST] ");
                }
                s.push_str(&format!("{} [/INST]", m.content));
            }
            Role::Assistant => {
                s.push_str(&m.content);
                s.push_str("</s>");
            }
            Role::System => {}
        }
    }
    s
}

fn generic(system: &str, messages: &[Message]) -> String {
    let mut s = String::new();
    if !system.is_empty() {
        s.push_str(&format!("SYSTEM: {system}\n"));
    }
    for m in messages {
        s.push_str(&format!(
            "{}: {}\n",
            m.role.tag().to_ascii_uppercase(),
            m.content
        ));
    }
    s.push_str("ASSISTANT: ");
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn user() -> Vec<Message> {
        vec![Message::new(Role::User, "Hello")]
    }
    fn dialog() -> Vec<Message> {
        vec![
            Message::new(Role::User, "Hello"),
            Message::new(Role::Assistant, "Hi there!"),
        ]
    }
    fn fmt(t: ChatTemplate, system: &str, msgs: &[Message]) -> String {
        format_prompt(t, system, msgs, ReasoningEffort::None, false)
    }

    #[test]
    fn chatml_basic() {
        let r = fmt(ChatTemplate::ChatMl, "", &user());
        assert!(r.contains("<|im_start|>user\nHello<|im_end|>"));
        assert!(r.ends_with("<|im_start|>assistant\n"));
        assert!(!r.contains("<|im_start|>system"));
        let r = fmt(ChatTemplate::ChatMl, "You are helpful.", &user());
        assert!(r.contains("<|im_start|>system\nYou are helpful.<|im_end|>"));
    }

    #[test]
    fn chatml_multi_turn() {
        let r = fmt(ChatTemplate::ChatMl, "", &dialog());
        assert!(r.contains("<|im_start|>assistant\nHi there!<|im_end|>"));
        assert!(r.ends_with("<|im_start|>assistant\n"));
    }

    #[test]
    fn llama3_has_no_literal_bos() {
        let r = fmt(ChatTemplate::Llama3, "", &user());
        assert!(!r.contains("<|begin_of_text|>"));
        assert!(r.starts_with("<|start_header_id|>"));
        assert!(r.contains("<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>"));
        assert!(r.ends_with("<|start_header_id|>assistant<|end_header_id|>\n\n"));
        let r = fmt(ChatTemplate::Llama3, "You are helpful.", &user());
        assert!(
            r.contains("<|start_header_id|>system<|end_header_id|>\n\nYou are helpful.<|eot_id|>")
        );
    }

    #[test]
    fn gemma_maps_assistant_to_model() {
        let r = fmt(ChatTemplate::Gemma, "", &dialog());
        assert!(r.contains("<start_of_turn>user\nHello<end_of_turn>"));
        assert!(r.contains("<start_of_turn>model\nHi there!<end_of_turn>"));
        assert!(r.ends_with("<start_of_turn>model\n"));
        let r = fmt(ChatTemplate::Gemma, "You are helpful.", &user());
        assert!(r.contains("<start_of_turn>user\n[System: You are helpful.]\n<end_of_turn>"));
    }

    #[test]
    fn phi_tags() {
        let r = fmt(ChatTemplate::Phi, "You are helpful.", &user());
        assert!(r.contains("<|system|>\nYou are helpful.<|end|>"));
        assert!(r.contains("<|user|>\nHello<|end|>"));
        assert!(r.ends_with("<|assistant|>\n"));
    }

    #[test]
    fn mistral_inst() {
        let r = fmt(ChatTemplate::Mistral, "", &user());
        assert!(r.starts_with("[INST]"));
        assert!(!r.contains("<s>"));
        assert!(r.contains("[INST] Hello [/INST]"));
        let r = fmt(ChatTemplate::Mistral, "You are helpful.", &user());
        assert!(r.contains("[INST] You are helpful."));
        let r = fmt(ChatTemplate::Mistral, "", &dialog());
        assert!(r.contains("Hi there!</s>"));
    }

    #[test]
    fn generic_fallback() {
        let r = fmt(ChatTemplate::Generic, "sys", &user());
        assert_eq!(r, "SYSTEM: sys\nUSER: Hello\nASSISTANT: ");
    }

    #[test]
    fn no_think_suffix_only_for_reasoning_models_with_effort_none() {
        let r = format_prompt(
            ChatTemplate::ChatMl,
            "",
            &user(),
            ReasoningEffort::None,
            true,
        );
        assert!(r.contains("Hello /no_think<|im_end|>"));
        let r = format_prompt(
            ChatTemplate::ChatMl,
            "",
            &user(),
            ReasoningEffort::High,
            true,
        );
        assert!(!r.contains("/no_think"));
        let r = format_prompt(
            ChatTemplate::ChatMl,
            "",
            &user(),
            ReasoningEffort::None,
            false,
        );
        assert!(!r.contains("/no_think"));
    }

    #[test]
    fn detection_and_ids_round_trip() {
        assert_eq!(
            ChatTemplate::detect("{% if im_start %}"),
            Some(ChatTemplate::ChatMl)
        );
        assert_eq!(
            ChatTemplate::detect("<|start_header_id|>"),
            Some(ChatTemplate::Llama3)
        );
        assert_eq!(
            ChatTemplate::detect("<start_of_turn>"),
            Some(ChatTemplate::Gemma)
        );
        assert_eq!(ChatTemplate::detect("<|user|>"), Some(ChatTemplate::Phi));
        assert_eq!(ChatTemplate::detect("[INST]"), Some(ChatTemplate::Mistral));
        assert_eq!(ChatTemplate::detect("plain"), None);
        for t in [
            ChatTemplate::ChatMl,
            ChatTemplate::Llama3,
            ChatTemplate::Gemma,
            ChatTemplate::Phi,
            ChatTemplate::Mistral,
            ChatTemplate::Generic,
        ] {
            assert_eq!(ChatTemplate::from_id(t.id()), Some(t));
        }
        assert_eq!(
            ChatTemplate::for_architecture("llama"),
            ChatTemplate::Llama3
        );
        assert_eq!(
            ChatTemplate::for_architecture("gemma3"),
            ChatTemplate::Gemma
        );
        assert_eq!(
            ChatTemplate::for_architecture("qwen3"),
            ChatTemplate::ChatMl
        );
        assert_eq!(ChatTemplate::for_architecture("phi3"), ChatTemplate::Phi);
        assert_eq!(
            ChatTemplate::for_architecture("mistral"),
            ChatTemplate::Mistral
        );
    }
}
