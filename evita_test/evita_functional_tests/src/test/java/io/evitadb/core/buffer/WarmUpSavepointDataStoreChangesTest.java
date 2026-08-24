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

import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Scope;
import io.evitadb.index.Index;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.IndexKey;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link DataStoreChanges} participates in a {@link WarmUpSavepoint} — the WARM_UP (bulk indexing)
 * counterpart of the transactional per-entity savepoint. On that path there is no transaction and therefore no
 * maintainer to snapshot diff layers, so the layer registers itself with the open savepoint on the first write-touch
 * and is rewound from its own undo journal when the bracketed entity mutation fails.
 *
 * Two fields carry revertable state and both are exercised here: the dirty-index bookkeeping (populated as the index
 * executor marks indexes dirty) and the trapped storage-part cache. Lightweight stand-ins are used for the index and
 * the storage part — the layer tracks only *which* indexes are dirty, by reference, never their contents. The two
 * fields are read reflectively because neither has a non-destructive accessor; the only public drain
 * ({@code popTrappedUpdates}) is the flush, which would consume exactly the state under assertion.
 *
 * The deterministic counterpart of {@code LongRunningSavepointDataStoreChangesTest}, which fuzzes the same layer
 * against a maintainer-driven transactional savepoint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(TRANSACTION)
@DisplayName("DataStoreChanges under a warm-up savepoint")
class WarmUpSavepointDataStoreChangesTest {
	private static final long CATALOG_VERSION = 1L;
	private DataStoreChanges dataStoreChanges;

	@BeforeEach
	void setUp() {
		@SuppressWarnings("unchecked") final StoragePartPersistenceService<StorageDescriptor> persistenceService =
			Mockito.mock(StoragePartPersistenceService.class);
		// the trapped-removal path self-skips a part the storage never held, so the stub must claim it does
		Mockito.when(persistenceService.containsStoragePart(Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
			.thenReturn(true);
		this.dataStoreChanges = new DataStoreChanges(persistenceService);
	}

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("Dirty-index bookkeeping")
	class DirtyIndexBookkeeping {

		@Test
		@DisplayName("Indexes registered dirty inside the savepoint are dropped on rollback")
		void shouldRevertDirtyIndexRegistrationOnRollback() {
			markIndexDirty(1);
			assertEquals(List.of(1), dirtyIndexIds());

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			markIndexDirty(2);
			markIndexDirty(3);
			assertEquals(
				List.of(1, 2, 3), dirtyIndexIds(),
				"The test would be vacuous without an in-savepoint change."
			);

			savepoint.rollback();
			assertEquals(
				List.of(1), dirtyIndexIds(),
				"Only the indexes registered before the savepoint may survive its rollback."
			);
		}

		@Test
		@DisplayName("Indexes registered dirty inside the savepoint are kept on commit")
		void shouldKeepDirtyIndexRegistrationOnCommit() {
			markIndexDirty(1);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			markIndexDirty(2);
			savepoint.commit();

			assertEquals(List.of(1, 2), dirtyIndexIds(), "Commit must keep the savepoint's registrations.");
		}

		@Test
		@DisplayName("Re-registering an index already dirty before the savepoint changes nothing")
		void shouldLeaveAlreadyDirtyIndexUntouched() {
			markIndexDirty(1);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			markIndexDirty(1);
			markIndexDirty(2);
			savepoint.rollback();

			assertEquals(List.of(1), dirtyIndexIds());
		}

		@Test
		@DisplayName("A registration made through the by-primary-key entry point is reverted too")
		void shouldRevertRegistrationMadeByPrimaryKey() {
			markIndexDirty(1);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.getIndexForModification(
				2, id -> new StubIndex(new StubIndexKey(id))
			);
			assertEquals(List.of(1, 2), dirtyIndexIds());

			savepoint.rollback();
			assertEquals(List.of(1), dirtyIndexIds());
		}
	}

	@Nested
	@DisplayName("Trapped storage parts")
	class TrappedStorageParts {

		@Test
		@DisplayName("Parts trapped inside the savepoint are dropped on rollback")
		void shouldRevertTrappedPartsOnRollback() {
			trapPart(1);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			trapPart(2);
			trapPart(3);
			assertEquals(List.of(1L, 2L, 3L), trappedPartKeys());

			savepoint.rollback();
			assertEquals(List.of(1L), trappedPartKeys(), "Only the parts trapped before the savepoint may survive.");
		}

		@Test
		@DisplayName("Parts trapped inside the savepoint are kept on commit")
		void shouldKeepTrappedPartsOnCommit() {
			trapPart(1);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			trapPart(2);
			savepoint.commit();

			assertEquals(List.of(1L, 2L), trappedPartKeys(), "Commit must keep the savepoint's trapped parts.");
		}

		@Test
		@DisplayName("A part overwritten by a trapped removal is restored by value")
		void shouldRestoreOverwrittenPartOnRollback() {
			trapPart(1);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.trapRemoveStoragePart(
				CATALOG_VERSION, 1L, StubStoragePart.class
			);
			assertInstanceOf(
				DataStoreChanges.RemovedStoragePart.class, trappedPart(StubStoragePart.class, 1L),
				"The test would be vacuous unless the removal really overwrote the trapped part."
			);

			savepoint.rollback();
			assertInstanceOf(
				StubStoragePart.class, trappedPart(StubStoragePart.class, 1L),
				"Rollback must restore the trapped part itself, not merely its key."
			);
		}

		@Test
		@DisplayName("A cache first populated inside the savepoint is emptied again on rollback")
		void shouldRevertToAbsentCacheOnRollback() {
			// nothing was trapped before the savepoint, so the whole cache is state created within it
			assertNull(trappedChanges(), "The cache must start out unallocated for this test to mean anything.");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			trapPart(1);
			savepoint.rollback();

			assertNull(trappedChanges(), "Rollback must return the cache to its unallocated pre-savepoint state.");
		}
	}

	@Nested
	@DisplayName("Interaction with the enclosing bracket")
	class BracketInteraction {

		@Test
		@DisplayName("Mutations made with no savepoint open are not revertable")
		void shouldNotRecordAnythingWithoutOpenSavepoint() {
			markIndexDirty(1);
			trapPart(1);

			// opening a savepoint AFTER the fact must not retroactively capture anything
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			savepoint.rollback();

			assertEquals(List.of(1), dirtyIndexIds());
			assertEquals(List.of(1L), trappedPartKeys());
		}

		@Test
		@DisplayName("A second savepoint reverts only its own changes")
		void shouldRevertOnlyTheChangesOfTheCurrentSavepoint() {
			final WarmUpSavepoint first = WarmUpSavepoint.open();
			markIndexDirty(1);
			first.commit();

			final WarmUpSavepoint second = WarmUpSavepoint.open();
			markIndexDirty(2);
			second.rollback();

			assertEquals(
				List.of(1), dirtyIndexIds(),
				"A committed savepoint's changes must be out of reach of the next savepoint's rollback."
			);
		}
	}

	/**
	 * Registers a stand-in index under the given id as dirty, the way the index executor does on every modification.
	 *
	 * @param id the marker id identifying both the index and its key
	 */
	private void markIndexDirty(int id) {
		final StubIndexKey key = new StubIndexKey(id);
		this.dataStoreChanges.getOrCreateIndexForModification(key, StubIndex::new);
	}

	/**
	 * Traps a stand-in storage part under the given primary key.
	 *
	 * @param primaryKey the storage-part primary key
	 */
	private void trapPart(int primaryKey) {
		this.dataStoreChanges.trapPutStoragePart(new StubStoragePart((long) primaryKey));
	}

	/**
	 * Reads the ids of the currently dirty indexes, sorted.
	 *
	 * @return the sorted marker ids of the dirty indexes
	 */
	@Nonnull
	private List<Integer> dirtyIndexIds() {
		@SuppressWarnings("unchecked") final Map<IndexKey, ?> dirtyIndexes =
			(Map<IndexKey, ?>) readField("dirtyEntityIndexes");
		final List<Integer> ids = new ArrayList<>(dirtyIndexes.size());
		for (final IndexKey key : dirtyIndexes.keySet()) {
			ids.add(((StubIndexKey) key).id());
		}
		ids.sort(Integer::compareTo);
		return ids;
	}

	/**
	 * Reads the primary keys of the currently trapped storage parts, sorted.
	 *
	 * @return the sorted trapped storage-part primary keys
	 */
	@Nonnull
	private List<Long> trappedPartKeys() {
		final Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> trapped = trappedChanges();
		if (trapped == null) {
			return List.of();
		}
		final List<Long> keys = new ArrayList<>();
		for (final LongObjectMap<StoragePart> perType : trapped.values()) {
			for (final LongObjectCursor<StoragePart> cursor : perType) {
				keys.add(cursor.key);
			}
		}
		keys.sort(Long::compareTo);
		return keys;
	}

	/**
	 * Reads the single trapped storage part registered under the given type and primary key.
	 *
	 * @param containerType the storage-part type the part is filed under
	 * @param primaryKey    the storage-part primary key
	 * @return the trapped part, or `null` when none is registered
	 */
	private StoragePart trappedPart(@Nonnull Class<? extends StoragePart> containerType, long primaryKey) {
		final Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> trapped = trappedChanges();
		if (trapped == null) {
			return null;
		}
		final LongObjectMap<StoragePart> perType = trapped.get(containerType);
		return perType == null ? null : perType.get(primaryKey);
	}

	/**
	 * Reads the trapped-changes cache, which is `null` until the first part is trapped.
	 *
	 * @return the trapped-changes structure, or `null` when it has not been allocated
	 */
	@SuppressWarnings("unchecked")
	private Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> trappedChanges() {
		return (Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>>) readField("trappedChanges");
	}

	/**
	 * Reads a private field of the layer under test. Neither revertable field has a non-destructive accessor, and the
	 * one public drain is the flush — which would consume exactly the state being asserted.
	 *
	 * @param fieldName the declared field name on {@link DataStoreChanges}
	 * @return the field's current value
	 */
	private Object readField(@Nonnull String fieldName) {
		try {
			final Field field = DataStoreChanges.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.get(this.dataStoreChanges);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read `" + fieldName + "` of the layer under test!", e);
		}
	}

	/**
	 * Marker index key identified by an integer; the layer tracks only which keys are dirty.
	 *
	 * @param id the key id
	 */
	private record StubIndexKey(int id) implements IndexKey {
		@Nonnull
		@Override
		public Scope scope() {
			return Scope.LIVE;
		}
	}

	/**
	 * Layer-less stand-in {@link Index} stored by reference in the dirty-index map; its contents are never read.
	 *
	 * @param key the key this index is filed under
	 */
	private record StubIndex(@Nonnull StubIndexKey key) implements Index<StubIndexKey> {
		/**
		 * One holder for every stub: nothing here plans a query or applies an entity mutation, so it is never recorded
		 * into and never read. Kept static rather than made a record component so that two stubs with the same key stay
		 * equal, which is what the dirty-index bookkeeping under test compares.
		 */
		private static final IndexActivity NO_ACTIVITY = new IndexActivity();

		@Nonnull
		@Override
		public StubIndexKey getIndexKey() {
			return this.key;
		}

		@Nonnull
		@Override
		public IndexActivity getActivity() {
			return NO_ACTIVITY;
		}

		@Override
		public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
			// no-op: this stub holds no modifiable storage parts
		}
	}

	/**
	 * Minimal {@link StoragePart} identified by its storage-part primary key.
	 *
	 * @param pk the storage-part primary key
	 */
	private record StubStoragePart(@Nonnull Long pk) implements StoragePart {
		@Serial private static final long serialVersionUID = 1L;

		@Nonnull
		@Override
		public Long getStoragePartPK() {
			return this.pk;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.pk;
		}
	}

}
