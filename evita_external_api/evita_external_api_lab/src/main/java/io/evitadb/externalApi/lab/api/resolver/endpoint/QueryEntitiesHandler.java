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

package io.evitadb.externalApi.lab.api.resolver.endpoint;

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryParser;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.lab.api.dto.QueryEntitiesRequestBodyDto;
import io.evitadb.externalApi.lab.api.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.lab.api.resolver.serializer.GenericEntityJsonSerializer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.PaginatedListDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryResponse.QueryResponseBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.StripListDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntitySerializationContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.ExtraResultsJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Returns entities by passed query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class QueryEntitiesHandler extends JsonRestHandler<LabApiHandlingContext> {

	@Nonnull private final QueryParser queryParser;
	@Nonnull private final GenericEntityJsonSerializer entityJsonSerializer;
	@Nonnull private final ExtraResultsJsonSerializer extraResultsJsonSerializer;

	public QueryEntitiesHandler(@Nonnull LabApiHandlingContext restHandlingContext) {
		super(restHandlingContext);
		this.queryParser = new DefaultQueryParser();
		this.entityJsonSerializer = new GenericEntityJsonSerializer(restHandlingContext.getObjectMapper());
		this.extraResultsJsonSerializer = new ExtraResultsJsonSerializer(
			this.entityJsonSerializer,
			this.restHandlingContext.getObjectMapper()
		);
	}

	@Nullable
	@Override
	protected Optional<EvitaSessionContract> createSession(@Nonnull RestEndpointExecutionContext exchange) {
		final Map<String, Object> parameters = getParametersFromRequest(exchange);
		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		final CatalogContract catalog = restHandlingContext.getEvita().getCatalogInstance(catalogName)
			.orElseThrow(() -> new RestInternalError("Catalog `" + catalogName + "` does not exist."));

		return Optional.of(restHandlingContext.getEvita().createReadOnlySession(catalog.getName()));
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

		return resolveQuery(executionContext)
			.thenApply(query -> {
				log.debug("Generated evitaDB query for entity query is `{}`.", query);

				final EvitaResponse<EntityClassifier> response = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().query(query, EntityClassifier.class));
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, response);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(HttpMethod.POST.name());
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

		return parseRequestBody(executionContext, QueryEntitiesRequestBodyDto.class)
			.thenApply(requestData -> {
				// todo lho arguments
				requestExecutedEvent.finishInputDeserialization();

				// todo lho arguments
				return requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() ->
					queryParser.parseQueryUnsafe(requestData.getQuery()));
			});

	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object result) {
		Assert.isPremiseValid(
			result instanceof EvitaResponse,
			() -> new RestInternalError("Expected evitaDB response, but got `" + result.getClass().getName() + "`.")
		);
		//noinspection unchecked
		final EvitaResponse<EntityClassifier> evitaResponse = (EvitaResponse<EntityClassifier>) result;
		final QueryResponseBuilder queryResponseBuilder = QueryResponse.builder()
			.recordPage(serializeRecordPage(exchange, evitaResponse));
		if (!evitaResponse.getExtraResults().isEmpty()) {
			queryResponseBuilder
				.extraResults(
					extraResultsJsonSerializer.serialize(
						evitaResponse.getExtraResults(),
						exchange.session().getEntitySchemaOrThrow(evitaResponse.getSourceQuery().getCollection().getEntityType()),
						exchange.session().getCatalogSchema()
					)
				);
		}
		return queryResponseBuilder.build();
	}

	@Nonnull
	private DataChunkDto serializeRecordPage(@Nonnull RestEndpointExecutionContext exchange, @Nonnull EvitaResponse<EntityClassifier> response) {
		final DataChunk<EntityClassifier> recordPage = response.getRecordPage();

		final EntitySerializationContext serializationContext = new EntitySerializationContext(exchange.session().getCatalogSchema());
		if (recordPage instanceof PaginatedList<EntityClassifier> paginatedList) {
			return new PaginatedListDto(
				paginatedList,
				entityJsonSerializer.serialize(serializationContext, paginatedList.getData())
			);
		} else if (recordPage instanceof StripList<EntityClassifier> stripList) {
			return new StripListDto(
				stripList,
				entityJsonSerializer.serialize(serializationContext, stripList.getData())
			);
		} else {
			throw new RestInternalError("Unsupported data chunk type `" + recordPage.getClass().getName() + "`.");
		}
	}
}
