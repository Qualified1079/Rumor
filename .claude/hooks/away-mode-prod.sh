#!/bin/sh
# Away-mode "keep going" prod (Stop hook).
#
# Dormant by default. Activates only while the flag file exists, so a fresh
# clone gets this hook but it does nothing until you opt in:
#   Enable  (before you step away):  touch ~/.claude/away-mode
#   Disable (when you're back):      rm -f ~/.claude/away-mode
#
# While active, every attempt to end the turn is blocked and the model is fed
# the "reason" below as a continuation prompt — the antidote to the stop-reflex
# ("this is too big for one session", "better done fresh"). The ONLY way to end
# the loop is to remove the flag, which is entirely in your hands.

FLAG="$HOME/.claude/away-mode"
[ -f "$FLAG" ] || exit 0   # flag absent → allow the stop, no output

# One-line JSON (JSON strings can't contain literal newlines). No embedded
# double-quotes in the reason, so nothing here needs escaping.
cat <<'JSON'
{"decision":"block","reason":"AWAY MODE is ON — the user is away and explicitly told you to keep going. Do NOT stop. Re-run your four questions and answer #4 (should I stop?) as NO: you have tokens. Do not rationalize stopping — 'this feature is too big for one session' / 'better done fresh' is EXACTLY the reflex to override. Commit and push incrementally; a partial feature with a clear handoff is fine and expected. Pick the next most-foundational backlog item (or continue the current one) and act NOW with a tool call. If you are genuinely blocked on a decision only the user can make, switch to a DIFFERENT backlog item and record the blocker in handoff.md rather than stopping. The user ends this loop by running: rm ~/.claude/away-mode"}
JSON
