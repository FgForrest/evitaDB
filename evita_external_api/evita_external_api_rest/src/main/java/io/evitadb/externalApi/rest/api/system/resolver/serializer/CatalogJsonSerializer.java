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

package io.evitadb.externalApi.rest.api.system.resolver.serializer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.SystemRestHandlingContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Handles serializing of {@link CatalogContract} into JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CatalogJsonSerializer {

	private final ObjectJsonSerializer objectJsonSerializer;

	public CatalogJsonSerializer(@Nonnull SystemRestHandlingContext restHandlingContext) {
		this.objectJsonSerializer = new ObjectJsonSerializer(restHandlingContext.getObjectMapper());
	}

	@Nonnull
	public ObjectNode serialize(@Nonnull CatalogContract catalog) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();
		rootNode.put(CatalogDescriptor.NAME.name(), catalog.getName());
		rootNode.put(CatalogDescriptor.VERSION.name(), String.valueOf(catalog.getVersion()));
		rootNode.put(CatalogDescriptor.CATALOG_STATE.name(), catalog.getCatalogState().name());
		rootNode.put(CatalogDescriptor.SUPPORTS_TRANSACTION.name(), catalog.supportsTransaction());

		final ArrayNode entityTypes = objectJsonSerializer.arrayNode();
		catalog.getEntityTypes().forEach(entityTypes::add);
		rootNode.set(CatalogDescriptor.ENTITY_TYPES.name(), entityTypes);

		return rootNode;
	}
}
