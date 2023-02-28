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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.rest.api.catalog.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.PathItemsCreator;
import io.evitadb.externalApi.rest.exception.RESTApiInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RESTApiRequiredParameterMissingException;
import io.evitadb.externalApi.rest.io.handler.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.OrderByConstraintResolver;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.io.model.EntityQueryRequestData;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.collection;
import static java.util.Optional.ofNullable;

/**
 * Handles queries for list of entities.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EntityListHandler extends RESTApiHandler {
	public EntityListHandler(@Nonnull RESTApiContext restApiContext) {
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

		log.debug("Generated Evita query for entity list of type `" + restApiContext.getEntityType() + "` is `" + query + "`.");

		try (final EvitaSessionContract evitaSession = restApiContext.createReadOnlySession()) {
			final List<EntityClassifier> entities = evitaSession.queryList(query, EntityClassifier.class);
			setSuccessResponse(exchange, serializeResult(new EntityJsonSerializer(restApiContext, entities).serialize()));
		}
	}

	@Nonnull
	protected Query resolveQuery(@Nonnull HttpServerExchange exchange) throws IOException {
		final EntityQueryRequestData requestData = getRequestData(exchange);

		final FilterBy filterBy = requestData.isFilterBySet()?(FilterBy) new FilterConstraintResolver(restApiContext, restApiContext.getPathItem().getPost()).resolve(PathItemsCreator.FILTER_BY, requestData.getFilterBy()):null;
		final OrderBy orderBy = requestData.isOrderBySet()?(OrderBy) new OrderByConstraintResolver(restApiContext, restApiContext.getPathItem().getPost()).resolve(PathItemsCreator.ORDER_BY, requestData.getOrderBy()):null;
		final Require require = requestData.isRequireSet()?(Require) new RequireConstraintResolver(restApiContext, restApiContext.getPathItem().getPost()).resolve(PathItemsCreator.REQUIRE, requestData.getRequire()):null;

		return Query.query(
			collection(restApiContext.getEntityType()),
			addLocaleIntoFilterByWhenUrlPathLocalized(exchange, filterBy),
			orderBy,
			require
		);
	}

	protected FilterBy addLocaleIntoFilterByWhenUrlPathLocalized(@Nonnull HttpServerExchange exchange, @Nullable FilterBy filterBy) {
		if (restApiContext.isLocalized()) {
			final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange, restApiContext.getPathItem().getPost());
			final Locale locale = (Locale) parametersFromRequest.get(ParamDescriptor.LOCALE.name());
			if (locale == null) {
				throw new RESTApiRequiredParameterMissingException("Missing LOCALE in URL path.");
			}

			final Optional<FilterConstraint> localeEquals = ofNullable(QueryUtils.findFilter(Query.query(filterBy, QueryConstraints.require()),
				EntityLocaleEquals.class));
			if(localeEquals.isPresent()) {
				throw new RESTApiInvalidArgumentException("When using localized URL path then entity_locale_equals constraint can't be present in filterBy.");
			}

			if (filterBy != null) {
				final EntityLocaleEquals newLocaleConstraint = QueryConstraints.entityLocaleEquals(locale);
				return new FilterBy(and(combineConstraints(((And)filterBy.getChildren()[0]).getChildren(), newLocaleConstraint, FilterConstraint.class)));
			} else {
				return QueryConstraints.filterBy(QueryConstraints.entityLocaleEquals(locale));
			}
		}
		return filterBy;
	}

	@SuppressWarnings("unchecked")
	protected <T> T[] combineConstraints(@Nonnull T[] constraints, @Nonnull T constraint, @Nonnull Class<T> classForArray) {
		if (ArrayUtils.isEmpty(constraints)) {
			final Object array = Array.newInstance(classForArray, 1);
			Array.set(array, 0, constraint);
			return (T[]) array;
		} else {
			final Object array = Array.newInstance(classForArray, 1 + constraints.length);
			for (int i = 0; i < constraints.length; i++) {
				Array.set(array, i, constraints[i]);
			}
			Array.set(array, constraints.length, constraint);
			return (T[]) array;
		}
	}

	@Nonnull
	protected EntityQueryRequestData getRequestData(@Nonnull HttpServerExchange exchange) throws IOException {
		return restApiContext.getObjectMapper().readValue(readRequestBody(exchange), EntityQueryRequestData.class);
	}
}
