#!/bin/bash
#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2023-2024
#
#   Licensed under the Business Source License, Version 1.1 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
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

function _trap_exit() {
    set +x

    echo
    if [ -n "$GH_ACTION" ] && [ "$GH_ACTION" == "1" ]; then
        date -Is
        echo "Run in GH Action - clean Kubernetes cluster"
        curl -s -L \
            -X POST \
            -H "Accept: application/vnd.github+json" \
            -H "Authorization: Bearer $GITHUB_TOKEN"\
            https://api.github.com/repos/FgForrest/evitaDB/dispatches \
            -d '{"event_type": "clean-webhook"}'

    else
        echo "Run on local"
    fi
}

## interactive shell for debug'n'tuning
[ "$1" != "bash" ] || exec bash "$@"

## args
EXTRA_JAVA_OPTS="${1:-}"
SHARED_GIST='abc12461f21d1cc66a541417edcb6ba7'
RESULT_JSON=latest-performance-results.json
# DO_CLUSTER_NODE_SLUG="mock"

PROCESSOR=$(lscpu | awk -F': +' '/Model name/ {model=$2} /Core\(s\) per socket/ {core=$2} /Thread\(s\) per core/ {thread=$2} /^CPU\(s\)/ {cpu=$2} /Architecture/ {arch=$2} END {printf "Processor: %s (%s * %s = %s CPU), architecture: %s\n", model, core, thread, cpu, arch}')

[ -n "$JMH_ARGS" ] || JMH_ARGS="-i 2 -wi 1 -f 1"

[ -n "$BENCHMARK_SELECTOR" ] || BENCHMARK_SELECTOR="${2:-.*}"

now="$(date -Is)"
new_filename="$(echo "$now" | sed 's/[-:]/_/g' | sed 's/\+/_/g' | sed 's/T/-/')"
echo "now: $now"
echo "new_filename: $new_filename"
echo "EXTRA_JAVA_OPTS: $EXTRA_JAVA_OPTS"
echo "BENCHMARK_JAVA_OPTS: $BENCHMARK_JAVA_OPTS"
echo "BENCHMARK_SELECTOR: $BENCHMARK_SELECTOR"
echo "JMH_ARGS: $JMH_ARGS"
echo "DO_REGISTRY_SLUG: $DO_REGISTRY_SLUG"
echo "DO_CLUSTER_NODE_SLUG: $DO_CLUSTER_NODE_SLUG"
echo "BENCHMARK_K8S_REQUEST_CPU: $BENCHMARK_K8S_REQUEST_CPU"
echo "BENCHMARK_K8S_LIMIT_CPU: $BENCHMARK_K8S_LIMIT_CPU"
echo "BENCHMARK_K8S_LIMIT_MEMORY: $BENCHMARK_K8S_LIMIT_MEMORY"

echo
lscpu
echo

trap _trap_exit EXIT

echo "Download datasets for performance..."

[ -n "$DATASET" ] || DATASET="https://evitadb.io/download/performance_test_datasets.zip"

wget -q "$DATASET" -O performance_test_datasets.zip
echo "Unzip datasets for performance..."
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

set +x

## public gist
cp $RESULT_JSON "$new_filename".json
gh gist create -d "Evita performance results: $BENCHMARK_SELECTOR - $now (node: $DO_CLUSTER_NODE_SLUG, $PROCESSOR)" --public "$new_filename".json

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

CHILL_OUT_SEC=90
echo
echo "Let the cluster chill-out after benchmark: $CHILL_OUT_SEC sec"
echo "  - $(date -Isec)"
echo
sleep $CHILL_OUT_SEC

echo Finished
