/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component.loader;

import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the per-attribute CARDINALITY index map carried by `ReferencedTypeEntityIndex` and
 * `ReducedGroupEntityIndex`.
 *
 * The cardinality entries share the `AttributeIndexStorageKey` namespace with UNIQUE / FILTER /
 * SORT / CHAIN but are routed to a separate map on the in-memory side because they are owned by
 * the subclass cardinality bookkeeping rather than the shared `AttributeIndex`.
 */
public final class AttributeCardinalityIndexMapLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		// count CARDINALITY entries to pre-size the map
		int cardinalityCount = 0;
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			if (key.indexType() == AttributeIndexType.CARDINALITY) {
				cardinalityCount++;
			}
		}
		final Map<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes =
			CollectionUtils.createHashMap(cardinalityCount);
		if (cardinalityCount == 0) {
			return new LoadedComponentBundle.AttributeCardinalityIndexes(cardinalityIndexes);
		}
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			if (key.indexType() != AttributeIndexType.CARDINALITY) {
				continue;
			}
			final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
				entityIndexId, AttributeIndexType.CARDINALITY, key.attribute(),
				service.getReadOnlyKeyCompressor()
			);
			final AttributeCardinalityIndexStoragePart part = service.getStoragePart(
				catalogVersion, primaryKey, AttributeCardinalityIndexStoragePart.class
			);
			isPremiseValid(
				part != null,
				"Cardinality index with id " + entityIndexId + " with key " + key.attribute() +
					" was not found in persistent storage!"
			);
			cardinalityIndexes.put(part.getAttributeIndexKey(), part.getCardinalityIndex());
		}
		return new LoadedComponentBundle.AttributeCardinalityIndexes(cardinalityIndexes);
	}

}
