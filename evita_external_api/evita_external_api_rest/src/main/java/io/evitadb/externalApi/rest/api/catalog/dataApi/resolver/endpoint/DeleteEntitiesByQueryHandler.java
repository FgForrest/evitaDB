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

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntitySerializationContext;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Handles entity list delete request by query.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class DeleteEntitiesByQueryHandler extends QueryOrientedEntitiesHandler {

	@Nonnull private final EntityJsonSerializer entityJsonSerializer;

	public DeleteEntitiesByQueryHandler(@Nonnull CollectionRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
		this.entityJsonSerializer = new EntityJsonSerializer(this.restHandlingContext.isLocalized(), this.restHandlingContext.getObjectMapper());
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

		return resolveQuery(executionContext)
			.thenApply(query -> {
				final Query finalQuery = requestExecutedEvent.measureInternalEvitaDBExecution(() -> {
					if (QueryUtils.findRequire(query, EntityFetch.class, SeparateEntityContentRequireContainer.class) == null) {
						return Query.query(
							query.getCollection(),
							query.getFilterBy(),
							query.getOrderBy(),
							require(
								ArrayUtils.mergeArrays(
									Optional.ofNullable(query.getRequire()).map(ConstraintContainer::getChildren).orElse(new RequireConstraint[0]),
									new RequireConstraint[] { entityFetch() }
								)
							)
						);
					} else {
						return query;
					}
				});
				log.debug("Generated evitaDB query for deletion of entity list of type `{}` is `{}`.", this.restHandlingContext.getEntitySchema(), finalQuery);

				final SealedEntity[] deletedEntities = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().deleteSealedEntitiesAndReturnBodies(finalQuery));
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, deletedEntities);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.DELETE);
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object deletedEntities) {
		Assert.isPremiseValid(
			deletedEntities instanceof SealedEntity[],
			() -> new RestInternalError("Expected SealedEntity[], but got `" + deletedEntities.getClass().getName() + "`.")
		);
		return this.entityJsonSerializer.serialize(
			new EntitySerializationContext(this.restHandlingContext.getCatalogSchema()),
			(SealedEntity[]) deletedEntities
		);
	}
}
