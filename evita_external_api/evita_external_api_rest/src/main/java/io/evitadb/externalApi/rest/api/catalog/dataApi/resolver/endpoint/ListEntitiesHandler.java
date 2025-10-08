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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntitySerializationContext;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles queries for list of entities.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ListEntitiesHandler extends QueryOrientedEntitiesHandler {

	@Nonnull private final EntityJsonSerializer entityJsonSerializer;

	public ListEntitiesHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.entityJsonSerializer = new EntityJsonSerializer(this.restHandlingContext.isLocalized(), this.restHandlingContext.getObjectMapper());
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
		return resolveQuery(executionContext)
			.thenApply(query -> {
				log.debug("Generated evitaDB query for entity list of type `{}` is `{}`.", this.restHandlingContext.getEntitySchema(), query);

				final List<EntityClassifier> entities = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().queryList(query, EntityClassifier.class));
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, entities);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object entities) {
		Assert.isPremiseValid(
			entities instanceof List,
			() -> new RestInternalError("Expected list of entities, but got `" + entities.getClass().getName() + "`.")
		);
		//noinspection unchecked
		return this.entityJsonSerializer.serialize(
			new EntitySerializationContext(this.restHandlingContext.getCatalogSchema()),
			(List<EntityClassifier>) entities
		);
	}
}
