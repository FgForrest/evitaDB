/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Set;

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
	protected EndpointResponse doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		Query query = resolveQuery(executionContext);
		if (QueryUtils.findRequire(query, EntityFetch.class, SeparateEntityContentRequireContainer.class) == null) {
			query = Query.query(
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
		}
		log.debug("Generated evitaDB query for deletion of entity list of type `{}` is `{}`.", restHandlingContext.getEntitySchema(), query);

		final SealedEntity[] deletedEntities = executionContext.session().deleteSealedEntitiesAndReturnBodies(query);

		return new SuccessEndpointResponse(convertResultIntoSerializableObject(executionContext, deletedEntities));
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.DELETE_STRING);
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object deletedEntities) {
		Assert.isPremiseValid(
			deletedEntities instanceof SealedEntity[],
			() -> new RestInternalError("Expected SealedEntity[], but got `" + deletedEntities.getClass().getName() + "`.")
		);
		return entityJsonSerializer.serialize(
			new EntitySerializationContext(restHandlingContext.getCatalogSchema()),
			(SealedEntity[]) deletedEntities
		);
	}
}
