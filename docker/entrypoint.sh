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
    # Iterate over files in /entrypoint.d directory (sorted by name)
    # and act according to their extension:
    #   .envsh    - source the script into current shell
    #   .sh       - execute the script in a subshell
    if [ -n "$(find "/entrypoint.d" -mindepth 1 -maxdepth 1 -type f -print -quit 2>/dev/null)" ]; then
        echo "Process scripts in /entrypoint.d"
        for f in $(find "/entrypoint.d" -mindepth 1 -maxdepth 1 -type f | sort); do
            case "$f" in
                *.envsh)
                    if [ -x "$f" ]; then
                        echo "$f";
                        . "$f"
                    else
                        echo "Ignore script, not executable: $f";
                    fi
                    ;;
                *.sh)
                    if [ -x "$f" ]; then
                        echo "Execute script: $f";
                        "$f"
                    else
                        echo "Ignore script, not executable: $f";
                    fi
                    ;;
                *)
                    echo "Ignore file, unknown type: $f";
                    ;;
            esac
        done
    else
        echo "Skip customization, nothing found in /entrypoint.d"
    fi
    echo

    # start evitaDB
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
