# Renamed and removed wire-format field names — never reuse

Once Rumor ships a wire-format version to phones that may never update (rural
users, disaster-cached APKs), every field name in that version is reserved
forever. Reusing a retired name for a new purpose silently corrupts data
between versions: a v0.1 client sees the new payload, deserializes it under
the old type, and acts on garbage. There is no error surface — the wire
parser succeeds and the bug lives downstream.

This file lists every JSON key name that has appeared in a published Rumor
wire format and has since been renamed or removed. **Adding new fields is
always fine; renaming or repurposing is not.** When a field is retired, add
its name here with the version that removed it and a one-line note on what
replaced it.

Policy summary (see `CLAUDE.md` O37 invariant 6):

1. **Additive only.** New fields go inside the reserved `_ext` map on each
   wire object until the next `protocolVersion` bump. Do not add new
   top-level fields between version bumps.
2. **Append-only field order in canonical-byte signature scopes.** Reordering
   fields inside `signableBytes` / `helloChallengeBytes` /
   `blocklistSignableBytes` / `blocklistDiffSignableBytes` breaks every
   pre-existing signature. Adding fields at the end is OK *only* if the new
   field gets its own domain tag bump (`rumor-msg-v2:` etc.).
3. **Type changes are not renames — they are removals.** If `foo: Int`
   becomes `foo: String`, the old `foo` is retired and a new key (`fooStr`
   or similar) takes its place. List the retired key here.
4. **Deprecation window is at least two years** of parse-and-relay support
   for any retired version, because the design target population is exactly
   the users least likely to update.

---

## Retired names

*(Empty as of v0.1. Add entries below as `| name | retired in | replaced by | notes |`.)*

| Name | Retired in | Replaced by | Notes |
|------|------------|-------------|-------|
| _    | _          | _           | _     |
