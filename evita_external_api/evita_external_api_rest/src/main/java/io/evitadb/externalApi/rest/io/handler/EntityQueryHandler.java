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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.dataType.DataChunk;
import io.evitadb.externalApi.rest.io.model.PaginatedList;
import io.evitadb.externalApi.rest.io.model.QueryResponse;
import io.evitadb.externalApi.rest.io.model.StripList;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.io.serializer.ExtraResultsJsonSerializer;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;

/**
 * Handles request for entities with full query, i.e. require constraint can contain not only basic fetch constraints but
 * also constraints to get hierarchy parents/statistics, histogram, etc. Response then contains entity data and also
 * additional data in extraResults section.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EntityQueryHandler extends EntityListHandler {

	private Map<String, String> referenceNameToFieldName;

	public EntityQueryHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.referenceNameToFieldName = restApiHandlingContext.getEntitySchema().getReferences().values().stream()
			.map(referenceSchema -> new SimpleEntry<>(referenceSchema.getName(), referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION)))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		final Query query = resolveQuery(exchange);

		log.debug("Generated Evita query for entity query of type `" + restApiHandlingContext.getEntitySchema() + "` is `" + query + "`.");

		restApiHandlingContext.queryCatalog(session -> {
			final EvitaResponse<EntityClassifier> response = session.query(query, EntityClassifier.class);

			final QueryResponse.QueryResponseBuilder queryResponseBuilder = QueryResponse.builder();
			setRecordPage(response, queryResponseBuilder);
			setExtraResults(response, queryResponseBuilder);

			setSuccessResponse(exchange, serializeResult(queryResponseBuilder.build()));
		});
	}

	private void setRecordPage(@Nonnull final EvitaResponse<EntityClassifier> response, QueryResponse.QueryResponseBuilder queryResponseBuilder) {
		final DataChunk<EntityClassifier> recordPage = response.getRecordPage();
		if(recordPage instanceof io.evitadb.dataType.PaginatedList<EntityClassifier> paginatedList) {
			final PaginatedList restPaginatedList = new PaginatedList(paginatedList, new EntityJsonSerializer(restApiHandlingContext, paginatedList.getData()).serialize());
			queryResponseBuilder.recordPage(restPaginatedList);
		} else if(recordPage instanceof io.evitadb.dataType.StripList<EntityClassifier> stripList) {
			final StripList restStripList = new StripList(stripList, new EntityJsonSerializer(restApiHandlingContext, stripList.getData()).serialize());
			queryResponseBuilder.recordPage(restStripList);
		}
	}

	private void setExtraResults(@Nonnull final EvitaResponse<EntityClassifier> response, QueryResponse.QueryResponseBuilder queryResponseBuilder) {
		final JsonNode extraResultsNode = new ExtraResultsJsonSerializer(restApiHandlingContext, response.getExtraResults(), referenceNameToFieldName).serialize();
		if(!extraResultsNode.isEmpty()) {
			queryResponseBuilder.extraResults(extraResultsNode);
		}
	}
}
