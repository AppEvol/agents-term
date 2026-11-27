//! Antigravity harness.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

use super::Harness;

mod usage;

pub struct Agy;

impl Harness for Agy {
    fn kind(&self) -> &'static str {
        "agy"
    }

    fn name(&self) -> &'static str {
        "Antigravity"
    }

    fn label(&self) -> &'static str {
        "Antigravity"
    }

    fn description(&self) -> &'static str {
        "Antigravity CLI"
    }

    fn binary(&self) -> &'static str {
        "agy"
    }

    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value> {
        Box::pin(usage::fetch_usage(home))
    }
}
