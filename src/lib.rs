// SPDX-License-Identifier: MIT OR Apache-2.0
// Module declarations
mod database;
pub mod database_utils;
pub mod date_utils;
pub mod domain;
pub mod file_extensions;
pub mod file_survey;
pub mod git;
pub mod git_range;
pub mod guard;
pub mod hash;
pub mod indexer;
pub mod perf_monitor;
pub mod symbol_walkback;
pub mod text_utils;
mod treesitter_analyzer;
mod types;
mod vectorizer;
pub mod workdir;
pub mod worktree;

// Query functionality modules
pub mod callchain;
pub mod diffdump;
pub mod display;
pub mod lore_repack;
pub mod lore_writers;
pub mod pages;
pub mod search;

/// Worker threads for CPU-bound indexing pipelines. `SEMCODE_WORKERS` caps it
/// (the plugin passes a fraction of the cores so a running index leaves the
/// machine usable); unset or invalid = all cores.
pub fn worker_limit() -> usize {
    std::env::var("SEMCODE_WORKERS")
        .ok()
        .and_then(|s| s.parse().ok())
        .filter(|n| *n >= 1)
        .unwrap_or_else(num_cpus::get)
        .max(1)
}

/// Drop this process to below-normal priority when `SEMCODE_LOW_PRIORITY` is
/// set (the plugin sets it for its children). Thread caps bound the worst
/// case; the scheduler priority is what keeps the user's foreground apps
/// responsive while the cap is in use. Child processes inherit the class.
pub fn lower_process_priority() {
    if std::env::var("SEMCODE_LOW_PRIORITY").as_deref() != Ok("1") {
        return;
    }
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::Threading::{
            GetCurrentProcess, SetPriorityClass, BELOW_NORMAL_PRIORITY_CLASS,
        };
        SetPriorityClass(GetCurrentProcess(), BELOW_NORMAL_PRIORITY_CLASS);
    }
    #[cfg(not(windows))]
    {
        //Unix: nice() via libc is left out for now; the plugin ships win-x64 first.
    }
}

// Re-export the main types and structs
pub use database::processed_files::ProcessedFileRecord;
pub use database::resolution::{Evidence, IndirectCaller};
pub use database::SCHEMA_VERSION;
pub use database::{DatabaseManager, LoreIdState, REFERENCE_SCAN_BUDGET_EXCEEDED};
pub use database_utils::process_database_path;
pub use git::{get_git_sha, get_git_sha_for_workdir};
pub use hash::{compute_content_hash, compute_file_hash};
pub use text_utils::preprocess_code;
pub use treesitter_analyzer::{
    extractor_version, extractor_version_for_file, extractor_versions, Language, ParameterFate,
    TreeSitterAnalyzer, EXTRACTOR_VERSION,
};
pub use types::Handover;
pub use types::{
    path_is_other_program, path_language, qualified_name, row_defines_the_function, under,
    ArgumentFunction, CallEdge, ChosenDefinition, DefinitionSite, DispatchKind, DispatchSite,
    FieldInfo, FunctionInfo, GitCommitInfo, GitFileEntry, GitFileManifestEntry, GlobalTypeRegistry,
    LoreEmailInfo, ParameterInfo, Registration, RegistrationKind, Resolution, Surface, TypeInfo,
    TypedefInfo,
};
pub use vectorizer::CodeVectorizer;
pub use workdir::WorkdirIndex;

// Re-export database types
pub use database::calls::CallRelationship;
pub use database::search::{FunctionMatch, LoreEmailFilters};

// Logging utilities
pub mod logging {
    use tracing_subscriber::EnvFilter;

    /// Initialize tracing with SEMCODE_DEBUG environment variable support
    /// This provides consistent logging configuration across all semcode binaries
    pub fn init_tracing() {
        let log_level = std::env::var("SEMCODE_DEBUG").unwrap_or_else(|_| "error".to_string());

        // Map common values to appropriate filter strings
        let filter_str = match log_level.as_str() {
            "0" | "off" | "none" => "error",
            "1" | "warn" => "warn",
            "2" | "info" => "info",
            "3" | "debug" => "debug",
            "4" | "trace" => "trace",
            // Allow custom filter strings like "semcode=debug,lancedb=warn"
            custom => custom,
        };

        // Check if SEMCODE_DEBUG contains specific module overrides
        let has_custom_modules = log_level.contains("ort=")
            || log_level.contains("lancedb=")
            || log_level.contains("lance=")
            || log_level.contains("lance_index=");

        let mut env_filter =
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(filter_str));

        // Only add default noise-reduction directives if user hasn't specified custom ones
        if !has_custom_modules {
            env_filter = env_filter
                .add_directive("ort=error".parse().unwrap())
                .add_directive("lancedb=warn".parse().unwrap())
                .add_directive("lance=warn".parse().unwrap())
                .add_directive("lance_index=warn".parse().unwrap())
                .add_directive("lance::index::vector::builder=error".parse().unwrap()) // Suppress "empty partition" warnings during IVF index building
                .add_directive("DatasetRecordBatchStream=error".parse().unwrap());
        }

        tracing_subscriber::fmt()
            .with_env_filter(env_filter)
            .with_writer(std::io::stderr)
            .init();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // One serial test, not three parallel ones: they all mutate the same
    // environment variable, and cargo runs tests on separate threads.
    #[test]
    fn worker_limit_reads_env_serially() {
        // Unset: every core — CLI users want speed, and the plugin (which
        // wants modesty) always sets the variable.
        std::env::remove_var("SEMCODE_WORKERS");
        assert_eq!(worker_limit(), num_cpus::get());

        // A valid value caps the count.
        std::env::set_var("SEMCODE_WORKERS", "4");
        assert_eq!(worker_limit(), 4);

        // Zero and garbage mean unset, not 1.
        std::env::set_var("SEMCODE_WORKERS", "0");
        assert_eq!(worker_limit(), num_cpus::get(), "0 means unset, not 1");
        std::env::set_var("SEMCODE_WORKERS", "not_a_number");
        assert_eq!(worker_limit(), num_cpus::get());

        std::env::remove_var("SEMCODE_WORKERS");
    }
}
