#!/bin/bash
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

## JMX REMOTE
#-Dcom.sun.management.jmxremote \
#-Dcom.sun.management.jmxremote.port=7091 \
#-Dcom.sun.management.jmxremote.authenticate=false \
#-Dcom.sun.management.jmxremote.ssl=false \

java \
        -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8005 \
        --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
        -javaagent:target/evita-server.jar \
        -jar "target/evita-server.jar" \
        "configDir=../conf/" \
        "logback.configurationFile=./logback.xml" \
        "server.trafficRecording.enabled=true " \
        "server.trafficRecording.trafficFlushIntervalInMilliseconds=0 " \
        "server.trafficRecording.sourceQueryTracking=true " \
        "server.closeSessionsAfterSecondsOfInactivity=0 " \
        "storage.storageDirectory=../data " \
        "storage.compress=true " \
        "api.exposedOn=localhost" \
        "api.accessLog=true" \
        "cache.enabled=false" \
        "api.certificate.generateAndUseSelfSigned=true" \
        "api.endpoints.graphQL.tlsMode=RELAXED" \
        "api.endpoints.rest.tlsMode=RELAXED" \
        "api.endpoints.lab.tlsMode=RELAXED" \
        "api.endpoints.gRPC.tlsMode=RELAXED" \
        "api.endpoints.gRPC.exposeDocsService=true" \
        "api.endpoints.gRPC.mTLS.enabled=false"
