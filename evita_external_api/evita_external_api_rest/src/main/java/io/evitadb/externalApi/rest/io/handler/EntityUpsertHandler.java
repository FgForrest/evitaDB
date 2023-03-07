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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.UpsertEntityMutationHeaderDescriptor;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.resolver.data.mutation.RestEntityUpsertMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RESTMutationObjectParser;
import io.evitadb.externalApi.rest.io.handler.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.io.model.EntityUpsertRequestData;
import io.evitadb.externalApi.rest.io.serializer.EntityJsonSerializer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Handles upsert request for entity.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EntityUpsertHandler extends RestHandler<CollectionRestHandlingContext> {

	private final boolean withPrimaryKeyInUrl;

	public EntityUpsertHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext, boolean withPrimaryKeyInUrl) {
		super(restApiHandlingContext);
		this.withPrimaryKeyInUrl = withPrimaryKeyInUrl;
	}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		validateRequest(exchange);

		final EntityUpsertRequestData requestData = getRequestData(exchange);

		if(withPrimaryKeyInUrl) {
			final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange, restApiHandlingContext.getEndpointOperation());
			if (parametersFromRequest.containsKey(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY.name())) {
				requestData.setPrimaryKey((Integer) parametersFromRequest.get(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY.name()));
			} else {
				throw new HttpExchangeException(
					StatusCodes.BAD_REQUEST,
					"Primary key is not present in request's URL path."
				);
			}
		}

		validateRequestData(requestData);

		final RESTMutationObjectParser restMutationObjectParser = new RESTMutationObjectParser(restApiHandlingContext.getObjectMapper());

		final RestEntityUpsertMutationConverter mutationResolver = new RestEntityUpsertMutationConverter(restApiHandlingContext, restMutationObjectParser);
		final EntityMutation entityMutation = mutationResolver.resolve(requestData.getPrimaryKey(), requestData.getEntityExistence(), requestData.getMutations());

		final EntityContentRequire[] children = getEntityContentRequires(requestData);

		restApiHandlingContext.updateCatalog(session -> {
			final SealedEntity upsertedEntity = session.upsertAndFetchEntity(entityMutation, children);
			setSuccessResponse(exchange, serializeResult(new EntityJsonSerializer(restApiHandlingContext, upsertedEntity).serialize()));
		});
	}

	@Nullable
	private EntityContentRequire[] getEntityContentRequires(EntityUpsertRequestData requestData) {
		final Require require = requestData.isRequireSet()?(Require) new RequireConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation()).resolve(QueryRequestBodyDescriptor.REQUIRE.name(), requestData.getRequire()):null;
		if(require != null) {
			final Optional<RequireConstraint> entityFetch = Arrays.stream(require.getChildren()).filter(requireConstraint -> requireConstraint.getClass().equals(EntityFetch.class)).findFirst();

			if(entityFetch.isPresent()) {
				final RequireConstraint[] children = ((EntityFetch) entityFetch.get()).getChildren();
				final EntityContentRequire[] requires = new EntityContentRequire[children.length];
				for (int i = 0; i < children.length; i++) {
					requires[i] = (EntityContentRequire) children[i];
				}
				return requires;
			}
		}
		return null;
	}

	@Nonnull
	protected EntityUpsertRequestData getRequestData(@Nonnull HttpServerExchange exchange) throws IOException {
		final String content = readRequestBody(exchange);
		if(content.trim().length() == 0) {
			throw new HttpExchangeException(
				StatusCodes.BAD_REQUEST,
				"Request's body contains no data."
			);
		}
		return restApiHandlingContext.getObjectMapper().readValue(content, EntityUpsertRequestData.class);
	}

	protected void validateRequestData(@Nonnull EntityUpsertRequestData requestData) {
		if (!requestData.isEntityExistenceSet()) {
			throw new HttpExchangeException(
				StatusCodes.BAD_REQUEST,
				"EntityExistence is not set in request data."
			);
		}
		if (!requestData.isMutationsSet()) {
			throw new HttpExchangeException(
				StatusCodes.BAD_REQUEST,
				"Mutations are not set in request data."
			);
		}
	}
}
