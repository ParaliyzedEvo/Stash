package com.stash.core.data.diagnostics

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of app-state suppliers sampled into crash reports.
 *
 * OOM triage for issues #238/#239 stalled because the crash report says
 * nothing about WHAT filled the heap — the stack line is just the alloc
 * that lost. Subsystems register a cheap supplier here (e.g. the player
 * registers its queue/timeline sizes) and [CrashFileStore] appends one
 * line per entry to every report.
 *
 * Suppliers run on the crash thread with the heap possibly exhausted:
 * they must be allocation-light (read pre-computed fields, no queries,
 * no controller/main-thread calls) and are individually wrapped so one
 * failing supplier can't lose the rest of the report.
 */
object CrashDiagnostics {

    private val suppliers = ConcurrentHashMap<String, () -> String>()

    /** Register (or replace) the supplier for [key]. */
    fun register(key: String, supplier: () -> String) {
        suppliers[key] = supplier
    }

    /** One "key: value" line per registered supplier, sorted by key. */
    fun snapshot(): String = buildString {
        suppliers.entries.sortedBy { it.key }.forEach { (key, supplier) ->
            val value = runCatching { supplier() }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
            appendLine("$key: $value")
        }
    }
}
