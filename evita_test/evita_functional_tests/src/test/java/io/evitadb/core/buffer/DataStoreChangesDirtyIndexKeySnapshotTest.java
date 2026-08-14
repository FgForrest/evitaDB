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

import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.IndexKey;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the dirty-index-key snapshot contract that the commit-time merge prune
 * ({@code EntityCollection#pruneMergeIndexes}) rests on: the flush hands the merge the set of indexes it just
 * persisted, and the merge carries everything else across the catalog version by reference.
 *
 * The snapshot alone cannot carry its own validity — an EMPTY set is a legitimate outcome (a transaction may change a
 * collection's schema without touching a single index), so it is indistinguishable from "the flush never ran". That
 * ambiguity is the dangerous one: pruning against a snapshot that was never taken would treat every index as unchanged
 * and silently orphan the diff layers of the ones that did change, surfacing only as a downstream
 * {@code StaleTransactionMemoryException} that suspends the catalog. These tests pin the positive validity marker that
 * turns that into a named failure at the point of misuse.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("DataStoreChanges dirty-index-key snapshot handed from the flush to the trunk merge")
class DataStoreChangesDirtyIndexKeySnapshotTest {
	private static final EntityIndexKey LIVE_GLOBAL_KEY = new EntityIndexKey(EntityIndexType.GLOBAL);
	private static final EntityIndexKey ARCHIVED_GLOBAL_KEY = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED);

	/**
	 * A buffer constructed with `capturesDirtyIndexKeys = true` — i.e. one whose flush feeds a merge that PRUNES on
	 * the snapshot, which today is exactly an {@code EntityCollection} diff layer. The flag is passed positionally, so
	 * this comment is what keeps the intent readable if the constructor parameter is ever renamed again.
	 */
	private final DataStoreChanges layer = new DataStoreChanges(mockPersistenceService(), true);

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
	 * Creates a layer-less {@link EntityIndex} stub — the dirty-index maps store it purely by reference and the flush
	 * only calls its (no-op on a mock) storage-part collection hooks.
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
	@DisplayName("the merge cannot consume a snapshot before any flush has taken one")
	void shouldRefuseToHandOutASnapshotThatNoFlushHasTaken() {
		this.layer.getOrCreateIndexForModification(LIVE_GLOBAL_KEY, key -> entityIndexStub(1));

		// the index IS dirty, but no flush has run - the merge must not be allowed to conclude "nothing changed"
		assertThrows(
			GenericEvitaInternalError.class,
			this.layer::popLastCommittedDirtyIndexKeys,
			"Consuming a snapshot no flush has taken must fail loudly rather than prune against an empty set!"
		);
	}

	@Test
	@DisplayName("a flush hands the merge exactly the keys of the indexes it persisted")
	void shouldHandOverTheKeysOfTheIndexesTheFlushPersisted() {
		this.layer.getOrCreateIndexForModification(LIVE_GLOBAL_KEY, key -> entityIndexStub(1));
		this.layer.getOrCreateIndexForModification(ARCHIVED_GLOBAL_KEY, key -> entityIndexStub(2));

		this.layer.popTrappedUpdates();

		assertEquals(
			Set.of(LIVE_GLOBAL_KEY, ARCHIVED_GLOBAL_KEY),
			this.layer.popLastCommittedDirtyIndexKeys(),
			"The merge must see exactly the index keys the flush persisted!"
		);
	}

	@Test
	@DisplayName("an empty snapshot is still a valid snapshot when the transaction dirtied no index")
	void shouldHandOverAnEmptySnapshotWhenNoIndexWasDirty() {
		this.layer.popTrappedUpdates();

		assertTrue(
			this.layer.popLastCommittedDirtyIndexKeys().isEmpty(),
			"A transaction that dirtied no index must yield an empty - but available - snapshot!"
		);
	}

	@Test
	@DisplayName("the snapshot is one-shot, so a second merge cannot silently prune against an emptied set")
	void shouldRefuseToHandOutTheSameSnapshotTwice() {
		this.layer.getOrCreateIndexForModification(LIVE_GLOBAL_KEY, key -> entityIndexStub(1));
		this.layer.popTrappedUpdates();

		assertEquals(Set.of(LIVE_GLOBAL_KEY), this.layer.popLastCommittedDirtyIndexKeys());
		assertThrows(
			GenericEvitaInternalError.class,
			this.layer::popLastCommittedDirtyIndexKeys,
			"A second consumption must fail loudly - the snapshot describes one flush, not all of them!"
		);
	}

	@Test
	@DisplayName("a second flush before the merge unions rather than narrowing the snapshot")
	void shouldUnionTheKeysOfTwoFlushesThatPrecedeASingleMerge() {
		// first flush persists (and resets) the LIVE global...
		this.layer.getOrCreateIndexForModification(LIVE_GLOBAL_KEY, key -> entityIndexStub(1));
		this.layer.popTrappedUpdates();
		// ...a second flush before the merge sees only the ARCHIVED one, because the dirty map was already reset. A
		// plain replacement here would drop the LIVE key and the merge would carry - and thereby orphan the diff layer
		// of - an index the first flush had persisted.
		this.layer.getOrCreateIndexForModification(ARCHIVED_GLOBAL_KEY, key -> entityIndexStub(2));
		this.layer.popTrappedUpdates();

		assertEquals(
			Set.of(LIVE_GLOBAL_KEY, ARCHIVED_GLOBAL_KEY),
			this.layer.popLastCommittedDirtyIndexKeys(),
			"Both flushes' keys must survive to the single merge that consumes them!"
		);
	}

	@Test
	@DisplayName("the shared non-layer buffer captures nothing - a warm-up flush pays no snapshot cost")
	void shouldNotCaptureASnapshotOnTheSharedNonLayerBuffer() {
		final DataStoreChanges sharedBuffer = new DataStoreChanges(mockPersistenceService());
		sharedBuffer.getOrCreateIndexForModification(LIVE_GLOBAL_KEY, key -> entityIndexStub(1));

		sharedBuffer.popTrappedUpdates();

		// the shared buffer behind a warm-up / non-transactional flush is never merged, so it must not pay the copy -
		// and must not pretend to hold a snapshot either
		assertThrows(
			GenericEvitaInternalError.class,
			sharedBuffer::popLastCommittedDirtyIndexKeys,
			"The shared buffer must not capture a snapshot - nothing ever merges it!"
		);
	}

	@Test
	@DisplayName("the snapshot is decoupled from the live dirty map the next transaction fills")
	void shouldNotLetPostFlushMutationsLeakIntoTheAlreadyTakenSnapshot() {
		this.layer.getOrCreateIndexForModification(LIVE_GLOBAL_KEY, key -> entityIndexStub(1));
		this.layer.popTrappedUpdates();
		// a mutation arriving after the flush belongs to the NEXT catalog version and must not retroactively appear in
		// the snapshot the merge is about to partition on
		this.layer.getOrCreateIndexForModification(ARCHIVED_GLOBAL_KEY, key -> entityIndexStub(2));

		final Set<IndexKey> snapshot = this.layer.popLastCommittedDirtyIndexKeys();

		assertEquals(Set.of(LIVE_GLOBAL_KEY), snapshot, "The snapshot must be a copy, not a live view of the dirty map!");
	}

}
