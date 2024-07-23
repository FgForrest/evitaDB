/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.system.resolver.serializer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CorruptedCatalogDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import io.evitadb.utils.NamingConvention;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Map;

/**
 * Handles serializing of {@link CatalogContract} into JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CatalogJsonSerializer {

	private final ObjectJsonSerializer objectJsonSerializer;

	public CatalogJsonSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		this.objectJsonSerializer = new ObjectJsonSerializer(restHandlingContext.getObjectMapper());
	}

	@Nonnull
	public ObjectNode serialize(@Nonnull CatalogContract c) {
		if (c instanceof CorruptedCatalog corruptedCatalog) {
			return serialize(corruptedCatalog);
		} else if (c instanceof Catalog catalog) {
			return serialize(catalog);
		} else {
			throw new RestInternalError("Missing support for serializing `" + c.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public ArrayNode serialize(@Nonnull Collection<CatalogContract> catalogs) {
		final ArrayNode arrayNode = objectJsonSerializer.arrayNode();
		catalogs.forEach(catalog -> arrayNode.add(serialize(catalog)));

		return arrayNode;
	}

	@Nonnull
	private ObjectNode serialize(@Nonnull Catalog catalog) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();
		rootNode.put(CatalogDescriptor.CATALOG_ID.name(), catalog.getCatalogId().toString());
		rootNode.put(CatalogDescriptor.NAME.name(), catalog.getName());
		rootNode.put(CatalogDescriptor.NAME_VARIANTS.name(), serializeNameVariants(catalog.getSchema().getNameVariants()));
		rootNode.put(CatalogDescriptor.VERSION.name(), String.valueOf(catalog.getVersion()));
		rootNode.put(CatalogDescriptor.CATALOG_STATE.name(), catalog.getCatalogState().name());
		rootNode.put(CatalogDescriptor.SUPPORTS_TRANSACTION.name(), catalog.supportsTransaction());
		rootNode.put(CatalogDescriptor.CORRUPTED.name(), false);

		final ArrayNode entityTypes = objectJsonSerializer.arrayNode();
		catalog.getEntityTypes().forEach(entityTypes::add);
		rootNode.set(CatalogDescriptor.ENTITY_TYPES.name(), entityTypes);

		return rootNode;
	}

	@Nonnull
	private ObjectNode serialize(@Nonnull CorruptedCatalog corruptedCatalog) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();
		rootNode.put(CorruptedCatalogDescriptor.CATALOG_ID.name(), corruptedCatalog.getCatalogId().toString());
		rootNode.put(CorruptedCatalogDescriptor.NAME.name(), corruptedCatalog.getName());
		rootNode.put(CorruptedCatalogDescriptor.CATALOG_STORAGE_PATH.name(), corruptedCatalog.getCatalogStoragePath().toString());
		rootNode.put(CorruptedCatalogDescriptor.CAUSE.name(), corruptedCatalog.getCause().toString());
		rootNode.put(CorruptedCatalogDescriptor.CORRUPTED.name(), true);

		return rootNode;
	}

	@Nonnull
	private ObjectNode serializeNameVariants(@Nonnull Map<NamingConvention, String> nameVariants) {
		final ObjectNode nameVariantsNode = objectJsonSerializer.objectNode();
		nameVariantsNode.put(NameVariantsDescriptor.CAMEL_CASE.name(), nameVariants.get(NamingConvention.CAMEL_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.PASCAL_CASE.name(), nameVariants.get(NamingConvention.PASCAL_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.SNAKE_CASE.name(), nameVariants.get(NamingConvention.SNAKE_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), nameVariants.get(NamingConvention.UPPER_SNAKE_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.KEBAB_CASE.name(), nameVariants.get(NamingConvention.KEBAB_CASE));

		return nameVariantsNode;
	}
}
