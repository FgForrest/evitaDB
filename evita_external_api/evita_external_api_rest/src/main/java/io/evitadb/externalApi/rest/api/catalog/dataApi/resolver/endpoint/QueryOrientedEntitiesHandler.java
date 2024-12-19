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

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryEntityRequestDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

		this.filterConstraintResolver = new FilterConstraintResolver(restApiHandlingContext.getCatalogSchema());
		this.orderConstraintResolver = new OrderConstraintResolver(
			restApiHandlingContext.getCatalogSchema(),
			new AtomicReference<>(filterConstraintResolver)
		);
		this.requireConstraintResolver = new RequireConstraintResolver(
			restApiHandlingContext.getCatalogSchema(),
			new AtomicReference<>(filterConstraintResolver),
			new AtomicReference<>(orderConstraintResolver)
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.POST);
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
	protected CompletableFuture<Query> resolveQuery(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
		return parseRequestBody(executionContext, QueryEntityRequestDto.class)
			.thenApply(requestData -> {
				final Optional<Object> rawFilterBy = requestData.getFilterBy().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.FILTER_BY.name(), it));
				final Optional<Object> rawOrderBy = requestData.getOrderBy().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.ORDER_BY.name(), it));
				final Optional<Object> rawRequire = requestData.getRequire().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.REQUIRE.name(), it));
				requestExecutedEvent.finishInputDeserialization();

				return requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
					final FilterBy filterBy = rawFilterBy
						.map(container -> (FilterBy) filterConstraintResolver.resolve(restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.FILTER_BY.name(), container))
						.orElse(null);
					final OrderBy orderBy = rawOrderBy
						.map(container -> (OrderBy) orderConstraintResolver.resolve(restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.ORDER_BY.name(), container))
						.orElse(null);
					final Require require = rawRequire
						.map(container -> (Require) requireConstraintResolver.resolve(restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.REQUIRE.name(), container))
						.orElse(null);

					return query(
						collection(restHandlingContext.getEntityType()),
						addLocaleIntoFilterByWhenUrlPathLocalized(executionContext, filterBy),
						orderBy,
						require
					);
				});
			});
	}

	@Nullable
	protected Object deserializeConstraintContainer(@Nonnull String key, Object value) {
		Assert.isPremiseValid(
			value instanceof JsonNode,
			() -> new RestInternalError("Input value is not a JSON node. Instead it is `" + value.getClass().getName() + "`.")
		);

		//noinspection rawtypes
		final Schema rootSchema = (Schema) SchemaUtils.getTargetSchema(
				restHandlingContext.getEndpointOperation()
					.getRequestBody()
					.getContent()
					.get(MimeTypes.APPLICATION_JSON)
					.getSchema(),
				restHandlingContext.getOpenApi()
			)
			.getProperties()
			.get(key);

		try {
			return dataDeserializer.deserializeTree(
				SchemaUtils.getTargetSchema(rootSchema, restHandlingContext.getOpenApi()),
				(JsonNode) value
			);
		} catch (Exception e) {
			throw new RestInvalidArgumentException("Could not parse query: " + e.getMessage());
		}
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
