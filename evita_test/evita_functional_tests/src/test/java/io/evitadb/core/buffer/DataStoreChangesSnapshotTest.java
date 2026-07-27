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

import io.evitadb.core.buffer.DataStoreChanges.DataStoreChangesMemento;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the {@link DataStoreChanges} savepoint contract ({@code snapshot()} / {@code restore(...)}) around the
 * dirty-index maps, with a focus on the interplay between the by-key map ({@code dirtyEntityIndexes}) and the by-pk
 * map ({@code dirtyEntityIndexesByPk}). The two maps must be rewound to exactly the snapshot-time state as a
 * consistent PAIR — {@code removeIndex} drops only the by-key entry (never the by-pk one), so a by-pk entry may
 * legitimately exist without its by-key twin, and a restore must preserve that exact asymmetry.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("DataStoreChanges savepoint snapshot/restore of the dirty-index maps")
class DataStoreChangesSnapshotTest {
	private static final int INDEX_PRIMARY_KEY = 1;
	/**
	 * Stand-in for one of the deferred removal instructions a dropped index stages to reclaim its persisted footprint -
	 * the concrete part type is irrelevant here, only whether it survives to the flush pipeline.
	 */
	private static final StoragePart FOOTPRINT_REMOVAL = new RemovedStoragePart(EntityIndexStoragePart.class, 42L);

	private final DataStoreChanges changes = new DataStoreChanges(mockPersistenceService());
	private final EntityIndexKey indexKey = new EntityIndexKey(EntityIndexType.GLOBAL);

	/**
	 * Creates a throwaway persistence-service stub — none of its methods are exercised by the dirty-index paths under
	 * test.
	 *
	 * @return a Mockito stub of the persistence service
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static StoragePartPersistenceService<StorageDescriptor> mockPersistenceService() {
		return Mockito.mock(StoragePartPersistenceService.class);
	}

	/**
	 * Creates a layer-less {@link EntityIndex} stub with the given primary key — the dirty-index maps store it purely
	 * by reference, so no real index behavior is needed.
	 *
	 * @param primaryKey the primary key the stub reports
	 * @return an {@link EntityIndex} stub
	 */
	@Nonnull
	private static EntityIndex entityIndexStub(int primaryKey) {
		final EntityIndex index = Mockito.mock(EntityIndex.class);
		Mockito.when(index.getPrimaryKey()).thenReturn(primaryKey);
		return index;
	}

	@Test
	@DisplayName("an index first created inside the savepoint window is dropped from both maps on restore")
	void shouldDropIndexCreatedInsideSavepointWindowFromBothMapsOnRestore() {
		final EntityIndex index = entityIndexStub(INDEX_PRIMARY_KEY);

		final DataStoreChangesMemento memento = this.changes.snapshot();
		this.changes.getOrCreateIndexForModification(this.indexKey, key -> index);
		this.changes.restore(memento);

		assertNull(
			this.changes.getIndexIfExists(this.indexKey, key -> null),
			"the by-key entry created inside the savepoint window must be dropped on restore"
		);
		assertNull(
			this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"the by-pk entry created inside the savepoint window must be dropped on restore"
		);
	}

	@Test
	@DisplayName("a by-pk entry that predates the snapshot survives a rollback of an in-window re-creation")
	void shouldKeepPreSnapshotByPkEntryWhenIndexIsRecreatedInsideSavepointWindow() {
		final EntityIndex index = entityIndexStub(INDEX_PRIMARY_KEY);

		// pre-snapshot: create the dirty index, then remove it by key — removeIndex deliberately leaves the by-pk
		// entry in place, so the snapshot-time state is: by-key ABSENT, by-pk PRESENT
		this.changes.getOrCreateIndexForModification(this.indexKey, key -> index);
		this.changes.removeIndex(0L, this.indexKey, key -> null);
		assertSame(
			index, this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"self-check: removeIndex must leave the by-pk entry in place"
		);

		final DataStoreChangesMemento memento = this.changes.snapshot();
		// in-window: the same index key is re-created (e.g. another entity re-populates the just-dropped index)
		this.changes.getOrCreateIndexForModification(this.indexKey, key -> index);
		this.changes.restore(memento);

		assertNull(
			this.changes.getIndexIfExists(this.indexKey, key -> null),
			"the by-key entry re-created inside the savepoint window must be dropped on restore"
		);
		assertSame(
			index, this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"the by-pk entry that already existed at snapshot time must survive the restore"
		);
	}

	@Test
	@DisplayName("both maps are rewound when an index is removed and re-created inside the savepoint window")
	void shouldRestoreBothMapsWhenIndexIsRemovedAndRecreatedInsideSavepointWindow() {
		final EntityIndex original = entityIndexStub(INDEX_PRIMARY_KEY);

		// pre-snapshot: the dirty index exists in both maps
		this.changes.getOrCreateIndexForModification(this.indexKey, key -> original);

		final DataStoreChangesMemento memento = this.changes.snapshot();
		// in-window: drop the index and re-create it under the same key with a fresh instance (same pk) — the exact
		// shape of an entity update that removes the last item of an index and then re-populates it
		this.changes.removeIndex(0L, this.indexKey, key -> null);
		final EntityIndex recreated = entityIndexStub(INDEX_PRIMARY_KEY);
		this.changes.getOrCreateIndexForModification(this.indexKey, key -> recreated);
		this.changes.restore(memento);

		assertSame(
			original, this.changes.getIndexIfExists(this.indexKey, key -> null),
			"the by-key entry must be rewound to the snapshot-time index instance"
		);
		assertSame(
			original, this.changes.getIndexIfExists(INDEX_PRIMARY_KEY, pk -> null),
			"the by-pk entry must be rewound to the snapshot-time index instance"
		);
	}

	@Test
	@DisplayName("footprint removals staged by a dropped index are drained when the drop is not rolled back")
	void shouldDrainFootprintRemovalsStagedByDroppedIndex() {
		final EntityIndex index = entityIndexStubStagingFootprintRemoval();

		this.changes.getOrCreateIndexForModification(this.indexKey, key -> index);
		this.changes.removeIndex(0L, this.indexKey, key -> null);

		assertEquals(
			List.of(FOOTPRINT_REMOVAL), drainTrappedChanges(),
			"the removal staged while dropping the index must reach the flush pipeline"
		);
	}

	@Test
	@DisplayName("footprint removals staged by a dropped index are discarded when the drop is rolled back")
	void shouldDiscardFootprintRemovalsStagedByDroppedIndexOnRestore() {
		final EntityIndex index = entityIndexStubStagingFootprintRemoval();

		this.changes.getOrCreateIndexForModification(this.indexKey, key -> index);

		final DataStoreChangesMemento memento = this.changes.snapshot();
		// in-window: the index is dropped, staging the removals that would reclaim its persisted footprint
		this.changes.removeIndex(0L, this.indexKey, key -> null);
		this.changes.restore(memento);

		// the index is alive again, so removing its persisted parts would silently corrupt it - the staged removals
		// must have been truncated by the undo journal
		assertSame(
			index, this.changes.getIndexIfExists(this.indexKey, key -> null),
			"self-check: the rolled-back drop must have reinstated the index"
		);
		assertEquals(
			List.of(), drainTrappedChanges(),
			"the removals staged by the rolled-back drop must never reach the flush pipeline"
		);
	}

	/**
	 * Drains the pending changes and materializes them into a list for assertion purposes.
	 *
	 * @return all storage parts the flush pipeline would receive
	 */
	@Nonnull
	private List<StoragePart> drainTrappedChanges() {
		final List<StoragePart> drained = new ArrayList<>(8);
		final Iterator<StoragePart> it = this.changes.popTrappedUpdates().getTrappedChangesIterator();
		while (it.hasNext()) {
			drained.add(it.next());
		}
		return drained;
	}

	/**
	 * Creates an {@link EntityIndex} stub that behaves as an index with a persisted footprint - dropping it stages
	 * exactly one removal instruction, which is what the savepoint rollback has to undo.
	 *
	 * @return an {@link EntityIndex} stub staging {@link #FOOTPRINT_REMOVAL} when its footprint is reclaimed
	 */
	@Nonnull
	private static EntityIndex entityIndexStubStagingFootprintRemoval() {
		final EntityIndex index = entityIndexStub(INDEX_PRIMARY_KEY);
		Mockito.doAnswer(invocation -> {
			invocation.getArgument(0, TrappedChanges.class).addChangeToStore(FOOTPRINT_REMOVAL);
			return null;
		}).when(index).emitFootprintRemovals(ArgumentMatchers.any());
		return index;
	}

}
