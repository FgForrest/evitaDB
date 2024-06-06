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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryEntityRequestDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.utils.ArrayUtils;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryUtils.findFilter;
import static java.util.Optional.ofNullable;

/**
 * Ancestor for endpoints that accept query object as input.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public abstract class QueryOrientedEntitiesHandler extends JsonRestHandler<CollectionRestHandlingContext> {

	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	protected QueryOrientedEntitiesHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);

		this.filterConstraintResolver = new FilterConstraintResolver(restApiHandlingContext);
		this.orderConstraintResolver = new OrderConstraintResolver(restApiHandlingContext);
		this.requireConstraintResolver = new RequireConstraintResolver(
			restApiHandlingContext,
			new AtomicReference<>(filterConstraintResolver),
			new AtomicReference<>(orderConstraintResolver)
		);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.POST_STRING);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	protected Query resolveQuery(@Nonnull RestEndpointExecutionContext executionContext) {
		final QueryEntityRequestDto requestData = parseRequestBody(executionContext, QueryEntityRequestDto.class);

		final FilterBy filterBy = requestData.getFilterBy()
			.map(container -> (FilterBy) filterConstraintResolver.resolve(FetchEntityRequestDescriptor.FILTER_BY.name(), container))
			.orElse(null);
		final OrderBy orderBy = requestData.getOrderBy()
			.map(container -> (OrderBy) orderConstraintResolver.resolve(FetchEntityRequestDescriptor.ORDER_BY.name(), container))
			.orElse(null);
		final Require require = requestData.getRequire()
			.map(container -> (Require) requireConstraintResolver.resolve(FetchEntityRequestDescriptor.REQUIRE.name(), container))
			.orElse(null);

		return query(
			collection(restHandlingContext.getEntityType()),
			addLocaleIntoFilterByWhenUrlPathLocalized(executionContext, filterBy),
			orderBy,
			require
		);
	}

	@Nonnull
	protected FilterBy addLocaleIntoFilterByWhenUrlPathLocalized(@Nonnull RestEndpointExecutionContext executionContext, @Nullable FilterBy filterBy) {
		if (restHandlingContext.isLocalized()) {
			final Map<String, Object> parametersFromRequest = getParametersFromRequest(executionContext);
			final Locale locale = (Locale) parametersFromRequest.get(FetchEntityEndpointHeaderDescriptor.LOCALE.name());
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
					ArrayUtils.mergeArrays(
						filterBy.getChildren(),
						new FilterConstraint[] { newLocaleConstraint }
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
}
