# O-rbsr-v2 (archive: full tombstone record)

**`[DROPPED 2026-07-16, merge]`** NIP-77-compatible raw-id-sum RBSR fingerprint**

Archimedes added a `rbsr-v2` capability whose fingerprint summed RAW message ids mod 2^256 (byte-compatible with hoytech/negentropy + Nostr NIP-77, motivated by strfry interop for O72/O54).
**Dropped in the merge: forgeable in Rumor's threat model.** In Nostr an id IS a content hash, so an attacker can't choose it;
Rumor ids are sender-chosen, so the last id in a range can steer the sum to any target — a chosen-id collision, exactly the linearity hole the additive-over-SHA256(domain-tagged-item) formula (kept, field-verified) closes.
If Nostr interop becomes real, reintroduce as a *hash-the-ids-first* variant — never raw-id sums. `FingerprintFormula` machinery + 2 tests deleted with it;
see `docs/ARCHIMEDES_MERGE_CATALOG.md`.
