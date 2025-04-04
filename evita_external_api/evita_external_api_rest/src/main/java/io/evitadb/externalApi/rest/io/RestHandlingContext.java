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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * This class contains information required to process REST API requests. Not all attributes has to be set
 * it depends on needs of particular handler.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RestHandlingContext {

	@Nonnull @Getter protected final Evita evita;
	@Nonnull @Getter protected final ObjectMapper objectMapper;
	@Nonnull @Getter protected final ExternalApiTracingContext<Object> tracingContext;

	@Nonnull @Getter private final OpenAPI openApi;
	@Nonnull @Getter private final Map<String, Class<? extends Enum<?>>> enumMapping;
	@Nonnull @Getter private final Operation endpointOperation;

	@Getter private final boolean localized;

	public RestHandlingContext(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headers,
		@Nonnull OpenAPI openApi,
		@Nonnull Map<String, Class<? extends Enum<?>>> enumMapping,
		@Nonnull Operation endpointOperation,
		boolean localized
	) {
		this.objectMapper = objectMapper;
		this.evita = evita;
		this.tracingContext = ExternalApiTracingContextProvider.getContext(headers);
		this.openApi = openApi;
		this.enumMapping = enumMapping;
		this.endpointOperation = endpointOperation;
		this.localized = localized;
	}
}
