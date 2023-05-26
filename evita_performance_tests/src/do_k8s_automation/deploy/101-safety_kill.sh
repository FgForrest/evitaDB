#!/bin/bash
set -e
cd "$(dirname "$0")"

BANNER_NO_X=1
. 00-env.sh

export LIMIT_HOURS="${LIMIT_HOURS:-24}"
export DO_CLUSTER_NAME="${DO_CLUSTER_NAME:-evita-bench01}"
export DO_REGISTRY_NAME="${DO_REGISTRY_NAME:-evita-reg01}"
# export | grep -e ' DO_' -e ' LIMIT_' || :

SOMETHING_KILLED="0"

function kill_old_cluster {
    local CN="$1"

    separator "Get cluster age"
    rm -f out.tmp
    if cmd doctl k8s cluster get "$CN" -o json | jq -r '.[0].created_at' >out.tmp; then
        echo "Cluster running since: $(cat out.tmp)"
        T0="$(date -d "$(cat out.tmp)" +"%s")"
        TN="$(date +"%s")"
        echo "T0=$T0"
        echo "TN=$TN"
        AGE=$(( (TN - T0) / 3600 ))
        echo "Age: $AGE hrs, Limit: $LIMIT_HOURS hrs"
        if [ "$AGE" -ge "$LIMIT_HOURS" ]; then
            separator "Kill cluster"
            cmd doctl k8s cluster delete "$CN" --force --dangerous || :
            SOMETHING_KILLED="1"
        else
            separator "Keep cluster running"
        fi
    else
        separator "Cluster not running"
    fi
    rm -f out.tmp
}

## get age of all running clusters (now - create) in seconds; no cluster - empty string
## destroy running clusters older than limit
banner "Cluster"
kill_old_cluster "${DO_CLUSTER_NAME}-memory"


if [ "$SOMETHING_KILLED" = "1" ]; then
    banner "Something was killed !!!!!!!!!!!!!!!!!!!!"
    exit 1
fi

exit 0
