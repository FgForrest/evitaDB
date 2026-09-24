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
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.dataType.Scope;
import io.evitadb.index.Index;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.IndexKey;
import io.evitadb.core.buffer.InMemoryStoragePartPersistenceService.SimulatedWriteFailure;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link DataStoreChanges} participates in a {@link WarmUpSavepoint} — the WARM_UP (bulk indexing)
 * counterpart of the transactional per-entity savepoint. On that path there is no transaction and therefore no
 * maintainer to snapshot diff layers, so the layer registers itself with the open savepoint on the first write-touch
 * and is rewound from its own undo journal when the bracketed entity mutation fails.
 *
 * THREE kinds of state are revertable here and all three are exercised:
 *
 * 1. the dirty-index bookkeeping, populated as the index executor marks indexes dirty;
 * 2. the trapped storage-part cache, populated by the `trap*` entry points used by implicit (nested) mutations;
 * 3. the RECORDS a direct write leaves in the persistence service — what a ROOT mutation does, since it runs with
 *    `trapChanges == false`. These are not in-memory state and no memento restores them; each write captures the
 *    record's pre-image and pushes its absolute restore into the savepoint at the point of the write.
 *
 * The first two are read reflectively because neither has a non-destructive accessor; the only public drain
 * ({@code popTrappedUpdates}) is the flush, which would consume exactly the state under assertion. The third is read
 * from {@link InMemoryStoragePartPersistenceService}, a real map-backed store rather than a mock — a service that
 * merely answers calls cannot tell a record that was rewound from one that was never written, so it would report a
 * green result for a layer that leaves half-written entity bodies behind.
 *
 * Lightweight stand-ins are used for the index and the storage part — the layer tracks only *which* indexes are
 * dirty, by reference, never their contents.
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
	/**
	 * The key {@link UnkeyedStoragePart} is filed under once the store assigns it one. Fixed rather than generated,
	 * because the point of that stand-in is only that the key comes into existence INSIDE the write.
	 */
	private static final long STORE_ASSIGNED_PK = 42L;
	private DataStoreChanges dataStoreChanges;
	/**
	 * The store the layer under test writes through to — a real in-memory implementation rather than a mock, because
	 * these tests assert what the store HOLDS after a rollback, which a service that merely answers calls cannot say.
	 */
	private InMemoryStoragePartPersistenceService persistenceService;

	@BeforeEach
	void setUp() {
		this.persistenceService = new InMemoryStoragePartPersistenceService();
		this.dataStoreChanges = new DataStoreChanges(this.persistenceService);
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
	@DisplayName("Schema context of the pre-image read")
	class PreImageSchemaContext {

		/**
		 * Records whether an {@link EntitySchemaContext} was established at the moment the pre-image was read.
		 *
		 * The savepoint captures the pre-image of a record it is about to overwrite by READING it back, and reading a
		 * `ReferencesStoragePart` or a `PricesStoragePart` deserializes it, which resolves the entity schema from that
		 * thread-local context. The read happens on the WRITE path, which - unlike `EntityCollection`'s reader, that
		 * wraps every fetch - establishes no context of its own. A real store would throw here; this stand-in records
		 * the fact instead, so the test names the invariant rather than a serializer's symptom.
		 */
		static class SchemaContextRecordingService extends InMemoryStoragePartPersistenceService {
			/** `TRUE` / `FALSE` once a read has happened, `null` while none has. */
			@Nullable Boolean schemaContextWasLive;

			@Nullable
			@Override
			public <T extends StoragePart> T getStoragePart(
				long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
			) {
				try {
					EntitySchemaContext.getEntitySchema();
					this.schemaContextWasLive = Boolean.TRUE;
				} catch (RuntimeException ex) {
					this.schemaContextWasLive = Boolean.FALSE;
				}
				return super.getStoragePart(catalogVersion, storagePartPk, containerType);
			}
		}

		@Test
		@DisplayName("The pre-image is read inside the schema context its deserialization needs")
		void shouldReadPreImageInsideSchemaContext() {
			final SchemaContextRecordingService store = new SchemaContextRecordingService();
			final EntitySchema schema = EntitySchema._internalBuild("product");
			final DataStoreChanges changes = new DataStoreChanges(store, false, () -> schema);
			// seed the record, so the overwrite below has a pre-image to capture at all
			changes.putStoragePart(CATALOG_VERSION, new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			changes.putStoragePart(CATALOG_VERSION, new StubStoragePart(1L, "after"));
			savepoint.rollback();

			assertEquals(
				Boolean.TRUE, store.schemaContextWasLive,
				"The savepoint's pre-image read must run inside an EntitySchemaContext - without it every entity " +
					"carrying references or prices fails to deserialize on the commit path."
			);
		}

		@Test
		@DisplayName("A changeset with no schema supplier still reads its pre-image")
		void shouldReadPreImageWithoutSupplier() {
			final SchemaContextRecordingService store = new SchemaContextRecordingService();
			// the catalog-level changeset holds parts that carry neither references nor prices, so it is given no
			// supplier - and must keep working rather than failing for want of a context it does not need
			final DataStoreChanges changes = new DataStoreChanges(store, false, null);
			changes.putStoragePart(CATALOG_VERSION, new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			changes.putStoragePart(CATALOG_VERSION, new StubStoragePart(1L, "after"));
			savepoint.rollback();

			assertEquals(
				"before",
				store.getStoragePart(CATALOG_VERSION, 1L, StubStoragePart.class).payload(),
				"Rollback must restore the pre-image even when no schema supplier is configured."
			);
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
			// the trapped-removal path self-skips a part the store never held, so the record has to be there
			seedStore(new StubStoragePart(1L));
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

	@Nested
	@DisplayName("Storage parts written in the commit phase")
	class CommitPhaseStorageParts {

		@Test
		@DisplayName("Both the trapped and the written-through part are dropped on rollback")
		void shouldRevertPartsWrittenAfterTheFirstTouch() {
			// this is the ordering a real root entity mutation has: the index writes touch this layer FIRST (here,
			// an index registration), and the entity body storage parts are written much LATER, from
			// ContainerizedLocalMutationExecutor#commit, while the savepoint is still open. The two write modes are
			// rewound by different means and this asserts both: a TRAPPED part rides the memento (a journal POSITION
			// taken at the first touch, so it still covers everything pushed after it), while a part written THROUGH
			// to the store has no in-memory state to rewind and is put back from its own captured pre-image
			markIndexDirty(1);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			markIndexDirty(2);

			// ... the commit phase now writes the entity's storage parts through the same buffer
			trapPart(7);
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.putStoragePart(
				CATALOG_VERSION, new StubStoragePart(8L)
			);
			assertEquals(List.of(7L), trappedPartKeys(), "The test would be vacuous without an in-savepoint write.");
			assertNotNull(storedPart(8L), "The test would be vacuous without an in-savepoint write-through.");

			savepoint.rollback();
			assertEquals(
				List.of(1), dirtyIndexIds(),
				"The index registration made inside the savepoint must be rewound."
			);
			assertEquals(
				List.of(), trappedPartKeys(),
				"A storage part trapped in the commit phase must be rewound as well - it is journalled onto the same " +
					"position the first touch marked, not captured separately."
			);
			assertNull(
				storedPart(8L),
				"A storage part written THROUGH to the store in the commit phase must be rewound as well - the " +
					"memento covers no state outside this layer, so the write has to journal its own inverse."
			);
		}

		@Test
		@DisplayName("Both the trapped and the written-through part are kept on commit")
		void shouldKeepPartsWrittenAfterTheFirstTouchOnCommit() {
			markIndexDirty(1);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			markIndexDirty(2);
			trapPart(7);
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.putStoragePart(
				CATALOG_VERSION, new StubStoragePart(8L)
			);

			savepoint.commit();
			assertEquals(List.of(1, 2), dirtyIndexIds());
			assertEquals(List.of(7L), trappedPartKeys());
			assertNotNull(storedPart(8L), "Commit must keep the part the savepoint wrote through to the store.");
		}
	}

	@Nested
	@DisplayName("Storage parts written through to the store")
	class WrittenThroughStorageParts {

		@Test
		@DisplayName("A part overwritten before a later write fails keeps its pre-mutation content")
		void shouldRestoreOverwrittenRecordWhenALaterWriteFails() {
			// Codex's scenario, and the one this whole group exists for: a root entity whose body spans two parts.
			// The first write lands, the second throws, the collector rolls the savepoint back - and the rollback
			// SUCCEEDS, so the catalog barrier never goes up. Without a captured pre-image the first part would stay
			// changed in the trunk, i.e. a half-updated but perfectly fetchable entity body
			seedStore(new StubStoragePart(1L, "before"));
			seedStore(new StubStoragePart(2L, "before"));
			// the SECOND write of the entity is the one that fails - aimed by ordinal rather than by key, so that the
			// writes the rollback itself issues to put the pre-images back still succeed
			WarmUpSavepointDataStoreChangesTest.this.persistenceService.failOnPut(2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			writeThrough(new StubStoragePart(1L, "after"));
			assertEquals("after", storedPayload(1L), "The test would be vacuous without a landed first write.");
			assertThrows(
				SimulatedWriteFailure.class,
				() -> writeThrough(new StubStoragePart(2L, "after")),
				"The test would be vacuous unless the second write really failed."
			);

			savepoint.rollback();
			assertEquals(
				"before", storedPayload(1L),
				"The part written before the failure must hold its PRE-mutation content after the rollback."
			);
			assertEquals("before", storedPayload(2L), "The failed write must have changed nothing.");
		}

		@Test
		@DisplayName("A part first created before a later write fails is removed again")
		void shouldRemoveCreatedRecordWhenALaterWriteFails() {
			// the previously-absent variant of the same scenario: the inverse of a write that created a record is to
			// drop it, not to put something back
			seedStore(new StubStoragePart(2L, "before"));
			WarmUpSavepointDataStoreChangesTest.this.persistenceService.failOnPut(2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			writeThrough(new StubStoragePart(1L, "created"));
			assertNotNull(storedPart(1L), "The test would be vacuous without a landed first write.");
			assertThrows(SimulatedWriteFailure.class, () -> writeThrough(new StubStoragePart(2L, "after")));

			savepoint.rollback();
			assertNull(
				storedPart(1L),
				"A record that did not exist before the savepoint must not survive its rollback."
			);
			assertEquals("before", storedPayload(2L));
		}

		@Test
		@DisplayName("A part whose key the store assigns inside the write is removed again on rollback")
		void shouldRemoveRecordWithStoreAssignedKeyOnRollback() {
			// a part arriving without a primary key has one computed and set on it INSIDE the write, so the inverse
			// cannot close over a key that does not exist yet - it reads it off the part at rollback time
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			writeThrough(new UnkeyedStoragePart());
			assertNotNull(
				this.storedUnkeyedPart(),
				"The test would be vacuous unless the store really filed the part under the key it assigned."
			);

			savepoint.rollback();
			assertNull(this.storedUnkeyedPart(), "The record the write created must be dropped by the rollback.");
		}

		@Test
		@DisplayName("A part whose key the store assigns inside the write is kept on commit")
		void shouldKeepRecordWithStoreAssignedKeyOnCommit() {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			writeThrough(new UnkeyedStoragePart());
			savepoint.commit();

			assertNotNull(this.storedUnkeyedPart(), "Commit must keep the record the write created.");
		}

		@Test
		@DisplayName("A record rewritten several times inside the savepoint ends at its pre-savepoint content")
		void shouldRestoreThePreSavepointContentOfARepeatedlyRewrittenRecord() {
			// the ordering property the absolute-restore contract buys: replayed newest-first, the EARLIEST inverse
			// for a record runs last and wins
			seedStore(new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			writeThrough(new StubStoragePart(1L, "first"));
			writeThrough(new StubStoragePart(1L, "second"));
			writeThrough(new StubStoragePart(1L, "third"));
			assertEquals("third", storedPayload(1L));

			savepoint.rollback();
			assertEquals("before", storedPayload(1L), "The pre-savepoint content is the one that must survive.");
		}

		@Test
		@DisplayName("Written-through parts are kept on commit")
		void shouldKeepWrittenThroughPartsOnCommit() {
			seedStore(new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			writeThrough(new StubStoragePart(1L, "after"));
			savepoint.commit();

			assertEquals("after", storedPayload(1L), "Commit must keep the content the savepoint wrote.");
		}

		@Test
		@DisplayName("A write made with no savepoint open is not revertable")
		void shouldNotRecordWrittenThroughPartsWithoutOpenSavepoint() {
			seedStore(new StubStoragePart(1L, "before"));
			writeThrough(new StubStoragePart(1L, "after"));

			// opening a savepoint AFTER the fact must not retroactively capture anything
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			savepoint.rollback();

			assertEquals("after", storedPayload(1L));
		}

		/**
		 * Reads the record the store filed {@link UnkeyedStoragePart} under.
		 *
		 * @return the stored part, or `null` when the store holds none
		 */
		private StoragePart storedUnkeyedPart() {
			return WarmUpSavepointDataStoreChangesTest.this.persistenceService.record(
				UnkeyedStoragePart.class, STORE_ASSIGNED_PK
			);
		}
	}

	@Nested
	@DisplayName("Storage parts removed from the store")
	class RemovedStorageParts {

		@Test
		@DisplayName("A record removed inside the savepoint is put back on rollback")
		void shouldRestoreRemovedRecordOnRollback() {
			// the removal branch of the same gap: an entity part that became empty is deleted straight from the store
			// by ContainerizedLocalMutationExecutor#commit, and a rollback has to reinstate its content, not just its
			// key
			seedStore(new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.removeStoragePart(
				CATALOG_VERSION, 1L, StubStoragePart.class
			);
			assertNull(storedPart(1L), "The test would be vacuous unless the removal really landed.");

			savepoint.rollback();
			assertEquals("before", storedPayload(1L), "Rollback must put the removed record back by value.");
		}

		@Test
		@DisplayName("Removing a record that was never there stays a no-op through the rollback")
		void shouldLeaveAbsentRecordAbsentOnRollback() {
			// the inverse of a removal that removed nothing must be total: it restores the absent pre-image, which is
			// a no-op in the store rather than a failure
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.removeStoragePart(
				CATALOG_VERSION, 1L, StubStoragePart.class
			);

			savepoint.rollback();
			assertNull(storedPart(1L));
		}

		@Test
		@DisplayName("A record removed inside the savepoint stays removed on commit")
		void shouldKeepRemovalOnCommit() {
			seedStore(new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.removeStoragePart(
				CATALOG_VERSION, 1L, StubStoragePart.class
			);
			savepoint.commit();

			assertNull(storedPart(1L), "Commit must keep the removal.");
		}

		@Test
		@DisplayName("A record removed and then rewritten inside the savepoint ends at its pre-savepoint content")
		void shouldRestorePreSavepointContentOfARemovedAndRewrittenRecord() {
			seedStore(new StubStoragePart(1L, "before"));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			WarmUpSavepointDataStoreChangesTest.this.dataStoreChanges.removeStoragePart(
				CATALOG_VERSION, 1L, StubStoragePart.class
			);
			writeThrough(new StubStoragePart(1L, "recreated"));
			assertEquals("recreated", storedPayload(1L));

			savepoint.rollback();
			assertEquals("before", storedPayload(1L));
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
	 * Writes a storage part THROUGH the layer into the stand-in store, which is what a root entity mutation does — it
	 * runs with {@code trapChanges == false}, so {@code ContainerizedLocalMutationExecutor#commit} reaches the
	 * persistence service rather than the trapped cache.
	 *
	 * @param part the storage part to write
	 */
	private void writeThrough(@Nonnull StoragePart part) {
		this.dataStoreChanges.putStoragePart(CATALOG_VERSION, part);
	}

	/**
	 * Puts a record into the store directly, standing in for state persisted by an EARLIER entity mutation — the state
	 * a rollback has to restore rather than remove.
	 *
	 * @param part the storage part the store already holds
	 */
	private void seedStore(@Nonnull StoragePart part) {
		this.persistenceService.seed(part);
	}

	/**
	 * Reads the {@link StubStoragePart} the store currently holds under the given primary key.
	 *
	 * @param primaryKey the storage-part primary key
	 * @return the stored part, or `null` when the store holds none
	 */
	private StoragePart storedPart(long primaryKey) {
		return this.persistenceService.record(StubStoragePart.class, primaryKey);
	}

	/**
	 * Reads the payload of the {@link StubStoragePart} the store currently holds under the given primary key.
	 *
	 * @param primaryKey the storage-part primary key
	 * @return the stored payload, or `null` when the store holds no such record
	 */
	private String storedPayload(long primaryKey) {
		final StoragePart part = storedPart(primaryKey);
		return part == null ? null : ((StubStoragePart) part).payload();
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
	 * Minimal {@link StoragePart} identified by its storage-part primary key, carrying a payload so that a restore can
	 * be told apart from a mere re-registration of the key.
	 *
	 * @param pk      the storage-part primary key
	 * @param payload the content a rollback has to put back
	 */
	private record StubStoragePart(@Nonnull Long pk, @Nonnull String payload) implements StoragePart {
		@Serial private static final long serialVersionUID = 1L;

		/**
		 * Creates a part whose content is irrelevant to the assertion — the trapped-cache tests track keys only.
		 *
		 * @param pk the storage-part primary key
		 */
		private StubStoragePart(long pk) {
			this(pk, "content-of-" + pk);
		}

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

	/**
	 * {@link StoragePart} that arrives at the store WITHOUT a primary key and has one computed and set on it by the
	 * write — the shape {@code AttributesStoragePart} / {@code AssociatedDataStoragePart} have when a mutation creates
	 * one for a key the entity did not carry before. It is the case where the inverse of a write cannot close over the
	 * key it has to drop, because that key does not exist yet when the inverse is recorded.
	 */
	private static final class UnkeyedStoragePart implements StoragePart {
		@Serial private static final long serialVersionUID = 1L;
		private Long storagePartPK;

		@Nullable
		@Override
		public Long getStoragePartPK() {
			return this.storagePartPK;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			this.storagePartPK = STORE_ASSIGNED_PK;
			return this.storagePartPK;
		}
	}

}
