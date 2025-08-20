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

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.rest.api.system.resolver.serializer.CatalogJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;

/**
 * Ancestor for endpoints returning {@link CatalogContract}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class CatalogHandler extends JsonRestHandler<SystemRestHandlingContext> {

	@Nonnull
	private final CatalogJsonSerializer catalogJsonSerializer;

	public CatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.catalogJsonSerializer = new CatalogJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object catalog) {
		Assert.isPremiseValid(
			catalog instanceof CatalogContract,
			() -> new RestInternalError("Catalog should be instance of CatalogContract, but was `" + catalog.getClass().getName() + "`.")
		);
		return this.catalogJsonSerializer.serialize((CatalogContract) catalog);
	}
}
