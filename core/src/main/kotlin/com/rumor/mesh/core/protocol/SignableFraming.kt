package com.rumor.mesh.core.protocol

/**
 * O144/O156 — the ONE canonicalization primitive every signed transcript uses.
 *
 * A signature covers a byte string built by concatenating fields. If adjacent
 * variable-length fields are joined by a bare delimiter (`|`, `,`, `:`) that the
 * field content can itself contain, the byte string is *re-partitionable*: two
 * distinct field-sequences produce the same bytes and therefore the same valid
 * signature. A relay (or anyone who has seen one signed object) can then shift
 * content across a field boundary and rebroadcast it under the original
 * signature. This bug was found three times (O144 message store, O156 keyword
 * filters, O157 room transcripts) before being centralized here.
 *
 * The fix is length-prefixed (netstring-style) framing: each field is written as
 * `<charLen>:<value>`. Decoding reads decimal digits up to the first `':'` to get
 * the count, then consumes exactly that many chars — so the value may freely
 * contain `':'`, digits, or any delimiter without ambiguity. This makes the
 * field→byte mapping injective: distinct field-sequences can never collide, so
 * the splice is structurally impossible rather than merely discouraged.
 *
 * `charLen` is the Kotlin `String.length` (UTF-16 code units). Injectivity holds
 * regardless of unit choice as long as it is consistent, because the whole
 * transcript String is UTF-8 encoded once at the end and String→bytes is itself
 * injective.
 *
 * Every transcript that frames free-text or attacker-chosen variable-length
 * fields MUST route through these helpers. `SignableFramingGuardTest` scans the
 * source tree and fails the build if a `*SignableBytes` / `*ChallengeBytes`
 * builder introduces a bare delimiter without being on its documented
 * splice-safe allowlist.
 */
internal fun StringBuilder.framed(value: String): StringBuilder {
    append(value.length)
    append(':')
    append(value)
    return this
}

internal fun StringBuilder.framed(value: Long): StringBuilder = framed(value.toString())

internal fun StringBuilder.framed(value: Int): StringBuilder = framed(value.toString())

internal fun StringBuilder.framed(value: Boolean): StringBuilder = framed(value.toString())

/** Frame a list: count first (so element boundaries are fixed), then each element. */
internal fun StringBuilder.framedList(values: List<String>): StringBuilder {
    framed(values.size)
    values.forEach { framed(it) }
    return this
}
