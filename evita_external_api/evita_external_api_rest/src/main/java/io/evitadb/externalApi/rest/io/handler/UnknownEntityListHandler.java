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

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.rest.io.handler.constraint.FilterByConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * Handles requests for multiple unknown entities identified by their URLs or codes.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class UnknownEntityListHandler extends RestHandler<RestHandlingContext> {

	public UnknownEntityListHandler(@Nonnull RestHandlingContext restHandlingContext) {
		super(restHandlingContext);
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange, restApiHandlingContext.getEndpointOperation());

		final Query query = Query.query(
			FilterByConstraintFromRequestQueryBuilder.buildFilterByForUnknownEntityList(parametersFromRequest, restApiHandlingContext.getCatalogSchema()),
			RequireConstraintFromRequestQueryBuilder.buildRequire(parametersFromRequest)
		);

		log.debug("Generated Evita query for unknown entity list fetch is `" + query + "`.");

		restApiHandlingContext.queryCatalog(session -> {
			final List<EntityClassifier> entities = session.queryList(query, EntityClassifier.class);
			setSuccessResponse(exchange, serializeResult(new EntityJsonSerializer(restApiHandlingContext, entities).serialize()));
		});
	}
}
