#!/bin/sh
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

## /bin/bash not found in docker:git image used for CI

set -ex
cd "$(dirname "$0")"


if [ "$1" = "performance" ]; then
    cp -f ../evita_server/target/evita-server.jar .
    IMAGE="evita-performance:benchmark"
    EVITA_JAR_NAME="evita-server.jar"
elif [ "$1" = "local" ]; then
    cp -f ../evita_server/target/evita-server.jar .
    IMAGE="evitadb:local"
    EVITA_JAR_NAME="evita-server.jar"
else
    if [ "$CI_REGISTRY" = "" ]; then
      echo "Usage: $0 local/performance"
      exit 1
    fi

    IMAGE="$CI_REGISTRY_USER/$RELEASE_IMAGE"
    EVITA_JAR_NAME="$EVITA_JAR_NAME"
fi

echo "Tagging as: $IMAGE"
docker build . \
    --pull \
    --platform linux/amd64 \
    -t "$IMAGE" \
    \
    --build-arg "EVITA_JAR_NAME=$EVITA_JAR_NAME"

if [ "$1" = "local" ] || [ "$1" = "performance" ]; then
    rm evita-server.jar
fi
