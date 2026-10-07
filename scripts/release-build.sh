#!/usr/bin/env bash
#
# Release build wrapper for memory-constrained Linux machines.
#
#   ./scripts/release-build.sh                 # assembleRelease
#   ./scripts/release-build.sh clean assembleRelease
#
# ## Why this exists
#
# `gradle.properties` keeps R8's *demand* below physical RAM. This script adds the other
# half: a hard ceiling, so that if demand is exceeded anyway the build is killed by the
# kernel with a clear OOM instead of entering the zram compression loop — where the machine
# becomes unresponsive and CPU time stops advancing, with no diagnostic to explain it.
#
# Failing is strictly better than wedging: it takes seconds, it leaves the system usable,
# and the message names the actual problem.
#
# ## What it does
#
#   1. A cgroup v2 memory cap around the whole build tree (see CGROUP_LIMIT).
#   2. zram/reclaim sysctls, applied if we have the privileges to set them.
#   3. `nice`/`ionice`, so a long build does not make the desktop unusable.
#   4. A stopwatch, because "is it stuck or slow?" is otherwise unanswerable.
#
# Everything degrades gracefully: without root, or on a cgroup v1 host, the build still
# runs — it just loses the hard ceiling.

set -euo pipefail

cd "$(dirname "$0")/.."

# ------------------------------------------------------------------------------
# Tunables
# ------------------------------------------------------------------------------

# Leave headroom for the desktop, the kernel and the page cache. Setting this at or above
# total RAM reintroduces the loop, because the kernel will then be *able* to reach zram.
#
# On a 15 GB machine, 9 GB is generous for this project: the R8 heap is capped at 5 GB in
# gradle.properties, and this ceiling additionally covers native allocations that -Xmx
# does not account for.
CGROUP_LIMIT="${AURIS_RELEASE_MEMORY_LIMIT:-9G}"

# How long to wait for the build before printing a liveness report rather than sitting in
# silence. Also what makes "stalled" distinguishable from "slow" from the outside.
HEARTBEAT_SECONDS="${AURIS_RELEASE_HEARTBEAT:-45}"

# ------------------------------------------------------------------------------
# Presentation
# ------------------------------------------------------------------------------

if [ -t 1 ]; then
    BOLD=$'\033[1m'; DIM=$'\033[2m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; OFF=$'\033[0m'
else
    BOLD=''; DIM=''; YELLOW=''; RED=''; OFF=''
fi

log()  { printf '%s==>%s %s\n' "$BOLD" "$OFF" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$YELLOW" "$OFF" "$*" >&2; }
die()  { printf '%s[fail]%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

# ------------------------------------------------------------------------------
# Host capability check
# ------------------------------------------------------------------------------

total_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
total_gb=$(( total_kb / 1024 / 1024 ))
log "Physical memory: ${total_gb} GB"

has_zram=$(grep -c '^zram' /proc/swaps || true)
if [ "${has_zram:-0}" -gt 0 ]; then
    log "zram active ($(grep '^zram' /proc/swaps | awk '{print $3" total / "$4" used"}'))"
fi

# ------------------------------------------------------------------------------
# sysctl: stop the kernel reaching for zram in the first place
# ------------------------------------------------------------------------------

# These reduce how eagerly the kernel swaps, but they cannot rescue a process that genuinely
# needs more memory than exists. The cgroup cap below is the actual guarantee; these just
# keep ordinary background activity out of zram's way during a long build.
#
# `page-cluster=0` is the one that matters most for zram: read-ahead normally pulls pages in
# in 16-page clusters, so a sequential scan of a large file drags 16 swap pages in to use
# one. Under compression, those clusters are 16 RAM allocations to serve one read.
apply_sysctls() {
    local sudo=""
    if [ "$(id -u)" -ne 0 ]; then
        if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
            sudo="sudo -n"
        else
            return 1
        fi
    fi

    # Swap only as a last resort, never to fill idle CPU.
    $sudo sysctl -w vm.swappiness=1                        >/dev/null 2>&1 || return 1
    # No cluster read-ahead into swap (see above).
    $sudo sysctl -w vm.page-cluster=0                      >/dev/null 2>&1 || return 1
    # Stop the kernel ramping swap in aggressively under pressure. Combined with a bounded
    # R8 heap this keeps us on the reclaim path rather than the compression path.
    $sudo sysctl -w vm.watermark_boost_factor=0            >/dev/null 2>&1 || return 1
    # Start reclaim earlier and in smaller steps instead of one late, expensive stall.
    $sudo sysctl -w vm.watermark_scale_factor=125           >/dev/null 2>&1 || return 1
    return 0
}

if apply_sysctls; then
    log "Applied swap sysctls (swappiness=1, page-cluster=0, watermark_boost_factor=0)"
else
    warn "Could not apply swap sysctls (need root). Build still works; it just has less margin."
fi

# ------------------------------------------------------------------------------
# cgroup v2 memory ceiling
# ------------------------------------------------------------------------------

# Runs the build with a hard memory ceiling.
#
# Prefers `systemd-run --user --scope`, which sets MemoryMax on a transient user scope and
# needs no root. That is the right primitive: if R8 exceeds the ceiling the kernel kills it
# and Gradle reports a failed build, which is a far better outcome than a machine that has
# stopped responding.
#
# Falls back to running directly when no scope mechanism is available. The build still works
# without the ceiling; it just loses the guarantee. Degrading is the point — a host that
# cannot do cgroups must not be unable to build at all.
run_capped() {
    if command -v systemd-run >/dev/null 2>&1 && systemd-run --user --scope true >/dev/null 2>&1; then
        systemd-run --user --scope --quiet \
            -p "MemoryMax=$CGROUP_LIMIT" \
            -p "MemoryAccounting=yes" \
            -- "$@"
        return $?
    fi

    warn "No working systemd user scope; running without a hard memory ceiling."
    warn "Exceeding ${CGROUP_LIMIT} may still stall on zram."
    "$@"
}

# ------------------------------------------------------------------------------
# Liveness reporting
# ------------------------------------------------------------------------------

# R8's stall is silent: no log output, no CPU-time progress. Without this the only signal is
# "still nothing" after several minutes, which is indistinguishable from a slow build.
#
# Reports cumulative CPU time per JVM, which is the measurement that actually distinguishes
# the failure: a healthy build advances it steadily, and a zram-thrashing one goes flat
# while the process is still resident.
heartbeat() {
    local last_total=0
    while true; do
        sleep "$HEARTBEAT_SECONDS"

        local total
        # `cputimes` is cumulative CPU seconds as a bare integer. `time=` would need parsing
        # (and its [[dd-]hh:]mm:ss format has a day field that shifts every position once a JVM
        # passes 24h), which is how an earlier version of this reported a nonsense 4.7e12.
        total=$(ps -o cputimes= -C java 2>/dev/null | awk '{s+=$1} END {print s+0}')

        if [ -z "$total" ]; then
            printf '%s[heartbeat]%s no JVM running — build likely finished\n' "$DIM" "$OFF"
            return
        fi

        local delta=$(( total - last_total ))
        last_total=$total

        if [ "$delta" -eq 0 ]; then
            printf '%s[heartbeat]%s CPU time has NOT advanced in %ss — R8 is almost certainly stuck.\n' \
                "$YELLOW" "$OFF" "$HEARTBEAT_SECONDS"
            printf '%s           %s Rss per JVM: %s\n' "$DIM" "$OFF" \
                "$(ps -o rss= -C java 2>/dev/null | awk '{printf "%.1fGB ", $1/1048576}')"
            printf '%s           %s See scripts/release-build.sh for the full explanation.\n' "$DIM" "$OFF"
        else
            printf '%s[heartbeat]%s progressing (+%ss CPU in %ss)\n' "$DIM" "$OFF" \
                "$delta" "$HEARTBEAT_SECONDS"
        fi
    done
}

# ------------------------------------------------------------------------------
# Run
# ------------------------------------------------------------------------------

TASKS=("$@")
if [ "${#TASKS[@]}" -eq 0 ]; then
    TASKS=(assembleRelease)
fi

log "Tasks: ${TASKS[*]}"
log "Memory ceiling: $CGROUP_LIMIT"
log "R8 heap cap: -Xmx5g, SerialGC, 1 worker (see gradle.properties)"
echo

heartbeat &
HEARTBEAT_PID=$!
# shellcheck disable=SC2064
trap "kill $HEARTBEAT_PID 2>/dev/null || true" EXIT

start=$(date +%s)
status=0

# `nice -n 10` yields the CPU to the desktop; `ionice -c 3` keeps R8's heavy reads and
# writes off the disk queue, which matters a lot when IO is backed by zram.
if command -v ionice >/dev/null 2>&1; then
    run_capped nice -n 10 ionice -c 3 ./gradlew --no-daemon "${TASKS[@]}" || status=$?
else
    run_capped nice -n 10 ./gradlew --no-daemon "${TASKS[@]}" || status=$?
fi

kill $HEARTBEAT_PID 2>/dev/null || true
elapsed=$(( $(date +%s) - start ))

echo
if [ "$status" -eq 0 ]; then
    log "BUILD SUCCEEDED in ${elapsed}s"
    printf 'APK: %s\n' "$(ls -1 app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || echo 'not found')"
else
    die "BUILD FAILED after ${elapsed}s (exit $status)"
    cat <<'EOF'

If the failure was an out-of-memory kill, the ceiling in this script is doing its job:
the machine stayed responsive and the error names the real problem. Either lower the work
per build, or raise the ceiling if you have the headroom:

    AURIS_RELEASE_MEMORY_LIMIT=11G ./scripts/release-build.sh

If instead the heartbeat reported stalled CPU time while the build was still resident, that
is the zram loop and the ceiling was not applied — check the [warn] lines above.
EOF
fi