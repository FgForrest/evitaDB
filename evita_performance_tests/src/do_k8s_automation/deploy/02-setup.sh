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
cd "$(dirname "$0")"
. 00-env.sh
# set -x

function deploy_monitoring {
    for f in $(ls -1 monitoring/*.yml | sort); do
        cat "$f" \
            | sed -e "s#__CLUSTER__NAME__#do-${DO_CLUSTER_REGION}-${DO_CLUSTER_BENCHMARK_NAME}#g" \
            | sed -e "s#__GRAFANA_URL__#${GRAFANA_URL}#g" \
            | sed -e "s#__GRAFANA_USER__#${GRAFANA_USER}#g" \
            | sed -e "s#__GRAFANA_PASSWD__#${GRAFANA_PASSWD}#g" \
            > k8s.yml
        kubectl apply -f k8s.yml
    done
}

banner "NAMESPACE"
kubectl create namespace "evita" || :

banner "MONITORING"
deploy_monitoring
