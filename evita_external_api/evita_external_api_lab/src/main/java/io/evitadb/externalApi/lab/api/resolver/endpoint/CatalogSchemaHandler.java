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

package io.evitadb.externalApi.lab.api.resolver.endpoint;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.lab.api.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer.CatalogSchemaJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/**
 * Ancestor for endpoints working with {@link CatalogSchemaContract}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public abstract class CatalogSchemaHandler extends JsonRestHandler<CatalogSchemaContract, LabApiHandlingContext> {

	@Nonnull
	private final CatalogSchemaJsonSerializer catalogSchemaJsonSerializer;

	protected CatalogSchemaHandler(@Nonnull LabApiHandlingContext labApiHandlingContext) {
		super(labApiHandlingContext);
		catalogSchemaJsonSerializer = new CatalogSchemaJsonSerializer(labApiHandlingContext);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nullable
	@Override
	protected Optional<EvitaSessionContract> createSession(@Nonnull RestEndpointExchange exchange) {
		// creates session based on url parameters instead of static context

		final Map<String, Object> parameters = getParametersFromRequest(exchange);
		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		final CatalogContract catalog = restApiHandlingContext.getEvita().getCatalogInstance(catalogName)
			.orElseThrow(() -> new RestInvalidArgumentException("Catalog `" + catalogName + "` does not exist."));

		if (modifiesData()) {
			return Optional.of(restApiHandlingContext.getEvita().createReadWriteSession(catalog.getName()));
		} else {
			return Optional.of(restApiHandlingContext.getEvita().createReadOnlySession(catalog.getName()));
		}
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExchange exchange, @Nonnull CatalogSchemaContract catalogSchema) {
		return catalogSchemaJsonSerializer.serialize(
			catalogSchema,
			exchange.session()::getEntitySchemaOrThrow,
			exchange.session().getAllEntityTypes()
		);
	}
}
