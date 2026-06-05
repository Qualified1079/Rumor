# Project signing keys

This file is the policy + audit log for keys the Rumor project itself
holds for signing release-relevant artifacts.

## Currently held keys

**None.** No project signing key has been generated yet.

A key will be created at the moment there is a concrete first artifact
to sign — most likely the first bundled keyword filter list under O67
(`docs/CRYPTO_PRIMITIVES_AUDIT.md` and the O67 backlog row for context).
Until then, this file exists to (1) record the policy below and (2)
prevent a future contributor from generating a key in CI and committing
the private half by accident.

## Policy

1. **Private key material is generated offline, on a clean machine.**
   Never inside a CI environment, never on a build server, never on a
   contributor's regular dev machine. Air-gapped or hardware-token-backed
   if at all feasible.

2. **Private key never enters this repo.** Period. Not encrypted, not
   in `.gitignore`, not in environment variables checked in to CI
   configs. If a private key is ever committed by accident, treat it as
   compromised, rotate immediately, document the rotation here.

3. **Public keys are committed to this file.** When a key is generated,
   the base64-encoded Ed25519 public key, the date of generation, the
   intended purpose (e.g. "default-list publisher"), and the maintainer
   responsible appear here. Users can verify any signed artifact by
   re-deriving against the public key recorded.

4. **Rotation is signed by the old key when possible.** A rotation
   message (signed by the soon-to-be-retired key, naming the new public
   key) lets users automatically pick up the new key with confidence.
   This is the SAME mechanism deprecated for individual identity
   rotation (G9/O41 — auto-rebind turns key-compromise into permanent
   impersonation), but it's safe at the *project* level because the
   project key signs only public artifacts (filter lists, plugin
   manifests); there is no "all my DMs decrypt to a different
   identity now" failure mode.

5. **If rotation is happening BECAUSE the old key was compromised**,
   the old-key-signed rotation message can't be trusted (the attacker
   has the old key too). Out-of-band announcement is required —
   release notes, project blog, multiple maintainer-signed channels.
   Users who only see the on-mesh rotation must be prompted to
   re-confirm at app update time.

6. **Compromise procedure.** If a private key is suspected compromised:
   (a) immediately publish a signed-by-the-suspected-key revocation
       (a no-op transcript binding that key to the empty future),
   (b) generate a new key per policy 1,
   (c) re-sign every artifact that was signed by the old key,
   (d) ship an app update that pins the new key as a known-good root
       AND surfaces a user prompt "the project signing key was
       compromised; please re-confirm trust" on first launch,
   (e) document the incident in this file with date and what was
       compromised.

## Reserved name

`rumor-project-default-lists-v1` — the intended kind-identifier for
the first bundled-filter-list signing key. Not generated yet.
Reserved here so that no other purpose claims the name.

## Why no key today

The first real artifact that benefits from project signing is the
default keyword filter lists from O67. Per the design conversation
recorded in HANDOFF.md, the v1 default is to ship NO bundled lists —
users opt in to community-published lists or author their own. Until
there is a list to sign, generating a key now (especially one that
would touch CI in any form) just creates surface area for accidental
compromise.

When the first list is ready to ship, the release maintainer at that
moment generates the key per the policy above, commits the public
half to this file under "Currently held keys" with the date, and
signs the list offline.
