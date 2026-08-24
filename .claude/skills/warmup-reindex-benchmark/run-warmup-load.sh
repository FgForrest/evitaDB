#!/usr/bin/env bash
#
# Runs io.evitadb.performance.warmupload.IsolatedWarmupLoadBenchmark - the READ side of the isolated
# WARM_UP reindex benchmark. It boots an embedded evitaDB holding the original catalog snapshot and
# re-upserts every entity into a freshly created catalog, single-threaded, through one long-lived
# WARM_UP session, then transitions that catalog to ALIVE.
#
# In the default `remote` mode the target catalog lives on a SEPARATE server process, which must
# already be running - start it with run-warmup-target-server.sh and attach your profiler to THAT
# process, not this one. This process only reads.
#
# In `embedded` mode the target is a second catalog in this same JVM. That is useless for profiling
# (reader and writer share every thread) but it gives a transport-free latency baseline: run both
# modes and the difference is what gRPC costs you.
#
# The pristine snapshot directory is never written to - the harness copies it into a disposable
# working directory on every run. PRISTINE_DIR must be the PARENT of the <CATALOG>/ folder.
#
# WORK_DIR is wiped before the copy and again at teardown, so the harness deletes it only when it is
# absent, empty, or carries the `.evita-warmup-workdir` marker it drops there - point WORK_DIR at a
# directory holding anything else and the run refuses to start rather than wiping it.
#
#   ROOT           evitaDB checkout to take the jar from          (default /www/oss/evita/release_2026-2)
#   PRISTINE_DIR   parent of the <CATALOG>/ snapshot folder       (default /var/tmp/senesi-bench/pristine)
#   CATALOG        source catalog name                            (default senesi)
#   TARGET_MODE    remote | embedded                              (default remote)
#   TARGET         target catalog name        (default: <CATALOG> remote, <CATALOG>_warmup embedded)
#   TARGET_HOST    target server host                             (default localhost)
#   TARGET_PORT    target server gRPC port                        (default 5555)
#   WORK_DIR       disposable working copy of the source          (default /var/tmp/senesi-bench/work)
#   XMX            reader heap                                    (default 14g)
#   XMS            reader initial heap                            (default 8g)
#   MAX_PER_COLL   cap entities per collection, 0 = unlimited     (default 0)
#   COLLECTIONS    comma-separated subset of collections          (default all)
#   BATCH_SIZE     entities read from the source per fetch        (default 1000)
#   PROGRESS       upserts between progress lines, 0 = off        (default 25000)
#   HOLD_OPEN      seconds to keep THIS jvm alive after report    (default 0)
#   PER_ENTITY_CSV path for a per-upsert CSV                      (default none)
#   KEEP_WORK_DIR  keep the working dir after the run             (default false)
#   SKIP_VERIFY    skip the post-load count verification          (default false)
#   LOG_DIR        where the run log and GC log land
#   JAVA_BIN       JVM to use                                     (default JDK 17)
#
# Heap size is NOT a neutral knob: it feeds the collation-key cache's heap-derived default sizing, and
# an undersized heap turns this workload GC-bound. Never compare two runs taken at different heaps.

set -euo pipefail

ROOT="${ROOT:-/www/oss/evita/release_2026-2}"
JAR="${ROOT}/evita_test/evita_performance_tests/target/benchmarks.jar"

PRISTINE_DIR="${PRISTINE_DIR:-/var/tmp/senesi-bench/pristine}"
CATALOG="${CATALOG:-senesi}"
TARGET_MODE="${TARGET_MODE:-remote}"
TARGET_HOST="${TARGET_HOST:-localhost}"
TARGET_PORT="${TARGET_PORT:-5555}"
WORK_DIR="${WORK_DIR:-/var/tmp/senesi-bench/work}"
XMX="${XMX:-14g}"
XMS="${XMS:-8g}"
MAX_PER_COLL="${MAX_PER_COLL:-0}"
COLLECTIONS="${COLLECTIONS:-}"
BATCH_SIZE="${BATCH_SIZE:-1000}"
PROGRESS="${PROGRESS:-25000}"
HOLD_OPEN="${HOLD_OPEN:-0}"
PER_ENTITY_CSV="${PER_ENTITY_CSV:-}"
KEEP_WORK_DIR="${KEEP_WORK_DIR:-false}"
SKIP_VERIFY="${SKIP_VERIFY:-false}"
JAVA_BIN="${JAVA_BIN:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
LOG_DIR="${LOG_DIR:-/var/tmp/senesi-bench/runs/$(date +%Y%m%d-%H%M%S)}"

if [[ -z "${TARGET:-}" ]]; then
	if [[ "${TARGET_MODE}" == "embedded" ]]; then
		# embedded mode shares one engine, so the target must not collide with the source
		TARGET="${CATALOG}_warmup"
	else
		TARGET="${CATALOG}"
	fi
fi

if [[ ! -f "${JAR}" ]]; then
	echo "ERROR: benchmarks jar not found at ${JAR}" >&2
	echo "Build it first:  cd ${ROOT} && mvn clean install -P full -DskipTests" >&2
	echo "(or point ROOT at the checkout you meant)" >&2
	exit 1
fi
if [[ ! -d "${PRISTINE_DIR}/${CATALOG}" ]]; then
	echo "ERROR: ${PRISTINE_DIR}/${CATALOG} does not exist." >&2
	echo "PRISTINE_DIR must be the PARENT of the catalog folder." >&2
	exit 1
fi
if [[ ! -x "${JAVA_BIN}" ]]; then
	echo "ERROR: JAVA_BIN ${JAVA_BIN} is not executable - override JAVA_BIN." >&2
	exit 1
fi
if [[ "${TARGET_MODE}" != "remote" && "${TARGET_MODE}" != "embedded" ]]; then
	echo "ERROR: TARGET_MODE must be 'remote' or 'embedded', got '${TARGET_MODE}'." >&2
	exit 1
fi

mkdir -p "${LOG_DIR}"

# Byte Buddy generates classes reflectively at runtime and needs these packages opened on JDK 17+.
# The jar's manifest carries them too, but a manifest Add-Opens applies to `java -jar` only - this
# harness is launched with `-cp`, so they MUST be repeated here or booting Evita dies with
# "Cannot define class using reflection".
JVM_ARGS=(
	"-Xmx${XMX}"
	"-Xms${XMS}"
	--add-opens java.base/java.lang=ALL-UNNAMED
	--add-opens java.base/java.lang.invoke=ALL-UNNAMED
	--add-opens java.base/java.math=ALL-UNNAMED
	--add-opens java.base/java.util=ALL-UNNAMED
	"-Xlog:gc*:file=${LOG_DIR}/reader-gc.log:time,uptime,level,tags:filecount=5,filesize=100M"
	-XX:+HeapDumpOnOutOfMemoryError
	"-XX:HeapDumpPath=${LOG_DIR}"
)

APP_PROPS=(
	"-Devita.warmup.pristineDataDir=${PRISTINE_DIR}"
	"-Devita.warmup.catalogName=${CATALOG}"
	"-Devita.warmup.targetMode=${TARGET_MODE}"
	"-Devita.warmup.targetCatalog=${TARGET}"
	"-Devita.warmup.targetHost=${TARGET_HOST}"
	"-Devita.warmup.targetPort=${TARGET_PORT}"
	"-Devita.warmup.workDir=${WORK_DIR}"
	"-Devita.warmup.maxPerCollection=${MAX_PER_COLL}"
	"-Devita.warmup.batchSize=${BATCH_SIZE}"
	"-Devita.warmup.progressInterval=${PROGRESS}"
	"-Devita.warmup.holdOpenSeconds=${HOLD_OPEN}"
	"-Devita.warmup.skipVerification=${SKIP_VERIFY}"
	"-Devita.warmup.keepWorkDir=${KEEP_WORK_DIR}"
)
if [[ -n "${COLLECTIONS}" ]]; then
	APP_PROPS+=("-Devita.warmup.collections=${COLLECTIONS}")
fi
if [[ -n "${PER_ENTITY_CSV}" ]]; then
	APP_PROPS+=("-Devita.warmup.perEntityCsv=${PER_ENTITY_CSV}")
fi

RUN_LOG="${LOG_DIR}/run.log"
{
	echo "=== WARM_UP reindex - READER side (source is embedded here) ==="
	echo "when          : $(date -Is)"
	echo "jar           : ${JAR}"
	echo "jar built     : $(date -Is -r "${JAR}")"
	echo "git           : $(git -C "${ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
	echo "java          : $("${JAVA_BIN}" -version 2>&1 | head -1)"
	echo "reader heap   : -Xmx${XMX} -Xms${XMS}"
	echo "source catalog: ${CATALOG} (from ${PRISTINE_DIR})"
	if [[ "${TARGET_MODE}" == "remote" ]]; then
		echo "target        : ${TARGET} (remote ${TARGET_HOST}:${TARGET_PORT} over gRPC)"
	else
		echo "target        : ${TARGET} (embedded, same JVM - transport-free control)"
	fi
	echo "max per coll. : ${MAX_PER_COLL}"
	echo "collections   : ${COLLECTIONS:-<all>}"
	echo "log dir       : ${LOG_DIR}"
	if [[ "${TARGET_MODE}" == "remote" ]]; then
		echo "REMINDER      : attach the profiler to the SERVER process, not this one."
	fi
	echo "================================================================"
} | tee "${RUN_LOG}"

echo "Running (log: ${RUN_LOG})..."
set +e
"${JAVA_BIN}" "${JVM_ARGS[@]}" "${APP_PROPS[@]}" \
	-cp "${JAR}" io.evitadb.performance.warmupload.IsolatedWarmupLoadBenchmark 2>&1 | tee -a "${RUN_LOG}"
STATUS="${PIPESTATUS[0]}"
set -e

echo "exit status: ${STATUS}" | tee -a "${RUN_LOG}"
echo "artifacts in ${LOG_DIR}:"
ls -la "${LOG_DIR}"
exit "${STATUS}"
