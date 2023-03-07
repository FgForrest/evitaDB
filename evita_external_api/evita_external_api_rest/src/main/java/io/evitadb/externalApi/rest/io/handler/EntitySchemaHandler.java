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

import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.rest.io.serializer.EntitySchemaJsonSerializer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Handles request for fetching entity schema
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class EntitySchemaHandler extends RestHandler<CollectionRestHandlingContext> {

	public EntitySchemaHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		restApiHandlingContext.queryCatalog(session -> {
			final Optional<SealedEntitySchema> entitySchema = session.getEntitySchema(restApiHandlingContext.getEntityType());
			if(entitySchema.isPresent()) {
				setSuccessResponse(
					exchange,
					serializeResult(new EntitySchemaJsonSerializer(
						restApiHandlingContext,
						session::getEntitySchemaOrThrow,
						entitySchema.get()
					).serialize())
				);
			} else {
				throw new HttpExchangeException(StatusCodes.NOT_FOUND, "Requested entity schema wasn't found.");
			}
		});
	}
}
