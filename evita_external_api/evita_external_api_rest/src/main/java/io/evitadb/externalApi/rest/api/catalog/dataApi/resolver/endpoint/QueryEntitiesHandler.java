/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryResponse.QueryResponseBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.DataChunkJsonSerializer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntitySerializationContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.ExtraResultsJsonSerializer;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Handles request for entities with full query, i.e. require constraint can contain not only basic fetch constraints but
 * also constraints to get hierarchy parents/statistics, histogram, etc. Response then contains entity data and also
 * additional data in extraResults section.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class QueryEntitiesHandler extends QueryOrientedEntitiesHandler {

	@Nonnull private final EntityJsonSerializer entityJsonSerializer;
	@Nonnull private final DataChunkJsonSerializer dataChunkJsonSerializer;
	@Nonnull private final ExtraResultsJsonSerializer extraResultsJsonSerializer;

	public QueryEntitiesHandler(@Nonnull CollectionRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
		this.entityJsonSerializer = new EntityJsonSerializer(this.restHandlingContext.isLocalized(), this.restHandlingContext.getObjectMapper());
		this.dataChunkJsonSerializer = new DataChunkJsonSerializer(new ObjectJsonSerializer(this.restHandlingContext.getObjectMapper()));
		this.extraResultsJsonSerializer = new ExtraResultsJsonSerializer(
			this.entityJsonSerializer,
			this.restHandlingContext.getObjectMapper()
		);
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

		return resolveQuery(executionContext)
			.thenApply(query -> {
				log.debug("Generated evitaDB query for entity query of type `{}` is `{}`.", this.restHandlingContext.getEntitySchema(), query);

				final EvitaResponse<EntityClassifier> response = requestExecutedEvent.measureInternalEvitaDBExecution(() -> {
					try {
						return executionContext.session().query(query, EntityClassifier.class);
					} catch (Exception e) {
						executionContext.addException(e);
						throw e;
					}
				});
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, response);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object response) {
		Assert.isPremiseValid(
			response instanceof EvitaResponse,
			() -> new RestInternalError("Expected evitaDB response, but got `" + response.getClass().getName() + "`.")
		);

		//noinspection unchecked
		final EvitaResponse<EntityClassifier> evitaResponse = (EvitaResponse<EntityClassifier>) response;
		final QueryResponseBuilder queryResponseBuilder = QueryResponse.builder()
			.recordPage(serializeRecordPage(evitaResponse));
		if (!evitaResponse.getExtraResults().isEmpty()) {
			queryResponseBuilder
				.extraResults(serializeExtraResults(evitaResponse));
		}

		return queryResponseBuilder.build();
	}

	@Nonnull
	private JsonNode serializeRecordPage(@Nonnull EvitaResponse<EntityClassifier> response) {
		final EntitySerializationContext serializationContext = new EntitySerializationContext(this.restHandlingContext.getCatalogSchema());
		return this.dataChunkJsonSerializer.serialize(
			response.getRecordPage(),
			item -> this.entityJsonSerializer.serialize(serializationContext, item)
		);
	}

	@Nonnull
	private JsonNode serializeExtraResults(EvitaResponse<EntityClassifier> evitaResponse) {
		return this.extraResultsJsonSerializer.serialize(
			evitaResponse.getExtraResults(),
			this.restHandlingContext.getEntitySchema(),
			this.restHandlingContext.getCatalogSchema()
		);
	}
}
