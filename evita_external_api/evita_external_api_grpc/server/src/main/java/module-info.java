/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import io.evitadb.externalApi.grpc.GrpcProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

/**
 * Module contains gRPC API (server) for evitaDB.
 */
module evita.external.api.grpc {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;

	provides ExternalApiProviderRegistrar with GrpcProviderRegistrar;

	opens io.evitadb.externalApi.grpc.configuration to com.fasterxml.jackson.databind;
	exports io.evitadb.externalApi.grpc.configuration;

	requires static jsr305;
	requires static org.slf4j;
	requires static lombok;
	requires com.fasterxml.jackson.annotation;
	requires com.google.protobuf;
	requires proto.google.common.protos;
	requires grpc.workaround.build;
	requires com.fasterxml.jackson.databind;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.engine;
	requires evita.external.api.core;
	requires evita.external.api.grpc.shared;

	requires org.bouncycastle.pkix;
	requires org.bouncycastle.provider;
	requires undertow.core;
	requires grpc.netty;

}