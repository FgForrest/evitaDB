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

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.DeleteEntitiesMutationHeaderDescriptor;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

/**
 * Handles single entity delete request.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EntityDeleteHandler extends RestHandler<CollectionRestHandlingContext> {

	public EntityDeleteHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange, restApiHandlingContext.getEndpointOperation());
		if(parametersFromRequest.containsKey(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY.name())) {
			final EntityContentRequire[] entityContentRequires = RequireConstraintFromRequestQueryBuilder.getEntityContentRequires(parametersFromRequest);

			restApiHandlingContext.updateCatalog(session -> {
				final Optional<SealedEntity> entity = session.deleteEntity(restApiHandlingContext.getEntityType(),
					(Integer) parametersFromRequest.get(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY.name()),
					entityContentRequires);

				if(entity.isPresent()) {
					setSuccessResponse(exchange, serializeResult(new EntityJsonSerializer(restApiHandlingContext, entity.get()).serialize()));
				} else {
					throw new HttpExchangeException(StatusCodes.NOT_FOUND, "Requested entity wasn't found and thus wasn't deleted.");
				}
			});
		} else {
			throw new HttpExchangeException(StatusCodes.BAD_REQUEST, "Primary key wasn't found in URL.");
		}
	}
}
