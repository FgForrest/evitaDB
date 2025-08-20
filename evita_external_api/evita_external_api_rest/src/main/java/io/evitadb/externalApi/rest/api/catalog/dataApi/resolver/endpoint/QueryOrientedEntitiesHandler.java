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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryEntityRequestDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.HeadConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.rest.traffic.RestQueryLabels;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryUtils.findFilter;
import static java.util.Optional.ofNullable;

/**
 * Ancestor for endpoints that accept query object as input.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public abstract class QueryOrientedEntitiesHandler extends JsonRestHandler<CollectionRestHandlingContext> {
	@Nonnull private final HeadConstraintResolver headConstraintResolver;
	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	protected QueryOrientedEntitiesHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);

		this.headConstraintResolver = new HeadConstraintResolver(restApiHandlingContext.getCatalogSchema());
		this.filterConstraintResolver = new FilterConstraintResolver(restApiHandlingContext.getCatalogSchema());
		this.orderConstraintResolver = new OrderConstraintResolver(
			restApiHandlingContext.getCatalogSchema(),
			new AtomicReference<>(this.filterConstraintResolver)
		);
		this.requireConstraintResolver = new RequireConstraintResolver(
			restApiHandlingContext.getCatalogSchema(),
			new AtomicReference<>(this.filterConstraintResolver),
			new AtomicReference<>(this.orderConstraintResolver)
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
		return readRawRequestBody(executionContext)
			.thenApply(rawRequestBody -> {
				try {
					final QueryEntityRequestDto body = parseRequestBody(rawRequestBody, QueryEntityRequestDto.class);
					trackSourceQuery(executionContext, rawRequestBody, null);
					return body;
				} catch (Exception e) {
					trackSourceQuery(executionContext, rawRequestBody, e);
					throw e;
				}
			})
			.thenApply(requestData -> {
				final Optional<Object> rawHead = requestData.getHead().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.HEAD.name(), it));
				final Optional<Object> rawFilterBy = requestData.getFilterBy().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.FILTER_BY.name(), it));
				final Optional<Object> rawOrderBy = requestData.getOrderBy().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.ORDER_BY.name(), it));
				final Optional<Object> rawRequire = requestData.getRequire().map(it -> deserializeConstraintContainer(FetchEntityRequestDescriptor.REQUIRE.name(), it));
				requestExecutedEvent.finishInputDeserialization();

				return requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
					final Head head = enrichHeadWithInternalConstraints(
						executionContext,
						rawHead
							.map(container -> (Head) this.headConstraintResolver.resolve(this.restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.HEAD.name(), container))
							.orElse(null)
					);
					final FilterBy filterBy = rawFilterBy
						.map(container -> (FilterBy) this.filterConstraintResolver.resolve(this.restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.FILTER_BY.name(), container))
						.map(it -> addLocaleIntoFilterByWhenUrlPathLocalized(executionContext, it))
						.orElse(null);
					final OrderBy orderBy = rawOrderBy
						.map(container -> (OrderBy) this.orderConstraintResolver.resolve(this.restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.ORDER_BY.name(), container))
						.orElse(null);
					final Require require = rawRequire
						.map(container -> (Require) this.requireConstraintResolver.resolve(this.restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.REQUIRE.name(), container))
						.orElse(null);

					return query(
						head,
						filterBy,
						orderBy,
						require
					);
				});
			});
	}

	private void trackSourceQuery(@Nonnull RestEndpointExecutionContext executionContext, @Nonnull String rawQuery, @Nullable Exception parsingError) {
		if (this.restHandlingContext.getEvita().getConfiguration().server().trafficRecording().sourceQueryTrackingEnabled()) {
			if (executionContext.session() instanceof EvitaInternalSessionContract evitaInternalSession) {
				try {
					final String serializedSourceQuery = this.restHandlingContext.getObjectMapper().writeValueAsString(rawQuery);
					final UUID recordingId = evitaInternalSession.recordSourceQuery(
						serializedSourceQuery, RestQueryLabels.REST_SOURCE_TYPE_VALUE, parsingError != null ? parsingError.getMessage() : null
					);

					executionContext.provideTrafficSourceQueryRecordingId(recordingId);
					executionContext.addCloseCallback(ctx -> {
						final String combinedErrorMessage = ctx.exceptions().stream().map(Throwable::getMessage).collect(Collectors.joining("; "));
						evitaInternalSession.finalizeSourceQuery(recordingId, !combinedErrorMessage.isBlank() ? combinedErrorMessage : null);
					});
				} catch (JsonProcessingException e) {
					log.error("Cannot serialize source rawQuery for traffic recording. Aborting.", e);
				}
			} else {
				log.error("Source rawQuery tracking is enabled but Evita session is not of internal type. Cannot record source rawQuery. Aborting.");
			}
		}
	}

	@Nonnull
	protected Head enrichHeadWithInternalConstraints(@Nonnull RestEndpointExecutionContext executionContext,
	                                                 @Nullable Head head) {
		final List<HeadConstraint> headConstraints = new LinkedList<>();

		headConstraints.add(collection(this.restHandlingContext.getEntityType()));
		headConstraints.add(label(Label.LABEL_SOURCE_TYPE, RestQueryLabels.REST_SOURCE_TYPE_VALUE));

		executionContext.trafficSourceQueryRecordingId()
			.ifPresent(uuid -> headConstraints.add(label(Label.LABEL_SOURCE_QUERY, uuid)));

		if (head != null) {
			Collections.addAll(headConstraints, head.getChildren());
		}

		return Objects.requireNonNull(head(headConstraints.toArray(HeadConstraint[]::new)));
	}

	@Nullable
	protected Object deserializeConstraintContainer(@Nonnull String key, Object value) {
		Assert.isPremiseValid(
			value instanceof JsonNode,
			() -> new RestInternalError("Input value is not a JSON node. Instead it is `" + value.getClass().getName() + "`.")
		);

		//noinspection rawtypes
		final Schema rootSchema = (Schema) SchemaUtils.getTargetSchema(
				this.restHandlingContext.getEndpointOperation()
					.getRequestBody()
					.getContent()
					.get(MimeTypes.APPLICATION_JSON)
					.getSchema(),
				this.restHandlingContext.getOpenApi()
			)
			.getProperties()
			.get(key);

		try {
			return this.dataDeserializer.deserializeTree(
				SchemaUtils.getTargetSchema(rootSchema, this.restHandlingContext.getOpenApi()),
				(JsonNode) value
			);
		} catch (Exception e) {
			throw new RestInvalidArgumentException("Could not parse query: " + e.getMessage());
		}
	}

	@Nonnull
	protected FilterBy addLocaleIntoFilterByWhenUrlPathLocalized(@Nonnull RestEndpointExecutionContext executionContext, @Nullable FilterBy filterBy) {
		if (this.restHandlingContext.isLocalized()) {
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
