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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterByConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

/**
 * Handle requests for unknown entity when entity is identified only by its URL or Code.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GetUnknownEntityHandler extends EntityHandler<CatalogRestHandlingContext> {

	public GetUnknownEntityHandler(@Nonnull CatalogRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
	}

	@Override
	@Nonnull
	protected EndpointResponse doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange);

		final Query query = Query.query(
			FilterByConstraintFromRequestQueryBuilder.buildFilterByForUnknownEntity(parametersFromRequest, restHandlingContext.getCatalogSchema()),
			RequireConstraintFromRequestQueryBuilder.buildRequire(parametersFromRequest)
		);

		log.debug("Generated evitaDB query for single unknown entity fetch is `{}`.", query);

		return exchange.session()
			.queryOne(query, EntityClassifier.class)
			.map(it -> (EndpointResponse) new SuccessEndpointResponse(convertResultIntoSerializableObject(exchange, it)))
			.orElse(new NotFoundEndpointResponse());
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.GET_STRING);
	}
}
