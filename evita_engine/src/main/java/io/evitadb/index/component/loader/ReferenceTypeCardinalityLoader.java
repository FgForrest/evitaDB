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

import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityLeafStreamKey;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the cross-reference cardinality bookkeeping carried by `ReferencedTypeEntityIndex`.
 *
 * The reference name is extracted from the `EntityIndexKey.discriminator()` which is always a
 * `String` for `REFERENCED_ENTITY_TYPE` and `REFERENCED_GROUP_ENTITY_TYPE` keys — the dispatcher
 * has already validated this before invoking the loader, so a cast-failure here is a
 * programming error.
 */
public final class ReferenceTypeCardinalityLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexKey entityIndexKey = context.entityIndexKey();
		final String referenceName = Objects.requireNonNull((String) entityIndexKey.discriminator());
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long primaryKey = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
			entityIndexId, referenceName, service.getReadOnlyKeyCompressor()
		);
		final ReferenceTypeCardinalityIndexStoragePart part = service.getStoragePart(
			context.catalogVersion(), primaryKey, ReferenceTypeCardinalityIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Cardinality index with id `" + entityIndexId + "` with key `" + referenceName +
				"` was not found in persistent storage!"
		);

		final ReferenceTypeCardinalityIndex index;
		if (part.isPaged()) {
			// PAGED root: fetch each live leaf page by its `pack(streamId, pageSequence)` key (the stream id folds the
			// (entityIndexPrimaryKey, referenceName) identity), then reassemble the boundary-stable tree from the pages
			final int streamId = service.getReadOnlyKeyCompressor().getId(
				new ReferenceTypeCardinalityLeafStreamKey(entityIndexId, referenceName)
			);
			final int[] orderedPageSequences = Objects.requireNonNull(part.getLeafPageSequences());
			final long[][] perPageKeys = new long[orderedPageSequences.length][];
			final long[][] perPagePayloads = new long[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final ReferenceTypeCardinalityIndexLeafPagePart leaf = service.getStoragePart(
					context.catalogVersion(),
					ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
					ReferenceTypeCardinalityIndexLeafPagePart.class
				);
				isPremiseValid(
					leaf != null,
					"Cardinality leaf page " + orderedPageSequences[i] + " for index `" + entityIndexId +
						"` reference `" + referenceName + "` was not found in persistent storage!"
				);
				perPageKeys[i] = leaf.getKeys();
				perPagePayloads[i] = leaf.getPayloads();
			}
			index = ReferenceTypeCardinalityIndex.fromPersistedPages(
				orderedPageSequences, perPageKeys, perPagePayloads,
				part.getHighWaterPageSequence(), part.getReferencedPrimaryKeysIndex()
			);
		} else {
			// SINGLE root: rebuild the small (≤ one leaf) index from the inline columns through the map constructor
			final long[] keys = Objects.requireNonNull(part.getKeys());
			final long[] payloads = Objects.requireNonNull(part.getPayloads());
			final Map<Long, Integer> cardinalities = CollectionUtils.createHashMap(keys.length);
			for (int i = 0; i < keys.length; i++) {
				cardinalities.put(keys[i], (int) payloads[i]);
			}
			index = new ReferenceTypeCardinalityIndex(cardinalities, part.getReferencedPrimaryKeysIndex());
		}
		return new LoadedComponentBundle.ReferenceTypeCardinality(index);
	}

}
