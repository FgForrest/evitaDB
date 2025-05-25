/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.endpoint;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer.CatalogSchemaJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;

/**
 * Ancestor for endpoints working with {@link CatalogSchemaContract}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public abstract class CatalogSchemaHandler extends JsonRestHandler<CatalogRestHandlingContext> {

	@Nonnull
	private final CatalogSchemaJsonSerializer catalogSchemaJsonSerializer;

	protected CatalogSchemaHandler(@Nonnull CatalogRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.catalogSchemaJsonSerializer = new CatalogSchemaJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object catalogSchema) {
		Assert.isPremiseValid(
			catalogSchema instanceof CatalogSchemaContract,
			() -> new RestInternalError("Expected CatalogSchemaContract, but got `" + catalogSchema.getClass().getName() + "`.")
		);
		return this.catalogSchemaJsonSerializer.serialize(
			(CatalogSchemaContract) catalogSchema,
			exchange.session()::getEntitySchemaOrThrow,
			exchange.session().getAllEntityTypes()
		);
	}
}
