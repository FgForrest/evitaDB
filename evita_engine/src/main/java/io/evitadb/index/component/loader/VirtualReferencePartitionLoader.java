/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionLeafStreamKey;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the {@link VirtualReferencePartitionIndex} carried by `ReferencedTypeEntityIndex`.
 *
 * Unlike the other loaders this one treats a missing root as a valid state: an index that never held an entry
 * persists nothing, so an absent root - in a catalog written before the index existed, or in one that never put an
 * entry into it - loads as an empty index. A root that is present, however, must have every leaf page it lists. The
 * sort indexes of the declared sort keys are not persisted at all; the index rebuilds them from the loaded entries.
 *
 * The reference name is the `EntityIndexKey.discriminator()`, which the dispatcher has validated to be a `String`
 * before invoking the loader.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		return new LoadedComponentBundle.VirtualReferencePartitions(
			loadIndex(
				context.storagePartService(),
				context.catalogVersion(),
				context.entityIndexId(),
				Objects.requireNonNull((String) context.entityIndexKey().discriminator()),
				slot -> context.resolveSortNormalizer(
					slot.attributeIndexKey(), slot.comparatorBase(), slot.indexedDecimalPlaces())
			)
		);
	}

	/**
	 * Reloads the index of one referenced-type entity index on its own, without the rest of that entity index - the
	 * way the storage protocol migration reads a registry it rewrites, holding no more than the registry itself. It
	 * binds the legacy type-level key functions only; {@link #load(LoadContext)} goes through the overload taking the
	 * owner-resolved functions instead.
	 *
	 * @param service        the storage part service of the collection
	 * @param catalogVersion the catalog version to read at
	 * @param entityIndexId  the primary key of the referenced-type entity index carrying the index
	 * @param referenceName  the reference of that entity index
	 * @return the loaded index, empty when it has no root on disk
	 */
	@Nonnull
	public static VirtualReferencePartitionIndex loadIndex(
		@Nonnull StoragePartPersistenceService<?> service,
		long catalogVersion,
		int entityIndexId,
		@Nonnull String referenceName
	) {
		return loadIndex(
			service,
			catalogVersion,
			entityIndexId,
			referenceName,
			slot -> SortIndex.createLegacyNormalizerFor(slot.comparatorBase(), slot.indexedDecimalPlaces())
		);
	}

	/**
	 * Loads with owner-resolved functions bound before constructing sort dictionaries and trees.
	 *
	 * @param service            the storage part service of the collection
	 * @param catalogVersion     the catalog version to read at
	 * @param entityIndexId      the primary key of the referenced-type entity index carrying the index
	 * @param referenceName      the reference of that entity index
	 * @param normalizerProvider supplies the key function of each persisted sort value slot
	 * @return the loaded index, empty when it has no root on disk
	 */
	@Nonnull
	public static VirtualReferencePartitionIndex loadIndex(
		@Nonnull StoragePartPersistenceService<?> service,
		long catalogVersion,
		int entityIndexId,
		@Nonnull String referenceName,
		@Nonnull Function<SortValueSlot, UnaryOperator<Serializable>> normalizerProvider
	) {
		final KeyCompressor keyCompressor = service.getReadOnlyKeyCompressor();

		// a reference name the compressor never saw proves no root was ever written
		final OptionalInt referenceNameId = keyCompressor.getIdIfExists(new ReferenceNameKey(referenceName));
		final VirtualReferencePartitionIndexStoragePart part = referenceNameId.isPresent() ?
			service.getStoragePart(
				catalogVersion,
				VirtualReferencePartitionIndexStoragePart.computeUniquePartId(
					entityIndexId, referenceNameId.getAsInt()
				),
				VirtualReferencePartitionIndexStoragePart.class
			) :
			null;
		if (part == null) {
			return new VirtualReferencePartitionIndex();
		}

		final String indexDescription = "reference `" + referenceName + "` of entity index `" + entityIndexId + "`";
		final VirtualReferencePartitionIndex index;
		if (part.isPaged()) {
			// fetch each live leaf page by its `pack(streamId, pageSequence)` key, then reassemble the tree with its
			// persisted leaf boundaries
			final int streamId = keyCompressor.getId(
				new VirtualReferencePartitionLeafStreamKey(entityIndexId, referenceName)
			);
			final int[] orderedPageSequences = Objects.requireNonNull(part.getLeafPageSequences());
			final long[][] perPageKeys = new long[orderedPageSequences.length][];
			final long[][] perPagePayloads = new long[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final int pageSequence = orderedPageSequences[i];
				final VirtualReferencePartitionIndexLeafPagePart leaf = service.getStoragePart(
					catalogVersion,
					VirtualReferencePartitionIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
					VirtualReferencePartitionIndexLeafPagePart.class
				);
				isPremiseValid(
					leaf != null,
					() -> "Leaf page " + pageSequence + " of the virtual reference partition index for " +
						indexDescription + " was not found in persistent storage!"
				);
				perPageKeys[i] = leaf.getKeys();
				perPagePayloads[i] = leaf.getPayloads();
			}
			index = VirtualReferencePartitionIndex.fromPersistedPages(
				indexDescription, orderedPageSequences, perPageKeys, perPagePayloads,
				part.getHighWaterPageSequence(), part.getTupleIds(), part.getTuples(), part.getSortValueSlots(),
				part.getChainPredecessorSlots(), normalizerProvider
			);
		} else {
			index = new VirtualReferencePartitionIndex(
				indexDescription,
				Objects.requireNonNull(part.getKeys()), Objects.requireNonNull(part.getPayloads()),
				part.getTupleIds(), part.getTuples(), part.getSortValueSlots(),
				part.getChainPredecessorSlots(), normalizerProvider
			);
		}
		return index;
	}

}
