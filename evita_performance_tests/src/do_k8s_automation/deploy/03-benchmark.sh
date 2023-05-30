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
cd "$(dirname "$0")"
. 00-env.sh
# set -x

EXTRA_JAVA_OPTS=""

function kill_jobs {
    banner "KILL PREVIOUS JOBS: $IMAGES"
    local FLAG_KILL="0"
    for IMG in $IMAGES; do
        separator "STOP PREVIOUS: $IMG"
        if kubectl get "job/${K8S_JOB_NAME}"; then
            FLAG_KILL="1"
            kubectl delete "job/${K8S_JOB_NAME}"
        fi
    done
    [ "$FLAG_KILL" = "1" ] && sleep 120 || :
}

function run_jobs {
    local rc=0

    for IMG in $IMAGES; do

        ## customize job
        ## k8s limits - https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#:~:text=Meaning%20of%20memory
        cat k8s-job.yml \
            | sed -e "s/__BENCHMARK_REQ_MEM__/${BENCHMARK_K8S_LIMIT_MEMORY:-0}/g" \
            | sed -e "s/__BENCHMARK_LIM_MEM__/${BENCHMARK_K8S_LIMIT_MEMORY:-0}/g" \
            | sed -e "s/__BENCHMARK_REQ_CPU__/${BENCHMARK_K8S_REQUEST_CPU:-0}/g" \
            | sed -e "s/__BENCHMARK_LIM_CPU__/${BENCHMARK_K8S_LIMIT_CPU:-0}/g" \
            | sed -e "s/__ARG_EXTRA_JAVA_OPTS__/${EXTRA_JAVA_OPTS}/g" \
            > k8s.yml

        ## run job
        banner "START: $IMG"
        kubectl apply -f k8s.yml
        ## get container limits
        kubectl get jobs --selector=job-name=${K8S_JOB_NAME} -o jsonpath-as-json="{.items[*].spec.template.spec.containers[*]['name','resources','args']}" || :

        ## wait
        banner "WAIT: $IMG"
        TL=0
        while [ "1" = "1" ]; do
            kubectl wait --timeout=10s --for=condition=complete "job/${K8S_JOB_NAME}" >/dev/null 2>&1 && { rc=0; break; } || :
            kubectl wait --timeout=0s  --for=condition=failed   "job/${K8S_JOB_NAME}" >/dev/null 2>&1 && { rc=1; break; } || :

            set +x ## do not log the progress loop
            TN="$(date '+%s')"
            if [[ "$(( TN - TL ))" -ge 300 ]]; then
                TL="$TN"
                separator "progress"
                kubectl get "job/${K8S_JOB_NAME}" || { rc=1; break; }
                for POD in $(kubectl get pods --selector=job-name=${K8S_JOB_NAME} --output=jsonpath='{.items[*].metadata.name}'); do
                    kubectl get "pod/$POD" || { rc=1; break; }
                    kubectl logs "$POD" --tail=10 || { rc=1; break; }
                done
            fi
        done
        banner "FINISHED: RC=$rc - $IMG" ## with set -x
        kubectl get events --sort-by=.metadata.creationTimestamp || :

        ## get logs
        banner "LOGS: $IMG"
        kubectl get "job/${K8S_JOB_NAME}" || :
        for POD in $(kubectl get pods --selector=job-name=${K8S_JOB_NAME} --output=jsonpath='{.items[*].metadata.name}'); do
            kubectl get "pod/$POD" || :
            kubectl logs "$POD" || :
        done

        ## fail early
        if [ "$rc" = "0" ]; then
            echo "Job finished"
        else
            echo "Job failed"
            break
        fi
    done

    return "$rc"
}


# banner "ENV"
# export | grep -v ' CI_'

kubectl config set-context --current --namespace="evita"

banner "CONFIG'N'SECRET"
## config values (public)
kubectl delete configmap benchmark || :
kubectl create configmap benchmark \
    "--from-literal=DO_CLUSTER_NODE_SLUG=$DO_CLUSTER_NODE_SLUG" \
    \
    "--from-literal=BENCHMARK_JAVA_OPTS=$BENCHMARK_JAVA_OPTS" \
    "--from-literal=BENCHMARK_K8S_REQUEST_CPU=$BENCHMARK_K8S_REQUEST_CPU" \
    "--from-literal=BENCHMARK_K8S_LIMIT_CPU=$BENCHMARK_K8S_LIMIT_CPU" \
    "--from-literal=BENCHMARK_K8S_LIMIT_MEMORY=$BENCHMARK_K8S_LIMIT_MEMORY" \
    \
    "--from-literal=JMH_ARGS=$JMH_ARGS" \
    "--from-literal=BENCHMARK_SELECTOR=$BENCHMARK_SELECTOR" \
    ## end

## secret values
kubectl delete secret benchmark || :
kubectl create secret generic benchmark \
    "--from-literal=GITHUB_TOKEN=$PERFORMANCE_GIST_TOKEN" \
    ## end


kill_jobs

banner "RUN JOBS: $IMAGES"
run_jobs; rc="$?"

banner "CLEANUP"
kubectl delete secret/benchmark

exit "$rc"
