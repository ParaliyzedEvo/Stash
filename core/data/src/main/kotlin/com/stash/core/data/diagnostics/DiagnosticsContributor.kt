package com.stash.core.data.diagnostics

/**
 * One extra section of the diagnostics bundle, owned by a module that
 * `core:data` cannot see (`data:download`'s lossless routing, `core:media`'s
 * player). Bind with `@Binds @IntoSet`; [DiagnosticsBundleBuilder] appends every
 * contributor after its own sections, sorted by [title], and fault-isolates each
 * one the same way it does its own — a throwing contributor degrades to an
 * `[<title> unavailable: …]` line, never a missing bundle.
 *
 * Return the body only; the builder prints the `== title ==` header. Emit no
 * PII: the whole bundle is redacted afterwards, but the redactor is a backstop
 * for secrets that leak through error strings, not a licence to print an email
 * or an endpoint literal.
 */
interface DiagnosticsContributor {
    val title: String
    suspend fun section(): String
}
