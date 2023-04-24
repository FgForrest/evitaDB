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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryEntityRequestDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.collection;

/**
 * Handles entity list delete request by query.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class DeleteEntitiesByQueryHandler extends ListEntitiesHandler {

	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;
	@Nonnull private final EntityJsonSerializer entityJsonSerializer;

	public DeleteEntitiesByQueryHandler(@Nonnull CollectionRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
		this.filterConstraintResolver = new FilterConstraintResolver(restApiHandlingContext);
		this.orderConstraintResolver = new OrderConstraintResolver(restApiHandlingContext);
		this.requireConstraintResolver = new RequireConstraintResolver(
			restApiHandlingContext,
			new AtomicReference<>(filterConstraintResolver),
			new AtomicReference<>(orderConstraintResolver)
		);
		this.entityJsonSerializer = new EntityJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	public String getSupportedHttpMethod() {
		return Methods.DELETE_STRING;
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
		final Query query = resolveQuery(exchange);

		log.debug("Generated evitaDB query for deletion of entity list of type `" + restApiHandlingContext.getEntitySchema() + "` is `" + query + "`.");

		final SealedEntity[] deletedEntities = restApiHandlingContext.updateCatalog(session ->
			session.deleteEntitiesAndReturnBodies(query));

		return Optional.of(entityJsonSerializer.serialize(deletedEntities));
	}

	@Override
	@Nonnull
	protected Query resolveQuery(@Nonnull HttpServerExchange exchange) {
		final QueryEntityRequestDto requestData = parseRequestBody(exchange, QueryEntityRequestDto.class);

		final FilterBy filterBy = requestData.getFilterBy()
			.map(it -> (FilterBy) filterConstraintResolver.resolve(FetchEntityRequestDescriptor.FILTER_BY.name(), it))
			.orElse(null);
		final OrderBy orderBy = requestData.getOrderBy()
			.map(it -> (OrderBy) orderConstraintResolver.resolve(FetchEntityRequestDescriptor.ORDER_BY.name(), it))
			.orElse(null);
		final Require require = requestData.getRequire()
			.map(it -> (Require) requireConstraintResolver.resolve(FetchEntityRequestDescriptor.REQUIRE.name(), it))
			.orElse(null);

		return Query.query(
			collection(restApiHandlingContext.getEntityType()),
			addLocaleIntoFilterByWhenUrlPathLocalized(exchange, filterBy),
			orderBy,
			require
		);
	}
}
