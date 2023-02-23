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

package io.evitadb.externalApi.rest.io.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.EntitySchemaMutationAggregateConverter;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RESTMutationObjectParser;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RESTMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.io.model.EntitySchemaUpdateRequestData;
import io.evitadb.externalApi.rest.io.serializer.EntitySchemaJsonSerializer;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Handles update request for entity schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class EntitySchemaUpdateHandler extends RESTApiHandler {

	public EntitySchemaUpdateHandler(@Nonnull RESTApiContext restApiContext) {
		super(restApiContext);
	}

	@Override
	protected void validateContext() {
		Assert.isPremiseValid(restApiContext.getObjectMapper() != null, "Instance of ObjectMapper must be set in context.");
		Assert.isPremiseValid(restApiContext.getEvita() != null, "Instance of Evita must be set in context.");
		Assert.isPremiseValid(restApiContext.getCatalog() != null, "Catalog must be set in context.");
		Assert.isPremiseValid(restApiContext.getEntityType() != null, "Entity type must be set in context.");
		Assert.isPremiseValid(restApiContext.getPathItem() != null, "PathItem must be set in context.");
		Assert.isPremiseValid(restApiContext.getOpenApi() != null, "OpenApi must be set in context.");
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		final EntitySchemaUpdateRequestData requestData = getRequestData(exchange);
		validateRequestData(requestData);

		final RESTMutationObjectParser restMutationObjectParser = new RESTMutationObjectParser(restApiContext.getObjectMapper());
		final EntitySchemaMutationAggregateConverter mutationAggregateResolver = new EntitySchemaMutationAggregateConverter(
			restMutationObjectParser,
			new RESTMutationResolvingExceptionFactory()
		);

		final List<EntitySchemaMutation> schemaMutations = new LinkedList<>();
		for (Iterator<JsonNode> elementsIterator = requestData.getMutations().elements(); elementsIterator.hasNext(); ) {
			schemaMutations.addAll(mutationAggregateResolver.convert(elementsIterator.next()));
		}
		final ModifyEntitySchemaMutation entitySchemaMutation = new ModifyEntitySchemaMutation(
			restApiContext.getEntityType(),
			schemaMutations.toArray(EntitySchemaMutation[]::new)
		);

		try(final EvitaSessionContract evitaSession = restApiContext.createReadWriteSession()) {
			final EntitySchemaContract updatedEntitySchema = evitaSession.updateAndFetchEntitySchema(entitySchemaMutation);
			setSuccessResponse(
				exchange,
				serializeResult(new EntitySchemaJsonSerializer(
					restApiContext,
					evitaSession::getEntitySchemaOrThrow,
					updatedEntitySchema
				).serialize())
			);
		}
	}

	@Nonnull
	protected EntitySchemaUpdateRequestData getRequestData(@Nonnull HttpServerExchange exchange) throws IOException {
		final String content = readRequestBody(exchange);
		if(content.trim().length() == 0) {
			throw new HttpExchangeException(
				StatusCodes.BAD_REQUEST,
				"Request's body contains no data."
			);
		}
		return restApiContext.getObjectMapper().readValue(content, EntitySchemaUpdateRequestData.class);
	}

	protected void validateRequestData(@Nonnull EntitySchemaUpdateRequestData requestData) {
		if (!requestData.isMutationsSet()) {
			throw new HttpExchangeException(
				StatusCodes.BAD_REQUEST,
				"Mutations are not set in request data."
			);
		}
	}
}
