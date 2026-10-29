#!/usr/bin/env bash
#
# Writes the release upgrade fixtures with the RELEASED engines into <testDataDir>:
#
# - <testDataDir>/release_upgrade_fixtures/<fixture> - one storage directory per recipe of
#   evita_test/evita_test_support/src/main/java/io/evitadb/test/upgrade, written by the v2026.1.20 engine
#   (Release2026_1FixtureRecipes) or the v2026.2.18 engine (Release2026_2FixtureRecipes and its siblings). The 2026.2
#   run then opens the 2026.1 fixtures that 2026.2 is to upgrade;
# - <testDataDir>/released_sort_2026_1/*.bin - single sort storage parts written by the v2026.1.20 engine
#   (tools/fixtures/GenerateRelease2026_1SortParts.java).
#
# The functional tests' build runs this script in `generate-test-resources`, with its test output directory's
# `testData` as <testDataDir> (see evita_test/evita_functional_tests/pom.xml); the bytes are never committed. A run is
# skipped while its inputs - this script, the recipes and the sort-part generator - are unchanged and the output in
# <testDataDir> is still the one written from them, file by file. The output is assembled in a scratch directory and
# replaces the previous one only once complete.
#
# The recipes compile against the release jars because they use only the API the releases and the current engine
# share; this script compiles them with javac against each release's class path. javac and java are taken from
# JAVA_HOME when it is set, otherwise from the PATH.
#
# Release class paths: by default `mvn dependency:build-classpath` over a throw-away pom depending on
# io.evitadb:evita_db and io.evitadb:evita_export_fs of the release version, which downloads the published release
# artifacts on first use. The options hand that nested Maven the calling build's local repository, offline mode and
# settings files; the build passes its own. An online run resolves offline first, so the network is needed only until
# the local repository holds the release. To use jars built from a tag instead (`git archive <tag> | tar -x -C <dir>`,
# then `mvn package -DskipTests` there with `-Dmaven.repo.local=<scratch>` - never `install` a release tree into the
# shared repository), put the class path into a file and pass it as RELEASE_2026_1_CLASSPATH /
# RELEASE_2026_2_CLASSPATH; such a run always writes the fixtures and never marks them reusable, because the jars a
# supplied class path names may change under the same name.
#
# Usage:  tools/generate-release-fixtures.sh [--maven-repo-local=<dir>] [--maven-offline=true|false]
#                                            [--maven-settings=<file>] [--maven-global-settings=<file>] <testDataDir>
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${PROJECT_ROOT}/tools/generate-release-fixtures.sh"
SOURCES="${PROJECT_ROOT}/evita_test/evita_test_support/src/main/java/io/evitadb/test/upgrade"
SORT_PARTS="${PROJECT_ROOT}/tools/fixtures/GenerateRelease2026_1SortParts.java"
RELEASE_2026_1="2026.1.20"
RELEASE_2026_2="2026.2.18"

MAVEN_REPO_LOCAL=""
MAVEN_OFFLINE="false"
MAVEN_SETTINGS=""
MAVEN_GLOBAL_SETTINGS=""
while [[ $# -gt 0 && "$1" == --* ]]; do
	case "$1" in
		--maven-repo-local=*) MAVEN_REPO_LOCAL="${1#*=}" ;;
		--maven-offline=*) MAVEN_OFFLINE="${1#*=}" ;;
		--maven-settings=*) MAVEN_SETTINGS="${1#*=}" ;;
		--maven-global-settings=*) MAVEN_GLOBAL_SETTINGS="${1#*=}" ;;
		*)
			echo "unknown option: $1" >&2
			exit 2
			;;
	esac
	shift
done
if [[ $# -ne 1 ]]; then
	echo "usage: $(basename "$0") [--maven-repo-local=<dir>] [--maven-offline=true|false]" \
		"[--maven-settings=<file>] [--maven-global-settings=<file>] <testDataDir>" >&2
	exit 2
fi
TEST_DATA_DIR="$(mkdir -p "$1" && cd "$1" && pwd)"
STAMP="${TEST_DATA_DIR}/.release-fixtures.sha256"
SUPPLIED_CLASSPATH="${RELEASE_2026_1_CLASSPATH:-}${RELEASE_2026_2_CLASSPATH:-}"

JAVA_BIN=""
if [[ -n "${JAVA_HOME:-}" ]]; then
	JAVA_BIN="${JAVA_HOME}/bin/"
fi

# Prints the digest of everything the output is written from.
hash_inputs() {
	local digest=(sha256sum)
	if ! command -v sha256sum > /dev/null; then
		digest=(shasum -a 256)
	fi
	cat "$SCRIPT" "$SORT_PARTS" "${SOURCES}"/*.java | "${digest[@]}" | cut -d ' ' -f 1
}

# Prints the stamp of the current output: the input digest, then every output file with its size, so that output
# removed or truncated since it was written is written again.
current_stamp() {
	echo "$INPUTS"
	(
		cd "$TEST_DATA_DIR"
		find release_upgrade_fixtures released_sort_2026_1 -type f 2> /dev/null | LC_ALL=C sort | while read -r file; do
			echo "$(wc -c < "$file" | tr -d ' ') ${file}"
		done
	) || true
}

INPUTS="$(hash_inputs)"
if [[ -z "$SUPPLIED_CLASSPATH" && -f "$STAMP" && "$(cat "$STAMP")" == "$(current_stamp)" ]]; then
	echo "The release upgrade fixtures in ${TEST_DATA_DIR} are up to date."
	exit 0
fi

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
FIXTURES="${SCRATCH}/testData/release_upgrade_fixtures"
SORT_OUTPUT="${SCRATCH}/testData/released_sort_2026_1"
mkdir -p "$FIXTURES" "$SORT_OUTPUT"

# Writes the class path of the given release into the given file, unless the caller supplied one.
resolve_classpath() {
	local version="$1" supplied="$2" target="$3"
	if [[ -n "$supplied" ]]; then
		cp "$supplied" "$target"
		return
	fi
	local pom_dir="${SCRATCH}/pom-${version}"
	mkdir -p "$pom_dir"
	cat > "${pom_dir}/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	<groupId>local.fixture</groupId>
	<artifactId>release-classpath</artifactId>
	<version>1</version>
	<packaging>pom</packaging>
	<dependencies>
		<dependency>
			<groupId>io.evitadb</groupId>
			<artifactId>evita_db</artifactId>
			<version>${version}</version>
			<type>pom</type>
		</dependency>
		<dependency>
			<groupId>io.evitadb</groupId>
			<artifactId>evita_export_fs</artifactId>
			<version>${version}</version>
		</dependency>
	</dependencies>
</project>
EOF
	local maven_args=(-q -B -f "${pom_dir}/pom.xml")
	if [[ -n "$MAVEN_REPO_LOCAL" ]]; then
		maven_args+=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL}")
	fi
	# a calling build names its settings files even when they do not exist, and only existing ones are passed on
	if [[ -f "$MAVEN_SETTINGS" ]]; then
		maven_args+=(-s "$MAVEN_SETTINGS")
	fi
	if [[ -f "$MAVEN_GLOBAL_SETTINGS" ]]; then
		maven_args+=(-gs "$MAVEN_GLOBAL_SETTINGS")
	fi
	maven_args+=(org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath -Dmdep.outputFile="$target")
	if [[ "$MAVEN_OFFLINE" == "true" ]]; then
		mvn -o "${maven_args[@]}"
	else
		# offline first: the release artifacts never change once downloaded, so only the first run needs the network
		mvn -o "${maven_args[@]}" > /dev/null 2>&1 || mvn "${maven_args[@]}"
	fi
}

CP_2026_1="${SCRATCH}/cp-2026-1.txt"
CP_2026_2="${SCRATCH}/cp-2026-2.txt"
resolve_classpath "$RELEASE_2026_1" "${RELEASE_2026_1_CLASSPATH:-}" "$CP_2026_1"
resolve_classpath "$RELEASE_2026_2" "${RELEASE_2026_2_CLASSPATH:-}" "$CP_2026_2"

# the 2026.1 engine compiles only the sources that name nothing newer than 2026.1
mkdir -p "${SCRATCH}/classes-2026-1" "${SCRATCH}/classes-2026-2" "${SCRATCH}/classes-sort-2026-1"
"${JAVA_BIN}javac" -nowarn -proc:none -cp "$(cat "$CP_2026_1")" -d "${SCRATCH}/classes-2026-1" \
	"${SOURCES}/ReleaseFixtureOrigin.java" \
	"${SOURCES}/ReleaseFixtureProbe.java" \
	"${SOURCES}/ReleaseFixtureRecipe.java" \
	"${SOURCES}/ReleaseFixtureValues.java" \
	"${SOURCES}/ReleaseFixtureWriter.java" \
	"${SOURCES}/Release2026_1FixtureRecipes.java" \
	"${SOURCES}/Release2026_1FixtureGenerator.java"
"${JAVA_BIN}javac" -nowarn -proc:none -cp "$(cat "$CP_2026_2")" -d "${SCRATCH}/classes-2026-2" \
	"${SOURCES}"/*.java
"${JAVA_BIN}javac" -nowarn -proc:none -cp "$(cat "$CP_2026_1")" -d "${SCRATCH}/classes-sort-2026-1" "$SORT_PARTS"

echo "Writing the 2026.1 fixtures with v${RELEASE_2026_1} ..."
"${JAVA_BIN}java" -cp "${SCRATCH}/classes-2026-1:$(cat "$CP_2026_1")" \
	io.evitadb.test.upgrade.Release2026_1FixtureGenerator write "$FIXTURES" "${SCRATCH}/work-2026-1"
echo "Writing the 2026.2 fixtures and opening the 2026.1 ones with v${RELEASE_2026_2} ..."
"${JAVA_BIN}java" -cp "${SCRATCH}/classes-2026-2:$(cat "$CP_2026_2")" \
	io.evitadb.test.upgrade.Release2026_2FixtureGenerator write "$FIXTURES" "${SCRATCH}/work-2026-2"
echo "Writing the 2026.1 sort parts with v${RELEASE_2026_1} ..."
"${JAVA_BIN}java" --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
	-cp "${SCRATCH}/classes-sort-2026-1:$(cat "$CP_2026_1")" GenerateRelease2026_1SortParts "$SORT_OUTPUT"

# the stamp goes first and returns last, so an interrupted replacement is written again by the next run
rm -rf "$STAMP" "${TEST_DATA_DIR}/release_upgrade_fixtures" "${TEST_DATA_DIR}/released_sort_2026_1"
mv "$FIXTURES" "$SORT_OUTPUT" "${TEST_DATA_DIR}/"
if [[ -z "$SUPPLIED_CLASSPATH" ]]; then
	current_stamp > "$STAMP"
fi
echo "Wrote $(find "${TEST_DATA_DIR}/release_upgrade_fixtures" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')" \
	"release upgrade fixtures and $(find "${TEST_DATA_DIR}/released_sort_2026_1" -name '*.bin' | wc -l | tr -d ' ')" \
	"sort parts into ${TEST_DATA_DIR}."
