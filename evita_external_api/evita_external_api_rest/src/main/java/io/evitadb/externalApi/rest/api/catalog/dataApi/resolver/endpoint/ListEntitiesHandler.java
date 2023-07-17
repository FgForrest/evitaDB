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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Handles queries for list of entities.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ListEntitiesHandler extends QueryOrientedEntitiesHandler<List<EntityClassifier>> {

	@Nonnull private final EntityJsonSerializer entityJsonSerializer;

	public ListEntitiesHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.entityJsonSerializer = new EntityJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	protected EndpointResponse<List<EntityClassifier>> doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final Query query = resolveQuery(exchange);

		log.debug("Generated evitaDB query for entity list of type `{}` is `{}`.", restApiHandlingContext.getEntitySchema(), query);

		final List<EntityClassifier> entities = exchange.session().queryList(query, EntityClassifier.class);

		return new SuccessEndpointResponse<>(entities);
	}

	@Nonnull
	@Override
	protected JsonNode convertResultIntoJson(@Nonnull RestEndpointExchange exchange, @Nonnull List<EntityClassifier> entities) {
		return entityJsonSerializer.serialize(entities);
	}
}
