//! Claude Code harness.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

use super::{Harness, ModelList};

mod models;
mod usage;

pub struct Claude;

impl Harness for Claude {
    fn kind(&self) -> &'static str {
        "claude"
    }

    fn name(&self) -> &'static str {
        "Claude Code"
    }

    fn label(&self) -> &'static str {
        "Claude"
    }

    fn description(&self) -> &'static str {
        "Claude CLI"
    }

    fn binary(&self) -> &'static str {
        "claude"
    }

    fn cached_models(&self) -> ModelList {
        crate::models::cached_disk_models("claude", models::read_models_from_disk)
    }

    fn models(&self, _force_refresh: bool) -> BoxFuture<'_, ModelList> {
        // Reading the CLI's local files is cheap, so every request re-reads them.
        Box::pin(std::future::ready(crate::models::refresh_disk_models(
            "claude",
            models::read_models_from_disk,
        )))
    }

    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value> {
        Box::pin(usage::fetch_usage(home))
    }
}
