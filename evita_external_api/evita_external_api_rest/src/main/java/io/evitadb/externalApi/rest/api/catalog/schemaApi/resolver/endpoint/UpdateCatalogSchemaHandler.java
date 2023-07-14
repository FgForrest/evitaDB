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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.LocalCatalogSchemaMutationAggregateConverter;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectParser;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.dto.CreateOrUpdateEntitySchemaRequestData;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Handles update request for catalog schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class UpdateCatalogSchemaHandler extends CatalogSchemaHandler {

	@Nonnull private final LocalCatalogSchemaMutationAggregateConverter mutationAggregateResolver;

	public UpdateCatalogSchemaHandler(@Nonnull CatalogRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.mutationAggregateResolver = new LocalCatalogSchemaMutationAggregateConverter(
			new RestMutationObjectParser(restApiHandlingContext.getObjectMapper()),
			new RestMutationResolvingExceptionFactory()
		);
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Override
	@Nonnull
	protected EndpointResponse<CatalogSchemaContract> doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final CreateOrUpdateEntitySchemaRequestData requestData = parseRequestBody(exchange, CreateOrUpdateEntitySchemaRequestData.class);

		final List<LocalCatalogSchemaMutation> schemaMutations = new LinkedList<>();
		final JsonNode inputMutations = requestData.getMutations()
			.orElseThrow(() -> new RestInvalidArgumentException("Mutations are not set in request data."));
		for (Iterator<JsonNode> schemaMutationsIterator = inputMutations.elements(); schemaMutationsIterator.hasNext(); ) {
			schemaMutations.addAll(mutationAggregateResolver.convert(schemaMutationsIterator.next()));
		}

		final CatalogSchemaContract updatedCatalogSchema = exchange.session().updateAndFetchCatalogSchema(
			schemaMutations.toArray(LocalCatalogSchemaMutation[]::new)
		);
		return new SuccessEndpointResponse<>(updatedCatalogSchema);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.PUT_STRING);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}
}
