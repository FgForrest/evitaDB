#!/usr/bin/env bash
#
# Writes the release-written upgrade fixtures with the RELEASED engines: every recipe of
# evita_test/evita_test_support/src/main/java/io/evitadb/test/upgrade is executed inside the
# v2026.1.20 engine (Release2026_1FixtureRecipes) or the v2026.2.18 engine (Release2026_2FixtureRecipes),
# each into a storage directory of its own under <outputDir>. The 2026.2 run then opens the 2026.1
# fixtures that 2026.2 is to upgrade. Nothing here runs in a test build, and nothing is installed
# into a Maven repository.
#
# The recipes compile against the release jars because they use only the API the releases and the
# current engine share; this script compiles them with javac against each release's class path.
#
# Release class paths: by default `mvn dependency:build-classpath` over a throw-away pom depending on
# io.evitadb:evita_db and io.evitadb:evita_export_fs of the release version, which downloads the
# published release artifacts (set MAVEN_REPO_LOCAL to keep them out of ~/.m2). To use jars built
# from a tag instead (`git archive <tag> | tar -x -C <dir>`, then `mvn package -DskipTests` there with
# `-Dmaven.repo.local=<scratch>` — never `install` a release tree into the shared repository), put
# the class path into a file and pass it as RELEASE_2026_1_CLASSPATH / RELEASE_2026_2_CLASSPATH.
#
# Usage:  tools/generate-release-fixtures.sh <outputDir> [fixture...]
#           <outputDir>  must not hold the fixtures to be written yet
#           fixture...   write only these fixtures (default: all)
#
# Then copy the fixture directories into
# evita_test/evita_functional_tests/src/test/resources/testData/release_upgrade_fixtures.
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCES="${PROJECT_ROOT}/evita_test/evita_test_support/src/main/java/io/evitadb/test/upgrade"
RELEASE_2026_1="2026.1.20"
RELEASE_2026_2="2026.2.18"

if [[ $# -lt 1 ]]; then
	echo "usage: $(basename "$0") <outputDir> [fixture...]" >&2
	exit 2
fi
OUTPUT_DIR="$(mkdir -p "$1" && cd "$1" && pwd)"
shift
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

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
	local repo_arg=()
	if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
		repo_arg=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL}")
	fi
	mvn -q -f "${pom_dir}/pom.xml" "${repo_arg[@]}" dependency:build-classpath -Dmdep.outputFile="$target"
}

CP_2026_1="${SCRATCH}/cp-2026-1.txt"
CP_2026_2="${SCRATCH}/cp-2026-2.txt"
resolve_classpath "$RELEASE_2026_1" "${RELEASE_2026_1_CLASSPATH:-}" "$CP_2026_1"
resolve_classpath "$RELEASE_2026_2" "${RELEASE_2026_2_CLASSPATH:-}" "$CP_2026_2"

# the 2026.1 engine compiles only the sources that name nothing newer than 2026.1
mkdir -p "${SCRATCH}/classes-2026-1" "${SCRATCH}/classes-2026-2"
javac -nowarn -proc:none -cp "$(cat "$CP_2026_1")" -d "${SCRATCH}/classes-2026-1" \
	"${SOURCES}/ReleaseFixtureOrigin.java" \
	"${SOURCES}/ReleaseFixtureProbe.java" \
	"${SOURCES}/ReleaseFixtureRecipe.java" \
	"${SOURCES}/ReleaseFixtureValues.java" \
	"${SOURCES}/ReleaseFixtureWriter.java" \
	"${SOURCES}/Release2026_1FixtureRecipes.java" \
	"${SOURCES}/Release2026_1FixtureGenerator.java"
javac -nowarn -proc:none -cp "$(cat "$CP_2026_2")" -d "${SCRATCH}/classes-2026-2" \
	"${SOURCES}"/*.java

echo "Writing the 2026.1 fixtures with v${RELEASE_2026_1} ..."
java -cp "${SCRATCH}/classes-2026-1:$(cat "$CP_2026_1")" \
	io.evitadb.test.upgrade.Release2026_1FixtureGenerator write "$OUTPUT_DIR" "${SCRATCH}/work-2026-1" "$@"
echo "Writing the 2026.2 fixtures and opening the 2026.1 ones with v${RELEASE_2026_2} ..."
java -cp "${SCRATCH}/classes-2026-2:$(cat "$CP_2026_2")" \
	io.evitadb.test.upgrade.Release2026_2FixtureGenerator write "$OUTPUT_DIR" "${SCRATCH}/work-2026-2" "$@"

echo "Fixture sizes:"
du -sb "$OUTPUT_DIR"/* | sort -n
OVERSIZED="$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -type d -exec du -sb {} + | awk '$1 > 2097152 {print $2}')"
if [[ -n "$OVERSIZED" ]]; then
	echo "WARNING: fixtures larger than 2 MB, keep them minimal:" >&2
	echo "$OVERSIZED" >&2
fi
