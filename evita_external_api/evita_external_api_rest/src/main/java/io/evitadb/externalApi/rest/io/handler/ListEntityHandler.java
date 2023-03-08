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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.rest.api.catalog.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.evitadb.externalApi.rest.io.handler.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.OrderByConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.io.model.EntityQueryRequestData;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.evitadb.utils.ArrayUtils;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryUtils.findFilter;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Handles queries for list of entities.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ListEntityHandler extends RestHandler<CollectionRestHandlingContext> {

	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderByConstraintResolver orderByConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	@Nonnull private final EntityJsonSerializer entityJsonSerializer;

	public ListEntityHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);

		this.filterConstraintResolver = new FilterConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation());
		this.orderByConstraintResolver = new OrderByConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation());
		this.requireConstraintResolver = new RequireConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation());

		this.entityJsonSerializer = new EntityJsonSerializer(restApiHandlingContext);
	}

	@Override
	@Nonnull
	public Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final Query query = resolveQuery(exchange);

		log.debug("Generated Evita query for entity list of type `" + restApiHandlingContext.getEntitySchema() + "` is `" + query + "`.");

		final List<EntityClassifier> entities = restApiHandlingContext.queryCatalog(session ->
			session.queryList(query, EntityClassifier.class));

		return of(entityJsonSerializer.serialize(entities));
	}

	@Nonnull
	protected Query resolveQuery(@Nonnull HttpServerExchange exchange) {
		final EntityQueryRequestData requestData = getRequestData(exchange);

		final FilterBy filterBy = requestData.getFilterBy()
			.map(container -> (FilterBy) filterConstraintResolver.resolve(QueryRequestBodyDescriptor.FILTER_BY.name(), container))
			.orElse(null);
		final OrderBy orderBy = requestData.getOrderBy()
			.map(container -> (OrderBy) orderByConstraintResolver.resolve(QueryRequestBodyDescriptor.ORDER_BY.name(), container))
			.orElse(null);
		final Require require = requestData.getRequire()
			.map(container -> (Require) requireConstraintResolver.resolve(QueryRequestBodyDescriptor.REQUIRE.name(), container))
			.orElse(null);

		return query(
			collection(restApiHandlingContext.getEntityType()),
			addLocaleIntoFilterByWhenUrlPathLocalized(exchange, filterBy),
			orderBy,
			require
		);
	}

	@Nonnull
	protected FilterBy addLocaleIntoFilterByWhenUrlPathLocalized(@Nonnull HttpServerExchange exchange, @Nullable FilterBy filterBy) {
		if (restApiHandlingContext.isLocalized()) {
			final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange, restApiHandlingContext.getEndpointOperation());
			final Locale locale = (Locale) parametersFromRequest.get(ParamDescriptor.LOCALE.name());
			if (locale == null) {
				throw new RestRequiredParameterMissingException("Missing LOCALE in URL path.");
			}

			final Optional<FilterConstraint> localeEquals = ofNullable(findFilter(
				query(
					filterBy,
					require()
				),
				EntityLocaleEquals.class
			));
			if (localeEquals.isPresent()) {
				throw new RestInvalidArgumentException("When using localized URL path then entity_locale_equals constraint can't be present in filterBy.");
			}

			if (filterBy != null) {
				final EntityLocaleEquals newLocaleConstraint = entityLocaleEquals(locale);
				return filterBy(
					and(
						combineConstraints(
							((And)filterBy.getChildren()[0]).getChildren(),
							newLocaleConstraint,
							FilterConstraint.class
						)
					)
				);
			} else {
				return filterBy(
					entityLocaleEquals(locale)
				);
			}
		}
		return filterBy;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	protected <T> T[] combineConstraints(@Nonnull T[] constraints, @Nonnull T constraint, @Nonnull Class<T> classForArray) {
		final Object array;
		if (ArrayUtils.isEmpty(constraints)) {
			array = Array.newInstance(classForArray, 1);
			Array.set(array, 0, constraint);
		} else {
			array = Array.newInstance(classForArray, 1 + constraints.length);
			for (int i = 0; i < constraints.length; i++) {
				Array.set(array, i, constraints[i]);
			}
			Array.set(array, constraints.length, constraint);
		}
		return (T[]) array;
	}

	@Nonnull
	protected EntityQueryRequestData getRequestData(@Nonnull HttpServerExchange exchange) {
		try {
			return restApiHandlingContext.getObjectMapper().readValue(readRequestBody(exchange), EntityQueryRequestData.class);
		} catch (JsonProcessingException e) {
			throw new RestInternalError("Could not parse request body: ", e);
		}
	}
}
