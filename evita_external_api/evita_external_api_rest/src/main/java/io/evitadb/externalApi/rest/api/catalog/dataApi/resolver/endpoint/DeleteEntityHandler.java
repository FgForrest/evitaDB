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

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.DeleteEntitiesMutationHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
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
public class DeleteEntityHandler extends RestHandler<CollectionRestHandlingContext> {

	@Nonnull
	private final EntityJsonSerializer entityJsonSerializer;

	public DeleteEntityHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.entityJsonSerializer = new EntityJsonSerializer(restApiHandlingContext);
	}

	@Override
	@Nonnull
	public Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange, restApiHandlingContext.getEndpointOperation());

		Assert.isTrue(
			parametersFromRequest.containsKey(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY.name()),
			() -> new RestInvalidArgumentException("Primary key wasn't found in URL.")
		);

		final EntityContentRequire[] entityContentRequires = RequireConstraintFromRequestQueryBuilder.getEntityContentRequires(parametersFromRequest);

		final Optional<SealedEntity> deletedEntity = restApiHandlingContext.updateCatalog(session ->
			session.deleteEntity(
				restApiHandlingContext.getEntityType(),
				(Integer) parametersFromRequest.get(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY.name()),
				entityContentRequires
			)
		);

		return deletedEntity.map(entityJsonSerializer::serialize);
	}
}
