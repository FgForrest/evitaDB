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

package io.evitadb.externalApi.lab.api.resolver.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
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
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.ExtraResultsJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Returns entities by passed query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class QueryEntitiesHandler extends JsonRestHandler<EvitaResponse<EntityClassifier>, LabApiHandlingContext> {

	@Nonnull private final QueryParser queryParser;
	@Nonnull private final GenericEntityJsonSerializer entityJsonSerializer;
	@Nonnull private final ExtraResultsJsonSerializer extraResultsJsonSerializer;

	public QueryEntitiesHandler(@Nonnull LabApiHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.queryParser = new DefaultQueryParser();
		this.entityJsonSerializer = new GenericEntityJsonSerializer(restApiHandlingContext);
		this.extraResultsJsonSerializer = new ExtraResultsJsonSerializer(
			restApiHandlingContext,
			this.entityJsonSerializer,
			Map.of()
		);
	}

	@Nullable
	@Override
	protected Optional<EvitaSessionContract> createSession(@Nonnull RestEndpointExchange exchange) {
		final Map<String, Object> parameters = getParametersFromRequest(exchange);
		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		final CatalogContract catalog = restApiHandlingContext.getCatalog(catalogName, ExternalApiNamingConventions.URL_NAME_NAMING_CONVENTION)
			.orElseThrow(() -> new RestInternalError("Catalog `" + catalogName + "` does not exist."));

		return Optional.of(restApiHandlingContext.getEvita().createReadOnlySession(catalog.getName()));
	}

	@Nonnull
	@Override
	protected EndpointResponse<EvitaResponse<EntityClassifier>> doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final Query query = resolveQuery(exchange);
		log.debug("Generated evitaDB query for entity query is `{}`.", query);

		final EvitaResponse<EntityClassifier> response = exchange.session().query(query, EntityClassifier.class);
		return new SuccessEndpointResponse<>(response);
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
	protected Query resolveQuery(@Nonnull RestEndpointExchange exchange) {
		final QueryEntitiesRequestBodyDto requestData = parseRequestBody(exchange, QueryEntitiesRequestBodyDto.class);

		// todo lho arguments
		return queryParser.parseQueryUnsafe(requestData.getQuery());
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExchange exchange, @Nonnull EvitaResponse<EntityClassifier> result) {
		final QueryResponseBuilder queryResponseBuilder = QueryResponse.builder()
			.recordPage(serializeRecordPage(result));
		if (!result.getExtraResults().isEmpty()) {
			queryResponseBuilder
				.extraResults(extraResultsJsonSerializer.serialize(result.getExtraResults()));
		}
		return queryResponseBuilder.build();
	}

	@Nonnull
	private DataChunkDto serializeRecordPage(@Nonnull EvitaResponse<EntityClassifier> response) {
		final DataChunk<EntityClassifier> recordPage = response.getRecordPage();

		if (recordPage instanceof PaginatedList<EntityClassifier> paginatedList) {
			return new PaginatedListDto(
				paginatedList,
				entityJsonSerializer.serialize(paginatedList.getData())
			);
		} else if (recordPage instanceof StripList<EntityClassifier> stripList) {
			return new StripListDto(
				stripList,
				entityJsonSerializer.serialize(stripList.getData())
			);
		} else {
			throw new RestInternalError("Unsupported data chunk type `" + recordPage.getClass().getName() + "`.");
		}
	}
}
