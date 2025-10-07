/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.api.catalog.cdcApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingInfrastructureMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectMapper;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.externalApi.rest.io.RestHandlingContext;

import javax.annotation.Nonnull;

/**
 * Serializes {@link ChangeCatalogCapture} to JSON for REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ChangeCatalogCaptureSerializer {

	@Nonnull
	private final ObjectJsonSerializer objectJsonSerializer;
	@Nonnull
	private final DelegatingLocalMutationConverter localMutationConverter;
	@Nonnull
	private final DelegatingEntityMutationConverter entityMutationConverter;
	@Nonnull
	private final DelegatingLocalCatalogSchemaMutationConverter localCatalogSchemaMutationConverter;
	@Nonnull
	private final DelegatingEntitySchemaMutationConverter entitySchemaMutationConverter;
	@Nonnull
	private final DelegatingInfrastructureMutationConverter infrastructureMutationConverter;

	public ChangeCatalogCaptureSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		this.objectJsonSerializer = new ObjectJsonSerializer(restHandlingContext.getObjectMapper());

		final RestMutationObjectMapper mutationObjectMapper = new RestMutationObjectMapper(restHandlingContext.getObjectMapper());
		final RestMutationResolvingExceptionFactory exceptionFactory = RestMutationResolvingExceptionFactory.INSTANCE;

		this.localMutationConverter = new DelegatingLocalMutationConverter(restHandlingContext.getObjectMapper(), mutationObjectMapper, exceptionFactory);
		this.entityMutationConverter = new DelegatingEntityMutationConverter(restHandlingContext.getObjectMapper(), mutationObjectMapper, exceptionFactory);
		this.localCatalogSchemaMutationConverter = new DelegatingLocalCatalogSchemaMutationConverter(mutationObjectMapper, exceptionFactory);
		this.entitySchemaMutationConverter = new DelegatingEntitySchemaMutationConverter(mutationObjectMapper, exceptionFactory);
		this.infrastructureMutationConverter = new DelegatingInfrastructureMutationConverter(mutationObjectMapper, exceptionFactory);
	}

	@Nonnull
	public ObjectNode serialize(@Nonnull ChangeCatalogCapture capture) {
		final ObjectNode rootNode = this.objectJsonSerializer.objectNode();

		// todo lho descriptor?
		rootNode.putIfAbsent("version", this.objectJsonSerializer.serializeObject(capture.version()));
		rootNode.putIfAbsent("index", this.objectJsonSerializer.serializeObject(capture.index()));
		rootNode.putIfAbsent("area", this.objectJsonSerializer.serializeObject(capture.area()));
		rootNode.putIfAbsent("entityType", capture.entityType() != null ? this.objectJsonSerializer.serializeObject(capture.entityType()) : null);
		rootNode.putIfAbsent("entityType", capture.entityPrimaryKey() != null ? this.objectJsonSerializer.serializeObject(capture.entityPrimaryKey()) : null);
		rootNode.putIfAbsent("operation", this.objectJsonSerializer.serializeObject(capture.operation()));

		if (capture.body() != null) {
			final JsonNode convertedBody;
			if (capture.body() instanceof EntityMutation entityMutation) {
				convertedBody = (JsonNode) this.entityMutationConverter.convertToOutput(entityMutation);
			} else if (capture.body() instanceof LocalMutation<?, ?> localMutation) {
				convertedBody = (JsonNode) this.localMutationConverter.convertToOutput(localMutation);
			} else if (capture.body() instanceof LocalCatalogSchemaMutation catalogSchemaMutation) {
				convertedBody = (JsonNode) this.localCatalogSchemaMutationConverter.convertToOutput(catalogSchemaMutation);
			} else if (capture.body() instanceof EntitySchemaMutation entitySchemaMutation) {
				convertedBody = (JsonNode) this.entitySchemaMutationConverter.convertToOutput(entitySchemaMutation);
			} else if (capture.body() instanceof TransactionMutation transactionMutation) {
				convertedBody = (JsonNode) this.infrastructureMutationConverter.convertToOutput(transactionMutation);
			} else {
				throw new RestQueryResolvingInternalError("Unsupported entity mutation: " + capture.body());
			}
			rootNode.putIfAbsent("body", convertedBody);
		}

		return rootNode;
	}
}
