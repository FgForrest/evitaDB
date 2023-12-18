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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.PaginatedListDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.QueryResponse.QueryResponseBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.StripListDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.ExtraResultsJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Handles request for entities with full query, i.e. require constraint can contain not only basic fetch constraints but
 * also constraints to get hierarchy parents/statistics, histogram, etc. Response then contains entity data and also
 * additional data in extraResults section.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class QueryEntitiesHandler extends QueryOrientedEntitiesHandler {

	@Nonnull private final EntityJsonSerializer entityJsonSerializer;
	@Nonnull private final ExtraResultsJsonSerializer extraResultsJsonSerializer;

	public QueryEntitiesHandler(@Nonnull CollectionRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
		this.entityJsonSerializer = new EntityJsonSerializer(this.restHandlingContext.isLocalized(), this.restHandlingContext.getObjectMapper());

		final Map<String, String> referenceNameToFieldName = this.restHandlingContext.getEntitySchema().getReferences().values().stream()
			.map(referenceSchema -> new SimpleEntry<>(referenceSchema.getName(), referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION)))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		this.extraResultsJsonSerializer = new ExtraResultsJsonSerializer(
			this.entityJsonSerializer,
			referenceNameToFieldName::get,
			this.restHandlingContext.getObjectMapper()
		);
	}

	@Override
	@Nonnull
	protected EndpointResponse doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final Query query = resolveQuery(exchange);
		log.debug("Generated evitaDB query for entity query of type `{}` is `{}`.", restHandlingContext.getEntitySchema(), query);

		final EvitaResponse<EntityClassifier> response = exchange.session().query(query, EntityClassifier.class);

		return new SuccessEndpointResponse(convertResultIntoSerializableObject(exchange, response));
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExchange exchange, @Nonnull Object response) {
		Assert.isPremiseValid(
			response instanceof EvitaResponse,
			() -> new RestInternalError("Expected evitaDB response, but got `" + response.getClass().getName() + "`.")
		);

		//noinspection unchecked
		final EvitaResponse<EntityClassifier> evitaResponse = (EvitaResponse<EntityClassifier>) response;
		final QueryResponseBuilder queryResponseBuilder = QueryResponse.builder()
			.recordPage(serializeRecordPage(evitaResponse));
		if (!evitaResponse.getExtraResults().isEmpty()) {
			queryResponseBuilder
				.extraResults(extraResultsJsonSerializer.serialize(evitaResponse.getExtraResults(), restHandlingContext.getEntitySchema(), restHandlingContext.getCatalogSchema()));
		}

		return queryResponseBuilder.build();
	}

	@Nonnull
	private DataChunkDto serializeRecordPage(@Nonnull EvitaResponse<EntityClassifier> response) {
		final DataChunk<EntityClassifier> recordPage = response.getRecordPage();

		if (recordPage instanceof PaginatedList<EntityClassifier> paginatedList) {
			return new PaginatedListDto(
				paginatedList,
				entityJsonSerializer.serialize(paginatedList.getData(), restHandlingContext.getCatalogSchema())
			);
		} else if (recordPage instanceof StripList<EntityClassifier> stripList) {
			return new StripListDto(
				stripList,
				entityJsonSerializer.serialize(stripList.getData(), restHandlingContext.getCatalogSchema())
			);
		} else {
			throw new RestInternalError("Unsupported data chunk type `" + recordPage.getClass().getName() + "`.");
		}
	}
}
