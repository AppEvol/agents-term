//! Coding-agent harnesses (Antigravity, Claude Code, Codex).
//!
//! Each harness implements [`Harness`] in its own module, and the rest of the daemon looks
//! harnesses up through [`get`] and [`all`] instead of branching on the agent kind. To add a
//! harness, create a module next to `agy`, `claude` and `codex` and list it in [`REGISTRY`].
//! The shell agent is not a harness: it runs a persistent PTY and has no models or usage.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

mod agy;
mod claude;
mod codex;

/// The models a harness offers and the one its CLI uses by default.
#[derive(Default)]
pub struct ModelList {
    pub default_model: Option<String>,
    pub models: Vec<Value>,
}

/// The strategy every coding-agent harness implements.
pub trait Harness: Send + Sync {
    /// Agent kind used on the wire and in stored tabs (`"agy"`, `"claude"`, ...).
    fn kind(&self) -> &'static str;
    /// Product name shown in the agent picker.
    fn name(&self) -> &'static str;
    /// Short name used as the default tab label.
    fn label(&self) -> &'static str;
    /// One-line description shown in the agent picker.
    fn description(&self) -> &'static str;
    /// CLI binary looked up on `PATH`.
    fn binary(&self) -> &'static str;
    /// The last known model list, without blocking; may start a background refresh.
    fn cached_models(&self) -> ModelList;
    /// The model list, re-read or re-discovered when `force_refresh` is set or the cache is stale.
    fn models(&self, force_refresh: bool) -> BoxFuture<'_, ModelList>;
    /// Fetches the signed-in account's remaining usage, as the `/api/agents/{agent}/usage` body.
    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value>;
}

static REGISTRY: &[&dyn Harness] = &[&agy::Agy, &claude::Claude, &codex::Codex];

/// Every registered harness, in the order the app lists them.
pub fn all() -> &'static [&'static dyn Harness] {
    REGISTRY
}

/// The harness for an agent kind, or `None` for the shell and unknown kinds.
pub fn get(kind: &str) -> Option<&'static dyn Harness> {
    REGISTRY.iter().copied().find(|h| h.kind() == kind)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_lists_each_harness_once_in_app_order() {
        let kinds: Vec<_> = all().iter().map(|h| h.kind()).collect();
        assert_eq!(kinds, ["agy", "claude", "codex"]);
        for kind in kinds {
            assert_eq!(get(kind).map(|h| h.kind()), Some(kind));
        }
        assert!(get("shell").is_none());
        assert!(get("unknown").is_none());
    }
}
