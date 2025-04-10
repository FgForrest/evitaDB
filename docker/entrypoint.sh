#!/bin/sh
#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2023-2025
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

if [ "$1" = "" ]; then
    set -x
    exec java \
        -javaagent:${EVITA_BIN_DIR}${EVITA_JAR_NAME} \
        $EVITA_JAVA_OPTS \
        -jar "${EVITA_BIN_DIR}${EVITA_JAR_NAME}" \
        "strictConfigFileCheck=$EVITA_STRICT_CONFIG_FILE_CHECK" \
        "configDir=$EVITA_CONFIG_DIR" \
        "storage.storageDirectory=$EVITA_STORAGE_DIR" \
        "storage.exportDirectory=$EVITA_EXPORT_DIR" \
        "api.certificate.folderPath=$EVITA_CERTIFICATE_DIR" \
        "logback.configurationFile=$EVITA_LOG_FILE" \
        $EVITA_ARGS
else
    exec "$@"
fi
