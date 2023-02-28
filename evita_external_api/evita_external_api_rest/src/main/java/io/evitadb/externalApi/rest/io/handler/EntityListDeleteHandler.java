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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.rest.api.catalog.builder.PathItemsCreator;
import io.evitadb.externalApi.rest.io.handler.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.OrderByConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.io.model.EntityQueryRequestData;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Arrays;

import static io.evitadb.api.query.QueryConstraints.collection;

/**
 * Handles entity list delete request by query.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EntityListDeleteHandler extends EntityListHandler {
	public EntityListDeleteHandler(@Nonnull RESTApiContext restApiContext) {
		super(restApiContext);
	}

	@Override
	protected void validateContext() {
		Assert.isPremiseValid(restApiContext.getObjectMapper() != null, "Instance of ObjectMapper must be set in context.");
		Assert.isPremiseValid(restApiContext.getEvita() != null, "Instance of Evita must be set in context.");
		Assert.isPremiseValid(restApiContext.getCatalog() != null, "Catalog must be set in context.");
		Assert.isPremiseValid(restApiContext.getEntityType() != null, "Entity type must be set in context.");
		Assert.isPremiseValid(restApiContext.getPathItem() != null, "PathItem must be set in context.");
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		final Query query = resolveQuery(exchange);

		log.debug("Generated Evita query for deletion of entity list of type `" + restApiContext.getEntityType() + "` is `" + query + "`.");

		try(final EvitaSessionContract evitaSession = restApiContext.createReadWriteSession()) {
			final SealedEntity[] deletedEntities = evitaSession.deleteEntitiesAndReturnBodies(query);
			setSuccessResponse(exchange, serializeResult(new EntityJsonSerializer(restApiContext, Arrays.asList(deletedEntities)).serialize()));
		}
	}

	@Override
	@Nonnull
	protected Query resolveQuery(@Nonnull HttpServerExchange exchange) throws IOException {
		final EntityQueryRequestData requestData = getRequestData(exchange);

		final FilterBy filterBy = requestData.isFilterBySet()?(FilterBy) new FilterConstraintResolver(restApiContext, restApiContext.getPathItem().getDelete()).resolve(PathItemsCreator.FILTER_BY, requestData.getFilterBy()):null;
		final OrderBy orderBy = requestData.isOrderBySet()?(OrderBy) new OrderByConstraintResolver(restApiContext, restApiContext.getPathItem().getDelete()).resolve(PathItemsCreator.ORDER_BY, requestData.getOrderBy()):null;
		final Require require = requestData.isRequireSet()?(Require) new RequireConstraintResolver(restApiContext, restApiContext.getPathItem().getDelete()).resolve(PathItemsCreator.REQUIRE, requestData.getRequire()):null;

		return Query.query(
			collection(restApiContext.getEntityType()),
			addLocaleIntoFilterByWhenUrlPathLocalized(exchange, filterBy),
			orderBy,
			require
		);
	}
}
