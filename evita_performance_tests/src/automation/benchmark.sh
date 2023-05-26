#!/bin/bash
#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2023
#
#   Licensed under the Business Source License, Version 1.1 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

set -e

function help {
    local rc="$1"
    echo "Usage: $0 \"<extra_java_opts>\" \"<benchmark_selector>\""
    echo ""
    echo "    bash - for interactive debugging"
    echo ""
    [ "$rc" = "" ] || exit "$rc"
}

## interactive shell for debug'n'tuning
[ "$1" != "bash" ] || exec bash "$@"

## args
EXTRA_JAVA_OPTS=""
BENCHMARK_SELECTOR="${2:-io.evitadb.performance.artificial.ArtificialEntitiesThroughputBenchmark.singleEntityRead}"
SHARED_GIST='abc12461f21d1cc66a541417edcb6ba7'
RESULT_JSON=latest-performance-results.json
# DO_CLUSTER_NODE_SLUG="mock"

## https://gitlab.fg.cz/hv/evita/-/issues/32#note_233553
[ -n "$JMH_ARGS" ] || JMH_ARGS="-i 2 -wi 1 -f 1"

now="$(date -Is)"
echo "now: $now"
# echo "DO_REGISTRY_SLUG: $DO_REGISTRY_SLUG"
# echo "DO_CLUSTER_NODE_SLUG: $DO_CLUSTER_NODE_SLUG"
# echo "BENCHMARK_K8S_REQUEST_CPU: $BENCHMARK_K8S_REQUEST_CPU"
# echo "BENCHMARK_K8S_LIMIT_CPU: $BENCHMARK_K8S_LIMIT_CPU"
# echo "BENCHMARK_K8S_LIMIT_MEMORY: $BENCHMARK_K8S_LIMIT_MEMORY"

echo
lscpu
echo

wget https://evitadb.io/download/performance_test_datasets.zip
unzip -d /evita-data performance_test_datasets.zip
rm performance_test_datasets.zip

[ -n "$CHILL_OUT_SEC" ] || CHILL_OUT_SEC=5
echo "Let the cluster chill-out before benchmark: $CHILL_OUT_SEC sec"
echo "  - $(date -Isec)"
echo
sleep $CHILL_OUT_SEC
set -x

ls -la

## run benchmark
java \
    -XshowSettings \
    -jar benchmarks.jar "$BENCHMARK_SELECTOR" \
        $JMH_ARGS -rf json -rff $RESULT_JSON \
        -jvmArgs "$EXTRA_JAVA_OPTS $BENCHMARK_JAVA_OPTS -DdataFolder=/data -DevitaData=/evita-data/data"

## public gist
gh gist create -d "Evita performance results: $BENCHMARK_SELECTOR - $now (node: $DO_CLUSTER_NODE_SLUG)" --public $RESULT_JSON

## shared gist
rm -rf "$SHARED_GIST"
gh gist clone "$SHARED_GIST" "$SHARED_GIST"
cd "$SHARED_GIST"
cp -f ../$RESULT_JSON $RESULT_JSON

## push back
git config user.email "novotny@fg.cz"
git config user.name "Novoj"
git commit -a -m "Updated results from $now"
git push "https://$GITHUB_TOKEN:x-oauth-basic@gist.github.com/evita-db/$SHARED_GIST"

set +x
CHILL_OUT_SEC=90
echo
echo "Let the cluster chill-out after benchmark: $CHILL_OUT_SEC sec"
echo "  - $(date -Isec)"
echo
sleep $CHILL_OUT_SEC

echo Finished
