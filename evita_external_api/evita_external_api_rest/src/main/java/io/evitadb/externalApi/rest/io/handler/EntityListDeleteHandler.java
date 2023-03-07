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

import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.io.handler.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.OrderByConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.io.model.EntityQueryRequestData;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
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
	public EntityListDeleteHandler(@Nonnull CollectionRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		final Query query = resolveQuery(exchange);

		log.debug("Generated Evita query for deletion of entity list of type `" + restApiHandlingContext.getEntitySchema() + "` is `" + query + "`.");

		restApiHandlingContext.updateCatalog(session -> {
			final SealedEntity[] deletedEntities = session.deleteEntitiesAndReturnBodies(query);
			setSuccessResponse(exchange, serializeResult(new EntityJsonSerializer(restApiHandlingContext, Arrays.asList(deletedEntities)).serialize()));
		});
	}

	@Override
	@Nonnull
	protected Query resolveQuery(@Nonnull HttpServerExchange exchange) throws IOException {
		final EntityQueryRequestData requestData = getRequestData(exchange);

		final FilterBy filterBy = requestData.isFilterBySet()?(FilterBy) new FilterConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation()).resolve(QueryRequestBodyDescriptor.FILTER_BY.name(), requestData.getFilterBy()):null;
		final OrderBy orderBy = requestData.isOrderBySet()?(OrderBy) new OrderByConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation()).resolve(QueryRequestBodyDescriptor.ORDER_BY.name(), requestData.getOrderBy()):null;
		final Require require = requestData.isRequireSet()?(Require) new RequireConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation()).resolve(QueryRequestBodyDescriptor.REQUIRE.name(), requestData.getRequire()):null;

		return Query.query(
			collection(restApiHandlingContext.getEntityType()),
			addLocaleIntoFilterByWhenUrlPathLocalized(exchange, filterBy),
			orderBy,
			require
		);
	}
}
