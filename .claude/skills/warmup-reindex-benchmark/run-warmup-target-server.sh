#!/usr/bin/env bash
#
# Starts the evitaDB server that acts as the WRITE side of the isolated WARM_UP reindex benchmark.
#
# This is the process to attach a profiler to. It holds ONLY the catalog being built - the source
# catalog lives in the loader process (embedded), so nothing a profiler samples here belongs to the
# read path. Start this first, then run run-warmup-load.sh in another shell - or use
# run-warmup-reindex.sh, which drives both and tears down afterwards.
#
# Derived from evita_server/run-server.sh, with three deliberate differences, each of which would
# otherwise corrupt the measurement:
#
#   1. server.trafficRecording.enabled=false  - run-server.sh enables it with a 0 ms flush interval,
#      which records and flushes every single mutation. That is write-path overhead landing straight
#      in the profile you are trying to read.
#   2. an explicit -Xmx                       - run-server.sh sets none, so the JVM would take 25% of
#      the cgroup limit. Heap size also feeds the collation-key cache's heap-derived default sizing,
#      so an unpinned heap silently changes the code path between runs.
#   3. a dedicated, empty storage directory   - so the target catalog is built from nothing and the
#      source snapshot is nowhere near this process.
#
# JDWP is off by default (run-server.sh has it on); a debug transport is not something to leave in a
# measurement run. Set JDWP=true if you need it.
#
#   ROOT             evitaDB checkout to take the jar from   (default /www/oss/evita/release_2026-2)
#   TARGET_DATA_DIR  storage dir for the catalog being built (default /var/tmp/senesi-bench/target-data)
#   XMX / XMS        writer heap                             (default 23g / 23g)
#   GRPC_PORT        gRPC port to listen on                  (default 5555)
#   COMPRESS         storage.compress                        (default true, matches run-server.sh)
#   PROFILE          none | jfr | alloc | cpu                (default none)
#   AP_LIB           path to libasyncProfiler.so (alloc/cpu)
#   JDWP             expose a debug transport on 8005        (default false)
#   RESET_DATA       wipe TARGET_DATA_DIR before starting    (default false)
#   LOG_DIR          where the server + GC log and profile go
#   JAVA_BIN         JVM to use                              (default JDK 17)

set -euo pipefail

ROOT="${ROOT:-/www/oss/evita/release_2026-2}"
SERVER_JAR="${ROOT}/evita_server/target/evita-server.jar"

TARGET_DATA_DIR="${TARGET_DATA_DIR:-/var/tmp/senesi-bench/target-data}"
XMX="${XMX:-23g}"
XMS="${XMS:-23g}"
GRPC_PORT="${GRPC_PORT:-5555}"
COMPRESS="${COMPRESS:-true}"
PROFILE="${PROFILE:-none}"
AP_LIB="${AP_LIB:-}"
JDWP="${JDWP:-false}"
RESET_DATA="${RESET_DATA:-false}"
JAVA_BIN="${JAVA_BIN:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
LOG_DIR="${LOG_DIR:-/var/tmp/senesi-bench/server/$(date +%Y%m%d-%H%M%S)}"

if [[ ! -f "${SERVER_JAR}" ]]; then
	echo "ERROR: server jar not found at ${SERVER_JAR}" >&2
	echo "Build it first:  cd ${ROOT} && mvn clean install -P full -DskipTests" >&2
	echo "(or point ROOT at the checkout you meant)" >&2
	exit 1
fi
if [[ ! -x "${JAVA_BIN}" ]]; then
	echo "ERROR: JAVA_BIN ${JAVA_BIN} is not executable - override JAVA_BIN." >&2
	exit 1
fi

# Guard the wipe: only ever touch a path that is clearly a dedicated scratch directory, never a data
# directory someone might actually care about.
if [[ "${RESET_DATA}" == "true" ]]; then
	case "${TARGET_DATA_DIR}" in
		/var/tmp/*|/tmp/*)
			echo "RESET_DATA=true - wiping ${TARGET_DATA_DIR}"
			rm -rf "${TARGET_DATA_DIR:?}"
			;;
		*)
			echo "ERROR: refusing to wipe '${TARGET_DATA_DIR}' - RESET_DATA only wipes paths under /var/tmp or /tmp." >&2
			echo "Delete it by hand if that is really what you want." >&2
			exit 1
			;;
	esac
fi

mkdir -p "${TARGET_DATA_DIR}" "${LOG_DIR}"

if [[ -n "$(ls -A "${TARGET_DATA_DIR}" 2>/dev/null)" ]]; then
	echo "NOTE: ${TARGET_DATA_DIR} is not empty - leftovers from an earlier run."
	echo "      The benchmark drops and recreates its target catalog, so this is usually harmless."
	echo "      Pass RESET_DATA=true for a guaranteed-clean start."
fi

JVM_ARGS=(
	"-Xmx${XMX}"
	"-Xms${XMS}"
	# improves profiler stack accuracy; negligible runtime cost, and standard for profiling runs
	-XX:+UnlockDiagnosticVMOptions
	-XX:+DebugNonSafepoints
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
	--add-opens java.base/java.lang=ALL-UNNAMED
	--add-opens java.base/java.lang.invoke=ALL-UNNAMED
	--add-opens java.base/java.math=ALL-UNNAMED
	--add-opens java.base/java.util=ALL-UNNAMED
	"-Xlog:gc*:file=${LOG_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=100M"
	-XX:+HeapDumpOnOutOfMemoryError
	"-XX:HeapDumpPath=${LOG_DIR}"
	"-javaagent:${SERVER_JAR}"
)

if [[ "${JDWP}" == "true" ]]; then
	JVM_ARGS+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8005")
fi

case "${PROFILE}" in
	none) ;;
	jfr)
		JVM_ARGS+=("-XX:StartFlightRecording=settings=profile,filename=${LOG_DIR}/writer.jfr,dumponexit=true")
		;;
	alloc|cpu)
		if [[ -z "${AP_LIB}" || ! -f "${AP_LIB}" ]]; then
			echo "ERROR: PROFILE=${PROFILE} needs AP_LIB pointing at libasyncProfiler.so." >&2
			exit 1
		fi
		JVM_ARGS+=("-agentpath:${AP_LIB}=start,event=${PROFILE},collapsed,file=${LOG_DIR}/writer-${PROFILE}.collapsed")
		;;
	*)
		echo "ERROR: unknown PROFILE '${PROFILE}' (expected none|jfr|alloc|cpu)." >&2
		exit 1
		;;
esac

# `closeSessionsAfterSecondsOfInactivity=0` disables the session reaper: the benchmark holds ONE
# WARM_UP write session open for the entire load, which can run for tens of minutes.
#
# `export.fileSystem.directory` must also be pinned: it defaults to `./export` relative to the working
# directory and evitaDB takes an exclusive folder lock on it, so two engines started from the same
# project directory fight over it and the second dies with FolderAlreadyUsedException.
APP_ARGS=(
	"storage.storageDirectory=${TARGET_DATA_DIR}"
	"export.fileSystem.directory=${TARGET_DATA_DIR}/export"
	"storage.compress=${COMPRESS}"
	"server.trafficRecording.enabled=false"
	"server.closeSessionsAfterSecondsOfInactivity=0"
	"cache.enabled=false"
	"api.exposedOn=localhost"
	"api.certificate.generateAndUseSelfSigned=true"
	"api.endpoints.gRPC.host=:${GRPC_PORT}"
	"api.endpoints.gRPC.tlsMode=RELAXED"
	"api.endpoints.gRPC.mTLS.enabled=false"
	"api.endpoints.system.tlsMode=RELAXED"
)

{
	echo "=== WARM_UP reindex TARGET server (write side - attach the profiler HERE) ==="
	echo "when          : $(date -Is)"
	echo "server jar    : ${SERVER_JAR}"
	echo "jar built     : $(date -Is -r "${SERVER_JAR}")"
	echo "git           : $(git -C "${ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
	echo "java          : $("${JAVA_BIN}" -version 2>&1 | head -1)"
	echo "writer heap   : -Xmx${XMX} -Xms${XMS}"
	echo "data dir      : ${TARGET_DATA_DIR}"
	echo "gRPC port     : ${GRPC_PORT}"
	echo "compress      : ${COMPRESS}"
	echo "traffic rec.  : DISABLED (measurement run)"
	echo "profile       : ${PROFILE}"
	echo "jdwp          : ${JDWP}"
	echo "cgroup max    : $(awk '{printf "%.1f GiB", $1/1073741824}' /sys/fs/cgroup/memory.max 2>/dev/null || echo n/a)"
	echo "log dir       : ${LOG_DIR}"
	echo "=============================================================================="
} | tee "${LOG_DIR}/server.log"

exec "${JAVA_BIN}" "${JVM_ARGS[@]}" -jar "${SERVER_JAR}" "${APP_ARGS[@]}" 2>&1 | tee -a "${LOG_DIR}/server.log"
