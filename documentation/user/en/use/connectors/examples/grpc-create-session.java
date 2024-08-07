/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// create evita service uring Armeria gRPC client
EvitaServiceGrpc.EvitaServiceBlockingStub evitaService = GrpcClients.builder("https://demo.evitadb.io:5555/")
	.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);

// create a read-only session against our demo server
final GrpcEvitaSessionResponse sessionResponse = evitaService.createReadOnlySession(
	GrpcEvitaSessionRequest.newBuilder()
		.setCatalogName("evita")
		.build()
);

// create a session service using the acquired session ID
EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub sessionService = GrpcClients.builder("https://demo.evitadb.io:5555/")
	.intercept(new ClientSessionInterceptor())
	.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
