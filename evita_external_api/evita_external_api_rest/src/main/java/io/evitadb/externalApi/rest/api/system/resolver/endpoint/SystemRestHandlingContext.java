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

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import io.evitadb.utils.NamingConvention;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;

/**
 * This class contains information required to process REST API requests. Not all attributes has to be set
 * it depends on needs of particular handler.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SystemRestHandlingContext extends RestHandlingContext {


	public SystemRestHandlingContext(@Nonnull ObjectMapper objectMapper,
	                                 @Nonnull Evita evita,
	                                 @Nonnull OpenAPI openApi,
									 @Nonnull Map<String, Class<? extends Enum<?>>> enumMapping,
	                                 @Nonnull Operation endpointOperation,
	                                 boolean localized) {
		super(objectMapper, evita, openApi, enumMapping, endpointOperation, localized);
	}

	@Nonnull
	public Optional<CatalogContract> getCatalog(@Nonnull String name, @Nullable NamingConvention namingConvention) {
		if (namingConvention == null) {
			return evita.getCatalogInstance(name);
		}
		return evita.getCatalogs()
			.stream()
			.filter(c -> c.getSchema().getNameVariant(namingConvention).equals(name))
			.findFirst();
	}
}
