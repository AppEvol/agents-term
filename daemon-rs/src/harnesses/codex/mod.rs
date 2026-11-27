//! Codex harness.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

use super::Harness;

mod usage;

pub struct Codex;

impl Harness for Codex {
    fn kind(&self) -> &'static str {
        "codex"
    }

    fn name(&self) -> &'static str {
        "Codex"
    }

    fn label(&self) -> &'static str {
        "Codex"
    }

    fn description(&self) -> &'static str {
        "Codex CLI"
    }

    fn binary(&self) -> &'static str {
        "codex"
    }

    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value> {
        Box::pin(usage::fetch_usage(home))
    }
}
