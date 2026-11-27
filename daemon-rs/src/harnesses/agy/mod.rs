//! Antigravity harness.

use futures_util::future::BoxFuture;
use serde_json::Value;
use std::path::Path;

use super::{Harness, ModelList};

mod models;
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

    fn cached_models(&self) -> ModelList {
        models::cached_models()
    }

    fn models(&self, force_refresh: bool) -> BoxFuture<'_, ModelList> {
        Box::pin(models::models(force_refresh))
    }

    fn usage<'a>(&'a self, home: &'a Path) -> BoxFuture<'a, Value> {
        Box::pin(usage::fetch_usage(home))
    }
}
