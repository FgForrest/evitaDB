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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.UpsertEntityUpsertRequestDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.mutation.RestEntityUpsertMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Handles upsert request for entity.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class UpsertEntityHandler extends RestHandler<CollectionRestHandlingContext> {

	@Nonnull private final RestEntityUpsertMutationConverter mutationResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;
	@Nonnull private final EntityJsonSerializer entityJsonSerializer;

	private final boolean withPrimaryKeyInPath;

	public UpsertEntityHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext, boolean withPrimaryKeyInPath) {
		super(restApiHandlingContext);
		this.mutationResolver = new RestEntityUpsertMutationConverter(
			restApiHandlingContext.getObjectMapper(),
			restApiHandlingContext.getEntitySchema()
		);
		this.requireConstraintResolver = new RequireConstraintResolver(restApiHandlingContext, restApiHandlingContext.getEndpointOperation());
		this.entityJsonSerializer = new EntityJsonSerializer(restApiHandlingContext);
		this.withPrimaryKeyInPath = withPrimaryKeyInPath;
	}

	@Override
	@Nonnull
	public Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final UpsertEntityUpsertRequestDto requestData = parseRequestBody(exchange, UpsertEntityUpsertRequestDto.class);

		if (withPrimaryKeyInPath) {
			final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange);
			Assert.isTrue(
				parametersFromRequest.containsKey(ParamDescriptor.PRIMARY_KEY.name()),
				() -> new RestInvalidArgumentException("Primary key is not present in request's URL path.")
			);
			requestData.setPrimaryKey((Integer) parametersFromRequest.get(ParamDescriptor.PRIMARY_KEY.name()));
		}

		final EntityMutation entityMutation = mutationResolver.convert(
			requestData.getPrimaryKey()
				.orElse(null),
			requestData.getEntityExistence()
				.orElseThrow(() -> new RestInvalidArgumentException("EntityExistence is not set in request data.")),
			requestData.getMutations()
				.orElseThrow(() -> new RestInvalidArgumentException("Mutations are not set in request data."))
		);

		final EntityContentRequire[] requires = getEntityContentRequires(requestData).orElse(null);

		final SealedEntity upsertedEntity = restApiHandlingContext.updateCatalog(session ->
			session.upsertAndFetchEntity(entityMutation, requires));

		return Optional.of(entityJsonSerializer.serialize(upsertedEntity));
	}

	@Nonnull
	private Optional<EntityContentRequire[]> getEntityContentRequires(@Nonnull UpsertEntityUpsertRequestDto requestData) {
		return requestData.getRequire()
			.map(it -> (Require) requireConstraintResolver.resolve(FetchRequestDescriptor.REQUIRE.name(), it))
			.flatMap(require -> Arrays.stream(require.getChildren())
				.filter(EntityFetch.class::isInstance)
				.findFirst()
				.map(entityFetch -> {
					final RequireConstraint[] children = ((EntityFetch) entityFetch).getChildren();
					final EntityContentRequire[] requires = new EntityContentRequire[children.length];
					for (int i = 0; i < children.length; i++) {
						requires[i] = (EntityContentRequire) children[i];
					}
					return requires;
				})
			);
	}
}
