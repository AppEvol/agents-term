//! Codex harness.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

use super::{Harness, ModelList};

mod models;
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

    fn cached_models(&self) -> ModelList {
        crate::models::cached_disk_models("codex", models::read_models_from_disk)
    }

    fn models(&self, _force_refresh: bool) -> BoxFuture<'_, ModelList> {
        // Reading the CLI's local files is cheap, so every request re-reads them.
        Box::pin(std::future::ready(crate::models::refresh_disk_models(
            "codex",
            models::read_models_from_disk,
        )))
    }

    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value> {
        Box::pin(usage::fetch_usage(home))
    }
}
