#!/usr/bin/env bash
#
# One full WARM_UP reindex + goLive, end to end: starts the target server, waits for it to be ready,
# runs the load, tears the server down and summarises. This is the entry point for "how long does
# publishing this catalog take" - the other two scripts exist for when you want the halves separately
# (profiling the writer, or driving several loads against one server).
#
# Completion and readiness are detected by log CONTENT, never by process liveness: a finished JVM
# lingers as a zombie and `ps` still sees it, so a liveness check hangs forever on a job that is
# already done.
#
#   ROOT           evitaDB checkout to measure                (default /www/oss/evita/release_2026-2)
#   RUN            run label, names the log directories       (default <git-short-sha>)
#   CATALOG        source catalog name                        (default catalog)
#   PRISTINE_DIR   parent of the <CATALOG>/ snapshot folder   (default /var/tmp/evita-warmup-bench/pristine)
#   BENCH_DIR      root for all run artifacts                 (default /var/tmp/evita-warmup-bench)
#   SERVER_XMX     writer heap                                (default 23g)
#   READER_XMX     reader heap                                (default 14g)
#   READER_XMS     reader initial heap                        (default 8g)
#   MAX_PER_COLL   cap entities per collection, 0 = unlimited (default 0)
#   COLLECTIONS    comma-separated subset of collections      (default all)
#   READY_TIMEOUT  seconds to wait for the server             (default 180)
#   PROFILE        none | jfr | alloc | cpu                   (default none)
#   AP_LIB         libasyncProfiler.so, required for alloc/cpu
#   PROFILE_DUMP_TIMEOUT  seconds to let the profiler flush   (default 120)
#
# Anything else the two underlying scripts accept can be exported and will be inherited.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROOT:-/www/oss/evita/release_2026-2}"
CATALOG="${CATALOG:-catalog}"
PRISTINE_DIR="${PRISTINE_DIR:-/var/tmp/evita-warmup-bench/pristine}"
BENCH_DIR="${BENCH_DIR:-/var/tmp/evita-warmup-bench}"
SERVER_XMX="${SERVER_XMX:-23g}"
READER_XMX="${READER_XMX:-14g}"
READER_XMS="${READER_XMS:-8g}"
MAX_PER_COLL="${MAX_PER_COLL:-0}"
COLLECTIONS="${COLLECTIONS:-}"
READY_TIMEOUT="${READY_TIMEOUT:-180}"
PROFILE="${PROFILE:-none}"
AP_LIB="${AP_LIB:-}"
PROFILE_DUMP_TIMEOUT="${PROFILE_DUMP_TIMEOUT:-120}"

if [[ "${PROFILE}" == "alloc" || "${PROFILE}" == "cpu" ]] && [[ -z "${AP_LIB}" || ! -f "${AP_LIB}" ]]; then
	echo "ERROR: PROFILE=${PROFILE} needs AP_LIB pointing at libasyncProfiler.so." >&2
	exit 1
fi
RUN="${RUN:-$(git -C "${ROOT}" rev-parse --short HEAD 2>/dev/null || echo run)}"

SRV_LOG_DIR="${BENCH_DIR}/server/${RUN}"
RUN_LOG_DIR="${BENCH_DIR}/runs/${RUN}"
SRV_OUT="${SRV_LOG_DIR}/stdout.log"
TARGET_DATA_DIR="${TARGET_DATA_DIR:-${BENCH_DIR}/target-data}"

mkdir -p "${SRV_LOG_DIR}" "${RUN_LOG_DIR}"

stamp() { echo "[$(date +%H:%M:%S)] $*"; }

stamp "checkout : ${ROOT}"
GIT_SHA="$(git -C "${ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
GIT_BRANCH="$(git -C "${ROOT}" branch --show-current 2>/dev/null || echo detached)"
stamp "git      : ${GIT_SHA} (${GIT_BRANCH})"
stamp "catalog  : ${CATALOG} from ${PRISTINE_DIR}"
stamp "heaps    : writer ${SERVER_XMX}, reader ${READER_XMX}/${READER_XMS}"

# ---------------------------------------------------------------- writer -----
stamp "starting target server${PROFILE:+ (PROFILE=${PROFILE})}"
XMX="${SERVER_XMX}" XMS="${SERVER_XMX}" RESET_DATA=true \
	ROOT="${ROOT}" TARGET_DATA_DIR="${TARGET_DATA_DIR}" LOG_DIR="${SRV_LOG_DIR}" \
	PROFILE="${PROFILE:-none}" AP_LIB="${AP_LIB:-}" \
	"${SCRIPT_DIR}/run-warmup-target-server.sh" > "${SRV_OUT}" 2>&1 &
SRV_SHELL_PID=$!

COLLAPSED="${SRV_LOG_DIR}/writer-${PROFILE:-none}.collapsed"

# SIGTERM, then wait for the profiler to finish writing, and only then escalate. async-profiler flushes
# its dump from a JVM shutdown hook, so a SIGKILL that arrives first leaves a ZERO-BYTE .collapsed while
# every exit code still reports success. A 23g-heap JVM does not always shut down inside five seconds,
# so the wait is on the file growing - content, not a fixed sleep.
teardown() {
	kill "${SRV_SHELL_PID}" 2>/dev/null
	pkill -f "${ROOT}/evita_server/target/evita-server.jar" 2>/dev/null
	if [[ "${PROFILE:-none}" == "alloc" || "${PROFILE:-none}" == "cpu" ]]; then
		local waited=0
		local size=0
		local previous=-1
		while (( waited < PROFILE_DUMP_TIMEOUT )); do
			size="$(stat -c %s "${COLLAPSED}" 2>/dev/null || echo 0)"
			# non-empty AND no longer growing => the dump is complete
			if (( size > 0 && size == previous )); then break; fi
			previous="${size}"
			sleep 2
			waited=$(( waited + 2 ))
		done
		stamp "profile dump: ${size} bytes after ${waited}s"
		if (( size == 0 )); then
			stamp "WARNING: ${COLLAPSED} is EMPTY - the profile was lost, do not trust this run"
		fi
	else
		sleep 5
	fi
	pkill -9 -f "${ROOT}/evita_server/target/evita-server.jar" 2>/dev/null
}

READY=false
for _ in $(seq 1 "${READY_TIMEOUT}"); do
	if grep -q 'API .gRPC. listening' "${SRV_OUT}" 2>/dev/null; then READY=true; break; fi
	if grep -qE 'BindException|FolderAlreadyUsed|Exception in thread "main"' "${SRV_OUT}" 2>/dev/null; then break; fi
	sleep 1
done

if [[ "${READY}" != "true" ]]; then
	stamp "SERVER DID NOT BECOME READY within ${READY_TIMEOUT}s - aborting"
	tail -40 "${SRV_OUT}"
	teardown
	echo "RUN_STATUS=SERVER_NOT_READY"
	exit 1
fi
stamp "target server ready"

# ---------------------------------------------------------------- loader -----
stamp "running reindex (${COLLECTIONS:-all collections}) - a full production catalog takes tens of minutes"
ROOT="${ROOT}" CATALOG="${CATALOG}" PRISTINE_DIR="${PRISTINE_DIR}" \
	XMX="${READER_XMX}" XMS="${READER_XMS}" MAX_PER_COLL="${MAX_PER_COLL}" \
	COLLECTIONS="${COLLECTIONS}" \
	WORK_DIR="${BENCH_DIR}/work" PER_ENTITY_CSV="${RUN_LOG_DIR}/per-entity.csv" \
	LOG_DIR="${RUN_LOG_DIR}" \
	"${SCRIPT_DIR}/run-warmup-load.sh" > "${RUN_LOG_DIR}/stdout.log" 2>&1
LOAD_EXIT=$?
stamp "load exit=${LOAD_EXIT}"

# ------------------------------------------------------------- teardown ------
stamp "on-disk target catalog: $(du -sh "${TARGET_DATA_DIR}" 2>/dev/null | cut -f1)"
teardown
YOUNG_GCS="$(grep -c 'Pause Young' "${SRV_LOG_DIR}/gc.log" 2>/dev/null)"
FULL_GCS="$(grep -c 'Pause Full' "${SRV_LOG_DIR}/gc.log" 2>/dev/null)"
stamp "writer GC: ${YOUNG_GCS:-0} young, ${FULL_GCS:-0} full"

echo
# The report is fenced by `===` rules on BOTH sides of its title, so a naive range ending at the first
# rule prints the title and nothing else - stop at the second one instead.
sed 's/\x1b\[[0-9;]*m//g' "${RUN_LOG_DIR}/stdout.log" |
	awk '/SINGLE-THREADED WARM_UP BULK LOAD - RESULT/ { f = 1 } f { print; if (/^=+$/ && ++n == 2) exit }'
echo
echo "LOAD_EXIT=${LOAD_EXIT}"
echo "RUN_STATUS=MEASUREMENT-DONE-${RUN}"
echo "artifacts: ${RUN_LOG_DIR} (loader), ${SRV_LOG_DIR} (server + GC log)"
if [[ "${PROFILE}" == "alloc" || "${PROFILE}" == "cpu" ]]; then
	echo "profile  : ${COLLAPSED} ($(stat -c %s "${COLLAPSED}" 2>/dev/null || echo 0) bytes)"
	echo "           copy it out of the tree before the next 'mvn clean'"
fi

# The loader's status is carried out of here, but only at the very end: the server still has to be torn
# down, the report still has to be printed and RUN_STATUS still has to be emitted, all of which happen
# above regardless of how the load went. Falling off the end instead would return 0 for a load that
# failed its post-copy count verification - and a caller gating on the exit code would read a short,
# meaninglessly fast load as a good measurement. The loader distinguishes 0 (counts verified), 2 (load
# stands but counts unverified) and 1 (run failed); all three are passed through unchanged.
exit "${LOAD_EXIT}"
