//! Claude Code harness.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

use super::Harness;

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

    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value> {
        Box::pin(usage::fetch_usage(home))
    }
}
