#!/usr/bin/env bash
# Build/test in a cloud container (Linux, no Android SDK). Everything every cloud
# session used to re-derive by hand:
#   - the toolchain pins JDK 17 and these containers ship 21 → fetch Temurin 17 once
#   - a POSIX locale breaks the em-dash test-method class files → force UTF-8
#   - Maven Central rate-limits (HTTP 429) the shared egress IP, even for curl →
#     resolve through Google's Maven Central mirror first, via a throwaway init
#     script (never committed into the build itself)
#   - a green run can be vacuous → print per-module totals incl. skipped tests and
#     classes that ran zero tests
#
#   scripts/cloud-build.sh :core:test :node:test
#   scripts/cloud-build.sh :simulator:test --tests '*LanLivenessValidationTest'
#
# `:app` cannot build here (no Android SDK) — hand-review `:app` edits and say so.
# Known load flakes under the full parallel `:simulator` suite: LanLivenessValidationTest
# and (once) TransferAssemblerHardeningTest; re-run alone before concluding anything.
set -uo pipefail
cd "$(dirname "$0")/.."

CACHE="${RUMOR_CACHE:-$HOME/.cache/rumor}"
JDK="$CACHE/jdk17"
mkdir -p "$CACHE"

if [ ! -x "$JDK/bin/java" ]; then
  echo "fetching Temurin 17 → $JDK" >&2
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/jdk.tgz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    && mkdir -p "$JDK" && tar xzf "$tmp/jdk.tgz" -C "$JDK" --strip-components=1
  rm -rf "$tmp"
  [ -x "$JDK/bin/java" ] || { echo "JDK 17 download failed" >&2; exit 2; }
fi

cat > "$CACHE/mirror.gradle" <<'EOF'
beforeSettings { s ->
    def mirror = { maven { url 'https://maven-central.storage-download.googleapis.com/maven2' } }
    s.dependencyResolutionManagement.repositories(mirror)
    s.pluginManagement.repositories(mirror)
    s.pluginManagement.repositories { gradlePluginPortal() }
}
EOF

export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" LANG=C.UTF-8 LC_ALL=C.UTF-8

./gradlew --init-script "$CACHE/mirror.gradle" \
  -Dkotlin.compiler.execution.strategy=in-process \
  --console=plain "$@"
rc=$?
python3 scripts/junit-summary.py core node simulator
exit $rc
