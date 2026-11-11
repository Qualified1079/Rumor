# :core — rules that apply when you work here

Pure JVM/Kotlin, no Android imports. The root `CLAUDE.md` invariants and host-wiring rules apply; these are the extra ones triggered by edits in this module.

- **Adding a `RumorMessage` field? Add the `MessageEntity` column in `:app` in the same commit** and extend `MessageEntityExtRoundTripTest`. The in-memory repo stores the Kotlin object, so no `:core` or `:simulator` test can see a dropped field (`docs/BUG_HISTORY.md`).
- **Touching a wire format, domain tag, `_ext` key or `MessageType` serial name? Read `docs/RENAMED_FIELDS_NEVER_REUSE.md` first.** Names and tags are reserved forever; a changed canonical-byte layout needs a new tag.
- **Writing a scenario test? Read `docs/SIMULATOR_TESTING.md` first** (summary in `simulator/CLAUDE.md`): real transport, final observable effect, negative controls with teeth. Prefer a pure `:core` scenario when the property is algorithmic.
- **Source-scanning guards match comments as well as code.** `SourceInvariantTest`, `DomainTagInvariantTest`, `SentAtMsLintTest` and `AsciiTestNamesTest` do substring scans; a comment naming a guarded identifier can fail the build, and a stale comment can keep a guard green. Assert emitted bytes, not comments. If you move guarded code to another file, update the guard's path and confirm it still fails when the guard is removed.
- **Verify security claims from the code** (`signableBytes`, the verifier), not from an invariant list. An identifier that is asserted rather than derived is a smell here: `userId = SHA-256(pubkey)`, `trafficClass` from type, routing tag from roomId.
- **No prune without a caller.** Wire it and test it with an injected clock, or file the wiring.
- A deliberately bare engine is fine in a test or attack tool; anything standing for a host goes through `runtime/MeshGraph.kt`.
- Test names stay ASCII (`AsciiTestNamesTest`); class files with other characters cannot be written under a POSIX locale.
