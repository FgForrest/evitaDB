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

package io.evitadb.core.buffer;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link DataStoreChanges} retires an {@link EntityIndex} dropped through
 * {@link DataStoreChanges#removeIndex}: the dropped instance leaves both dirty-index maps, so no lookup or write
 * acquisition by its primary key reaches it again, and the removals reclaiming its persisted footprint are emitted
 * ahead of every part a dirty index writes, so that an index installed under the same primary key within the same
 * flush window survives the flush.
 *
 * Real indexes and a real map-backed store are used throughout: the assertions are about which instance a lookup
 * returns and which records the store holds once the flushed parts are applied, and neither can be observed through a
 * stand-in that only answers calls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(INDEXING)
@DisplayName("DataStoreChanges retirement of a dropped index")
class DataStoreChangesIndexRetirementTest {
	private static final long CATALOG_VERSION = 1L;
	private static final int INDEX_PRIMARY_KEY = 7;
	private static final String ENTITY_TYPE = "product";
	private static final EntityIndexKey REDUCED_INDEX_KEY = new EntityIndexKey(
		EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
		new RepresentativeReferenceKey(new ReferenceKey("brand", 1))
	);
	private static final EntityIndexKey GLOBAL_INDEX_KEY = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);

	/**
	 * The store the flushed parts are applied to, standing in for the persistence service of the collection.
	 */
	private final InMemoryStoragePartPersistenceService store = new InMemoryStoragePartPersistenceService();
	private final DataStoreChanges changes = new DataStoreChanges(this.store);

	@Test
	@DisplayName("a dropped index is no longer returned by a lookup by its primary key")
	void shouldForgetDroppedIndexByPrimaryKey() {
		final ReducedEntityIndex index = new ReducedEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, REDUCED_INDEX_KEY);
		this.changes.getOrCreateIndexForModification(REDUCED_INDEX_KEY, key -> index);
		assertSame(
			index, this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"The test would be vacuous unless the index was registered under its primary key first."
		);

		// the origin collection has dropped the index as well, so its own lookup by primary key answers nothing
		this.changes.removeIndex(CATALOG_VERSION, REDUCED_INDEX_KEY, key -> null);

		assertNull(
			this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"A lookup by primary key must not return the dropped index instance."
		);
		assertNull(
			this.changes.getIndexIfExists(REDUCED_INDEX_KEY, key -> null),
			"A lookup by key must not return the dropped index instance."
		);
	}

	@Test
	@DisplayName("dropping an index leaves a different index registered under the same primary key in place")
	void shouldLeaveAnotherIndexUnderTheSamePrimaryKeyInPlace() {
		final EntityIndexKey otherKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey("brand", 2))
		);
		final ReducedEntityIndex other = new ReducedEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, otherKey);
		this.changes.getOrCreateIndexForModification(otherKey, key -> other);
		assertSame(
			other, this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"The test would be vacuous unless the other index was registered under the shared primary key first."
		);

		// the origin collection hands over a clean index of its own that happens to share the primary key
		final ReducedEntityIndex dropped = new ReducedEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, REDUCED_INDEX_KEY);
		this.changes.removeIndex(CATALOG_VERSION, REDUCED_INDEX_KEY, key -> dropped);

		assertSame(
			other, this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"The primary key entry of a different index must survive the drop."
		);
		assertSame(
			other, this.changes.getIndexIfExists(otherKey, key -> null),
			"The key entry of a different index must survive the drop."
		);
	}

	@Test
	@DisplayName("a write acquisition by the primary key of a dropped index is refused")
	void shouldRefuseWriteAcquisitionOfDroppedPk() {
		final ReducedEntityIndex index = new ReducedEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, REDUCED_INDEX_KEY);
		this.changes.getOrCreateIndexForModification(REDUCED_INDEX_KEY, key -> index);
		this.changes.removeIndex(CATALOG_VERSION, REDUCED_INDEX_KEY, key -> null);

		// the acquisition falls through to the origin collection, which no longer holds the index - so it must fail
		// rather than hand the dropped instance out for modification and register it dirty again
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class,
			() -> this.changes.getIndexForModification(INDEX_PRIMARY_KEY, pk -> null),
			"A write acquisition by the primary key of a dropped index must not revive the dropped instance."
		);
		assertTrue(
			error.getMessage().contains(String.valueOf(INDEX_PRIMARY_KEY)),
			"The refusal must name the primary key that was asked for: " + error.getMessage()
		);
	}

	@Test
	@DisplayName("the dropped footprint is emitted before the parts of an index reinstalled under the same primary key")
	void shouldEmitDroppedFootprintBeforeReinstalledParts() {
		// a global index is used because its hierarchy root is a sub-index root that persists without any schema,
		// which makes the drop stage a deferred root removal next to the manifest and membership removals
		final GlobalEntityIndex dropped = new GlobalEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, GLOBAL_INDEX_KEY);
		dropped.insertPrimaryKeyIfMissing(10);
		dropped.addNode(10, null);
		persist(dropped);
		assertNotNull(
			storedHierarchy(), "The test would be vacuous unless the dropped index had a persisted footprint."
		);

		// the origin collection hands the clean (no longer dirty) index over when it is dropped
		this.changes.removeIndex(CATALOG_VERSION, GLOBAL_INDEX_KEY, key -> dropped);
		final GlobalEntityIndex reinstalled = new GlobalEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, GLOBAL_INDEX_KEY);
		this.changes.getOrCreateIndexForModification(GLOBAL_INDEX_KEY, key -> reinstalled);
		reinstalled.insertPrimaryKeyIfMissing(20);
		reinstalled.addNode(20, null);

		final List<StoragePart> parts = drain();
		int lastRemoval = -1;
		int firstWrite = parts.size();
		int removals = 0;
		for (int i = 0; i < parts.size(); i++) {
			if (isRemoval(parts.get(i))) {
				lastRemoval = i;
				removals++;
			} else {
				firstWrite = Math.min(firstWrite, i);
			}
		}
		assertEquals(
			3, removals,
			"The drop must stage the removal of the manifest, the membership bitmaps and the hierarchy root: " + parts
		);
		assertTrue(
			lastRemoval < firstWrite,
			"Every removal of the dropped footprint must precede every part of the reinstalled index: " + parts
		);

		apply(parts);
		final EntityIdsStoragePart storedIds = (EntityIdsStoragePart) this.store.record(
			EntityIdsStoragePart.class, INDEX_PRIMARY_KEY
		);
		assertNotNull(storedIds, "The membership bitmaps of the reinstalled index must survive the flush.");
		assertArrayEquals(
			new int[]{20}, storedIds.getEntityIds().getArray(),
			"The stored membership must be the reinstalled index's, not the dropped one's."
		);
		assertNotNull(
			this.store.record(EntityIndexStoragePart.class, INDEX_PRIMARY_KEY),
			"The manifest of the reinstalled index must survive the flush."
		);
		final HierarchyIndexStoragePart storedHierarchy = storedHierarchy();
		assertNotNull(storedHierarchy, "The hierarchy root of the reinstalled index must survive the flush.");
		assertArrayEquals(
			new int[]{20}, storedHierarchy.getRoots(),
			"The stored hierarchy must be the reinstalled index's, not the dropped one's."
		);
	}

	@Test
	@DisplayName("the part of the dropped footprint the reinstalled index does not overwrite is reclaimed")
	void shouldReclaimDroppedFootprintNotOverwrittenByReinstalledIndex() {
		final GlobalEntityIndex dropped = new GlobalEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, GLOBAL_INDEX_KEY);
		dropped.insertPrimaryKeyIfMissing(10);
		dropped.addNode(10, null);
		persist(dropped);
		assertNotNull(storedHierarchy(), "The test would be vacuous unless the dropped index persisted a hierarchy.");

		this.changes.removeIndex(CATALOG_VERSION, GLOBAL_INDEX_KEY, key -> dropped);
		// the reinstalled index has members but no hierarchy, so nothing it writes supersedes the old hierarchy root
		final GlobalEntityIndex reinstalled = new GlobalEntityIndex(INDEX_PRIMARY_KEY, ENTITY_TYPE, GLOBAL_INDEX_KEY);
		this.changes.getOrCreateIndexForModification(GLOBAL_INDEX_KEY, key -> reinstalled);
		reinstalled.insertPrimaryKeyIfMissing(20);
		apply(drain());

		assertNull(
			storedHierarchy(),
			"The hierarchy root of the dropped index must be reclaimed - the reinstalled index has none to replace it."
		);
		assertNotNull(
			this.store.record(EntityIndexStoragePart.class, INDEX_PRIMARY_KEY),
			"The manifest of the reinstalled index must survive the flush."
		);
	}

	/**
	 * Registers the index dirty, flushes it and applies the flushed parts to {@link #store}, leaving the index with a
	 * persisted footprint and its change-detection baselines advanced to it — the state of an index flushed by an
	 * earlier transaction or an earlier warm-up flush.
	 *
	 * @param index the index to persist
	 */
	private void persist(@Nonnull EntityIndex index) {
		this.changes.getOrCreateIndexForModification(index.getIndexKey(), key -> index);
		apply(drain());
	}

	/**
	 * Drains the pending changes in the order the store receives them.
	 *
	 * @return all storage parts the flush pipeline would receive, in iteration order
	 */
	@Nonnull
	private List<StoragePart> drain() {
		final List<StoragePart> drained = new ArrayList<>(16);
		final Iterator<StoragePart> it = this.changes.popTrappedUpdates().getTrappedChangesIterator();
		while (it.hasNext()) {
			drained.add(it.next());
		}
		return drained;
	}

	/**
	 * Applies the drained parts to {@link #store} one by one, exactly as the store-side flush drain does: a removal
	 * drops the record it names, every other part is written.
	 *
	 * @param parts the drained parts in iteration order
	 */
	private void apply(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof RemovedStoragePart removedStoragePart) {
				this.store.removeStoragePart(
					CATALOG_VERSION, removedStoragePart.storagePartPK(), removedStoragePart.containerType()
				);
			} else if (part instanceof DeferredRemovalStoragePart deferredRemoval) {
				final long removedPartPK = deferredRemoval.computeUniquePartIdAndSet(
					this.store.getReadOnlyKeyCompressor()
				);
				this.store.removeStoragePart(CATALOG_VERSION, removedPartPK, deferredRemoval.removedContainerType());
			} else {
				this.store.putStoragePart(CATALOG_VERSION, part);
			}
		}
	}

	/**
	 * @param part a drained part
	 * @return `true` when the part instructs the store to remove a record rather than to write one
	 */
	private static boolean isRemoval(@Nonnull StoragePart part) {
		return part instanceof RemovedStoragePart || part instanceof DeferredRemovalStoragePart;
	}

	/**
	 * @return the hierarchy root the store holds under {@link #INDEX_PRIMARY_KEY}, or `null` when it holds none
	 */
	@Nullable
	private HierarchyIndexStoragePart storedHierarchy() {
		final StoragePart part = this.store.record(HierarchyIndexStoragePart.class, INDEX_PRIMARY_KEY);
		return part == null ? null : assertInstanceOf(HierarchyIndexStoragePart.class, part);
	}

}
