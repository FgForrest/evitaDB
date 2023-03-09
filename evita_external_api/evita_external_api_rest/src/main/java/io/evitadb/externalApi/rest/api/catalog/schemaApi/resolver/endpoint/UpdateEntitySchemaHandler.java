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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.EntitySchemaMutationAggregateConverter;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectParser;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.dto.EntitySchemaUpdateRequestData;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer.EntitySchemaJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Handles update request for entity schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class UpdateEntitySchemaHandler extends RestHandler<CollectionRestHandlingContext> {

	@Nonnull private final EntitySchemaMutationAggregateConverter mutationAggregateResolver;
	@Nonnull private final EntitySchemaJsonSerializer entitySchemaJsonSerializer;

	public UpdateEntitySchemaHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.mutationAggregateResolver = new EntitySchemaMutationAggregateConverter(
			new RestMutationObjectParser(restApiHandlingContext.getObjectMapper()),
			new RestMutationResolvingExceptionFactory()
		);
		this.entitySchemaJsonSerializer = new EntitySchemaJsonSerializer(restApiHandlingContext);
	}

	@Override
	@Nonnull
	public Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final EntitySchemaUpdateRequestData requestData = getRequestData(exchange);

		final List<EntitySchemaMutation> schemaMutations = new LinkedList<>();
		final JsonNode inputMutations = requestData.getMutations()
			.orElseThrow(() -> new RestInvalidArgumentException("Mutations are not set in request data."));
		for (Iterator<JsonNode> schemaMutationsIterator = inputMutations.elements(); schemaMutationsIterator.hasNext(); ) {
			schemaMutations.addAll(mutationAggregateResolver.convert(schemaMutationsIterator.next()));
		}
		final ModifyEntitySchemaMutation entitySchemaMutation = new ModifyEntitySchemaMutation(
			restApiHandlingContext.getEntityType(),
			schemaMutations.toArray(EntitySchemaMutation[]::new)
		);

		final JsonNode serializedUpdatedEntitySchema = restApiHandlingContext.updateCatalog(session -> {
			final EntitySchemaContract updatedEntitySchema = session.updateAndFetchEntitySchema(entitySchemaMutation);
			return entitySchemaJsonSerializer.serialize(session::getEntitySchemaOrThrow, updatedEntitySchema);
		});

		return Optional.of(serializedUpdatedEntitySchema);
	}

	@Nonnull
	protected EntitySchemaUpdateRequestData getRequestData(@Nonnull HttpServerExchange exchange) {
		final String content = readRequestBody(exchange);
		Assert.isTrue(
			content.trim().length() > 0,
			() -> new RestInvalidArgumentException("Request's body contains no data.")
		);

		try {
			return restApiHandlingContext.getObjectMapper().readValue(content, EntitySchemaUpdateRequestData.class);
		} catch (JsonProcessingException e) {
			throw new RestInternalError("Could not parse request body: ", e);
		}
	}
}
