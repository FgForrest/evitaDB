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

echo "Pulling down new evitaDB image"
docker pull index.docker.io/evitadb/evitadb:canary
echo "Stopping running evitaDB server"
docker stop evitadb
docker rm evitadb
echo "Recreating evitaDB container and starting it"
docker run --name evitadb -i --net=host \
      -v "/www/oss/evitaDB-temporary/data:/evita/data" \
      -v "/www/oss/evitaDB-temporary/evita_server/evita-server-certificates:/evita/certificates" \
      -v "/www/oss/evitaDB-temporary/evita_server/logback.xml:/evita/logback.xml" \
      -v "/www/oss/evitaDB-temporary/evita_server/logs:/evita/logs" \
      -e "EVITA_ARGS=server.trafficRecording.enabled=true server.trafficRecording.trafficFlushIntervalInMilliseconds=0 server.trafficRecording.sourceQueryTracking=true server.closeSessionsAfterSecondsOfInactivity=0 storage.compress=true api.exposedOn=localhost api.accessLog=true cache.enabled=false api.certificate.generateAndUseSelfSigned=true api.endpoints.graphQL.tlsMode=RELAXED api.endpoints.rest.tlsMode=RELAXED api.endpoints.lab.tlsMode=RELAXED api.endpoints.gRPC.tlsMode=RELAXED api.endpoints.gRPC.exposeDocsService=true api.endpoints.gRPC.mTLS.enabled=false" \
      index.docker.io/evitadb/evitadb:canary
echo "Done"
