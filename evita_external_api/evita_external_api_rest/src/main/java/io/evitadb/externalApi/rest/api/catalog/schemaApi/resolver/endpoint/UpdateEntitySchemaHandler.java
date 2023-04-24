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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.EntitySchemaMutationAggregateConverter;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectParser;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.dto.CreateOrUpdateEntitySchemaRequestData;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer.EntitySchemaJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
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

	@Nonnull
	@Override
	public String getSupportedHttpMethod() {
		return Methods.PUT_STRING;
	}

	@Override
	public boolean acceptsRequestBodies() {
		return true;
	}

	@Override
	public boolean returnsResponseBodies() {
		return true;
	}

	@Override
	@Nonnull
	public Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final CreateOrUpdateEntitySchemaRequestData requestData = parseRequestBody(exchange, CreateOrUpdateEntitySchemaRequestData.class);

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
}
