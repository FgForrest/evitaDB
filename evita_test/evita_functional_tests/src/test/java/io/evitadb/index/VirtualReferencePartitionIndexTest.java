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

package io.evitadb.index;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionRootRemoval;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the in-memory behaviour of {@link VirtualReferencePartitionIndex}: the owner and tuple slots, the reference
 * counting of the tuple dictionary, slot iteration, the transactional (STM) commit, rollback and layer removal, the
 * warm-up savepoint rollback, and which storage parts a flush emits for each state of the index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexTest {
	private static final int ENTITY_INDEX_PK = 3;
	private static final String REFERENCE_NAME = "media";
	private static final Serializable[] GALLERY = {"gallery"};
	private static final Serializable[] THUMBNAIL = {"thumbnail"};
	private static final Serializable[] COMPOUND = {"video", 7, new BigDecimal("1.50"), null};

	/**
	 * The logical content of an index - every entry of both slots, the tuple ids resolved to their values.
	 *
	 * @param owners the `partition -> pack(owner, target)` entries
	 * @param tuples the `partition -> tuple values` entries
	 */
	record Content(@Nonnull Map<Integer, Long> owners, @Nonnull Map<Integer, List<Serializable>> tuples) {
	}

	/**
	 * Reads the logical content of the index through its public surface.
	 *
	 * @param index the index to read
	 * @return the content
	 */
	@Nonnull
	static Content contentOf(@Nonnull VirtualReferencePartitionIndex index) {
		final Map<Integer, Long> owners = new TreeMap<>();
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(index, owners::put);
		final Map<Integer, List<Serializable>> tuples = new TreeMap<>();
		VirtualReferencePartitionIndexEntries.forEachRepresentativeTuple(
			index,
			(partitionPrimaryKey, tupleId) -> {
				final Serializable[] tuple = index.getTuple((int) tupleId);
				assertNotNull(tuple, "Partition " + partitionPrimaryKey + " refers to an unknown tuple " + tupleId);
				tuples.put(partitionPrimaryKey, Arrays.asList(tuple));
			}
		);
		return new Content(owners, tuples);
	}

	/**
	 * Drains a flush of the index into a list.
	 *
	 * @param index the index to flush
	 * @return every part the flush emitted, in emission order
	 */
	@Nonnull
	static List<StoragePart> flush(@Nonnull VirtualReferencePartitionIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Returns the root of an emission.
	 *
	 * @param parts the emission
	 * @return the root
	 */
	@Nonnull
	static VirtualReferencePartitionIndexStoragePart rootOf(@Nonnull List<StoragePart> parts) {
		return parts.stream()
			.filter(VirtualReferencePartitionIndexStoragePart.class::isInstance)
			.map(VirtualReferencePartitionIndexStoragePart.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("The emission must hold a root: " + parts));
	}

	/**
	 * Closes a savepoint a failing test might have left bound to this thread.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("Partition owner slot")
	class PartitionOwnerSlot {

		@Test
		@DisplayName("stores, replaces and forgets the owner of a partition")
		void shouldPutReplaceAndRemoveOwner() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertTrue(index.isEmpty());
			assertTrue(index.getPartitionOwner(10).isEmpty());

			index.putPartitionOwner(10, 100, 1000);
			assertFalse(index.isEmpty());
			assertEquals(OptionalLong.of(NumberUtils.pack(100, 1000)), index.getPartitionOwner(10));

			index.putPartitionOwner(10, 101, 1001);
			assertEquals(OptionalLong.of(NumberUtils.pack(101, 1001)), index.getPartitionOwner(10));

			assertTrue(index.removePartitionOwner(10));
			assertFalse(index.removePartitionOwner(10), "A second removal finds nothing");
			assertTrue(index.getPartitionOwner(10).isEmpty());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("round-trips any owner and target primary key, the extremes included")
		void shouldRoundTripExtremeOwnerAndTargetPrimaryKeys() {
			final int[] extremes = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			int partition = 1;
			for (final int owner : extremes) {
				for (final int target : extremes) {
					index.putPartitionOwner(partition++, owner, target);
				}
			}
			partition = 1;
			for (final int owner : extremes) {
				for (final int target : extremes) {
					final long packed = index.getPartitionOwner(partition++).orElseThrow();
					assertEquals(owner, NumberUtils.unpackHigh(packed), "Owner must round-trip");
					assertEquals(target, NumberUtils.unpackLow(packed), "Target must round-trip");
				}
			}
		}

		@Test
		@DisplayName("rewriting the same owner leaves the index clean")
		void shouldNotDirtyTheIndexWhenTheOwnerIsUnchanged() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(10, 100, 1000);
			index.resetDirty();

			index.putPartitionOwner(10, 100, 1000);

			assertTrue(flush(index).isEmpty(), "An unchanged owner must not make the index emit anything");
		}

		@Test
		@DisplayName("refuses a non-positive partition primary key")
		void shouldRefuseNonPositivePartitionPrimaryKey() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertThrows(GenericEvitaInternalError.class, () -> index.putPartitionOwner(0, 1, 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.putPartitionOwner(-5, 1, 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.putRepresentativeTuple(0, GALLERY));
			assertThrows(GenericEvitaInternalError.class, () -> index.getPartitionOwner(Integer.MIN_VALUE));
		}
	}

	@Nested
	@DisplayName("Representative tuple slot")
	class RepresentativeTupleSlot {

		@Test
		@DisplayName("partitions with equal values share one dictionary tuple")
		void shouldShareOneTupleAmongPartitionsWithEqualValues() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			// an equal but distinct array must resolve to the same tuple
			index.putRepresentativeTuple(2, new Serializable[]{"gallery"});
			index.putRepresentativeTuple(3, THUMBNAIL);

			final int galleryId = index.getTupleId(GALLERY);
			assertNotEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, galleryId);
			assertEquals(galleryId, index.getRepresentativeTupleId(1));
			assertEquals(galleryId, index.getRepresentativeTupleId(2));
			assertNotEquals(galleryId, index.getRepresentativeTupleId(3));
			assertArrayEquals(GALLERY, index.getTuple(galleryId));
			assertArrayEquals(THUMBNAIL, index.getTuple(index.getRepresentativeTupleId(3)));
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getRepresentativeTupleId(4));
		}

		@Test
		@DisplayName("a tuple leaves the dictionary together with its last user")
		void shouldDropTupleWithItsLastUser() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.putRepresentativeTuple(2, GALLERY);
			final int galleryId = index.getTupleId(GALLERY);

			assertTrue(index.removeRepresentativeTuple(1));
			assertEquals(galleryId, index.getTupleId(GALLERY), "The tuple still has a user");
			assertArrayEquals(GALLERY, index.getTuple(galleryId));

			assertTrue(index.removeRepresentativeTuple(2));
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(GALLERY));
			assertNull(index.getTuple(galleryId), "A tuple without users must leave the dictionary");
			assertFalse(index.removeRepresentativeTuple(2), "A second removal finds nothing");
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("switching a partition to another tuple releases the previous one")
		void shouldReleaseThePreviousTupleWhenSwitching() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			final int galleryId = index.getTupleId(GALLERY);

			index.putRepresentativeTuple(1, THUMBNAIL);

			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(GALLERY));
			assertNull(index.getTuple(galleryId));
			assertArrayEquals(THUMBNAIL, index.getTuple(index.getRepresentativeTupleId(1)));
		}

		@Test
		@DisplayName("re-recording the same tuple neither dirties the index nor adds a user")
		void shouldIgnoreTheSameTupleRecordedAgain() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();

			index.putRepresentativeTuple(1, new Serializable[]{"gallery"});
			assertTrue(flush(index).isEmpty(), "An unchanged tuple must not make the index emit anything");

			// one removal must be enough to drop the tuple - a second recorded user would keep it alive
			index.removeRepresentativeTuple(1);
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(GALLERY));
		}

		@Test
		@DisplayName("the dictionary keeps its own copy of the values")
		void shouldCopyTheValuesIntoTheDictionary() {
			final Serializable[] values = {"gallery"};
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, values);

			values[0] = "changed";

			assertArrayEquals(GALLERY, index.getTuple(index.getRepresentativeTupleId(1)));
			assertNotEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(GALLERY));
		}

		@Test
		@DisplayName("a tuple of mixed types and a null value is found again by equal values")
		void shouldFindCompoundTupleByEqualValues() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(5, COMPOUND);
			final int tupleId = index.getTupleId(new Serializable[]{"video", 7, new BigDecimal("1.50"), null});
			assertEquals(index.getRepresentativeTupleId(5), tupleId);
			assertArrayEquals(COMPOUND, index.getTuple(tupleId));
		}

		@Test
		@DisplayName("an id stays with its tuple while anything refers to it")
		void shouldReuseIdOnlyAfterRelease() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.putRepresentativeTuple(2, THUMBNAIL);
			final int galleryId = index.getTupleId(GALLERY);
			final int thumbnailId = index.getTupleId(THUMBNAIL);
			assertNotEquals(galleryId, thumbnailId);

			// a new tuple while both are in use takes a fresh id
			index.putRepresentativeTuple(3, COMPOUND);
			final int compoundId = index.getTupleId(COMPOUND);
			assertNotEquals(galleryId, compoundId);
			assertNotEquals(thumbnailId, compoundId);

			// after the largest id is released, the remaining tuples keep theirs
			index.removeRepresentativeTuple(3);
			index.putRepresentativeTuple(4, new Serializable[]{"poster"});
			assertEquals(galleryId, index.getTupleId(GALLERY));
			assertEquals(thumbnailId, index.getTupleId(THUMBNAIL));
			assertArrayEquals(new Serializable[]{"poster"}, index.getTuple(index.getRepresentativeTupleId(4)));
		}
	}

	@Nested
	@DisplayName("Tuple id allocation")
	class TupleIdAllocation {
		private static final Serializable[] POSTER = {"poster"};
		private static final Serializable[] BANNER = {"banner"};

		@Test
		@DisplayName("a released id is never handed out again, not even to the tuple that held it")
		void shouldNotHandOutAReleasedIdAgain() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.putRepresentativeTuple(2, THUMBNAIL);
			final int galleryId = index.getTupleId(GALLERY);
			final int thumbnailId = index.getTupleId(THUMBNAIL);

			// releasing the largest id in use leaves a dictionary whose largest id is smaller than one already issued
			index.removeRepresentativeTuple(2);
			index.putRepresentativeTuple(3, POSTER);
			final int posterId = index.getTupleId(POSTER);
			index.putRepresentativeTuple(4, THUMBNAIL);
			final int readdedThumbnailId = index.getTupleId(THUMBNAIL);

			assertEquals(thumbnailId + 1, posterId, "The next id follows the last one issued, not the dictionary");
			assertEquals(posterId + 1, readdedThumbnailId, "A re-added tuple takes a fresh id");
			assertEquals(galleryId, index.getTupleId(GALLERY));
			assertArrayEquals(GALLERY, index.getTuple(galleryId));
			assertArrayEquals(POSTER, index.getTuple(posterId));
			assertArrayEquals(THUMBNAIL, index.getTuple(readdedThumbnailId));
			assertNull(index.getTuple(thumbnailId), "Nothing refers to the released id");
		}

		/**
		 * Allocating an id by a walk over the dictionary costs `O(T)` per new tuple, so building `N` distinct tuples
		 * costs `O(N²)` - around 2·10¹⁰ map visits here, which takes minutes. An `O(1)` allocation builds them in well
		 * under a second, so the bound sits more than an order of magnitude away from both. There is no seam to count
		 * the visits through, hence the wall-clock bound.
		 */
		@Test
		@DisplayName("builds many distinct tuples in linear time")
		void shouldBuildManyDistinctTuplesInLinearTime() {
			final int tupleCount = 200_000;
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertTimeoutPreemptively(
				Duration.ofSeconds(10),
				() -> {
					for (int partition = 1; partition <= tupleCount; partition++) {
						index.putRepresentativeTuple(partition, new Serializable[]{partition});
					}
				},
				"Building distinct tuples must not walk the dictionary per new tuple"
			);
			assertEquals(tupleCount, index.getTupleId(new Serializable[]{tupleCount}));
		}

		@Test
		@DisplayName("a transaction rollback restores the next id")
		void shouldRestoreTheNextIdOnTransactionRollback() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final int galleryId = index.getTupleId(GALLERY);

			assertStateAfterRollback(
				index,
				original -> {
					original.putRepresentativeTuple(2, THUMBNAIL);
					original.putRepresentativeTuple(3, COMPOUND);
				},
				(original, committed) -> assertNull(committed)
			);

			index.putRepresentativeTuple(4, POSTER);
			assertEquals(galleryId + 1, index.getTupleId(POSTER), "The rolled-back ids must be issued again");
		}

		@Test
		@DisplayName("a commit carries the next id into the new instance and leaves the original's untouched")
		void shouldCarryTheNextIdAcrossCommit() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final int galleryId = index.getTupleId(GALLERY);

			final VirtualReferencePartitionIndex[] committed = new VirtualReferencePartitionIndex[1];
			assertStateAfterCommit(
				index,
				original -> {
					original.putRepresentativeTuple(2, THUMBNAIL);
					// a tuple acquired and released within the transaction still consumes its id
					original.putRepresentativeTuple(3, COMPOUND);
					original.removeRepresentativeTuple(3);
				},
				(original, result) -> committed[0] = result
			);

			committed[0].putRepresentativeTuple(4, POSTER);
			assertEquals(galleryId + 3, committed[0].getTupleId(POSTER));
			index.putRepresentativeTuple(4, POSTER);
			assertEquals(galleryId + 1, index.getTupleId(POSTER), "The original must not see the committed next id");
		}

		@Test
		@DisplayName("a savepoint rollback restores the next id the transaction advanced before the savepoint")
		void shouldRestoreTheNextIdOnSavepointRollback() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final int galleryId = index.getTupleId(GALLERY);

			assertStateAfterCommit(
				index,
				original -> {
					// the transaction touches the next id before the savepoint, so the rollback restores its layer
					original.putRepresentativeTuple(2, THUMBNAIL);
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					final TransactionalLayerMaintainer.Savepoint savepoint = maintainer.openSavepoint();
					original.putRepresentativeTuple(3, COMPOUND);
					original.putRepresentativeTuple(4, POSTER);
					maintainer.rollbackSavepoint(savepoint);

					original.putRepresentativeTuple(5, BANNER);
					assertEquals(galleryId + 2, original.getTupleId(BANNER), "The rolled-back ids must be issued again");
				},
				(original, committed) -> assertEquals(galleryId + 2, committed.getTupleId(BANNER))
			);
		}

		@Test
		@DisplayName("a savepoint rollback restores the next id the transaction first advanced inside the savepoint")
		void shouldRestoreTheNextIdFirstTouchedInsideTheSavepoint() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final int galleryId = index.getTupleId(GALLERY);

			assertStateAfterCommit(
				index,
				original -> {
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					final TransactionalLayerMaintainer.Savepoint savepoint = maintainer.openSavepoint();
					original.putRepresentativeTuple(2, THUMBNAIL);
					maintainer.rollbackSavepoint(savepoint);

					original.putRepresentativeTuple(3, POSTER);
					assertEquals(galleryId + 1, original.getTupleId(POSTER), "The rolled-back id must be issued again");
				},
				(original, committed) -> assertEquals(galleryId + 1, committed.getTupleId(POSTER))
			);
		}

		@Test
		@DisplayName("a warm-up savepoint rollback restores the next id")
		void shouldRestoreTheNextIdOnWarmUpSavepointRollback() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			final int galleryId = index.getTupleId(GALLERY);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			index.putRepresentativeTuple(2, THUMBNAIL);
			index.putRepresentativeTuple(3, COMPOUND);
			savepoint.rollback();

			index.putRepresentativeTuple(4, POSTER);
			assertEquals(galleryId + 1, index.getTupleId(POSTER), "The rolled-back ids must be issued again");
		}

		@Test
		@DisplayName("a reloaded index continues allocating above every persisted id")
		void shouldContinueAllocationAfterReload() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.putRepresentativeTuple(2, THUMBNAIL);
			index.putRepresentativeTuple(3, COMPOUND);
			// a hole below the largest persisted id
			index.removeRepresentativeTuple(2);
			final VirtualReferencePartitionIndexStoragePart root = rootOf(flush(index));
			final int[] persistedIds = root.getTupleIds();

			final VirtualReferencePartitionIndex reloaded = new VirtualReferencePartitionIndex(
				REFERENCE_NAME, root.getKeys(), root.getPayloads(), persistedIds, root.getTuples(),
				root.getSortValueSlots(), root.getChainPredecessorSlots()
			, slot -> SortIndex.createLegacyNormalizerFor(slot.comparatorBase(), slot.indexedDecimalPlaces()));
			reloaded.putRepresentativeTuple(4, POSTER);

			final int posterId = reloaded.getTupleId(POSTER);
			assertEquals(Arrays.stream(persistedIds).max().orElseThrow() + 1, posterId);
			assertArrayEquals(GALLERY, reloaded.getTuple(index.getTupleId(GALLERY)));
			assertArrayEquals(COMPOUND, reloaded.getTuple(index.getTupleId(COMPOUND)));
			assertArrayEquals(POSTER, reloaded.getTuple(posterId));
		}
	}

	@Nested
	@DisplayName("Owner row iteration")
	class OwnerRowIteration {

		@Test
		@DisplayName("the owner cursor visits exactly the owner rows in ascending partition order")
		void shouldVisitTheOwnerRowsInPartitionOrder() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(Integer.MAX_VALUE, -1, -2);
			index.putPartitionOwner(7, 70, 700);
			index.putPartitionOwner(1, 10, 100);
			index.putRepresentativeTuple(1, GALLERY);
			index.putRepresentativeTuple(Integer.MAX_VALUE, THUMBNAIL);

			final List<Integer> ownerPartitions = new ArrayList<>();
			final List<Long> ownerPayloads = new ArrayList<>();
			VirtualReferencePartitionIndexEntries.forEachOwnerRow(
				index,
				(partitionPrimaryKey, payload) -> {
					ownerPartitions.add(partitionPrimaryKey);
					ownerPayloads.add(payload);
				}
			);
			assertEquals(List.of(1, 7, Integer.MAX_VALUE), ownerPartitions);
			assertEquals(
				List.of(NumberUtils.pack(10, 100), NumberUtils.pack(70, 700), NumberUtils.pack(-1, -2)), ownerPayloads
			);

			final List<Integer> tuplePartitions = new ArrayList<>();
			VirtualReferencePartitionIndexEntries.forEachRepresentativeTuple(
				index, (partitionPrimaryKey, payload) -> tuplePartitions.add(partitionPrimaryKey)
			);
			assertEquals(List.of(1, Integer.MAX_VALUE), tuplePartitions);
		}

		@Test
		@DisplayName("the owner cursor visits nothing when only the other slots hold entries")
		void shouldVisitNothingWithoutOwnerRows() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			final List<Integer> visited = new ArrayList<>();
			VirtualReferencePartitionIndexEntries.forEachOwnerRow(
				index, (partitionPrimaryKey, payload) -> visited.add(partitionPrimaryKey)
			);
			assertTrue(visited.isEmpty());
			assertFalse(index.isEmpty(), "The premise of the case: another slot holds an entry");
		}
	}

	@Nested
	@DisplayName("Registered partition count")
	class RegisteredPartitionCount {

		@Test
		@DisplayName("counts no partition when no owner is recorded, whatever the other slots hold")
		void shouldCountNothingWithoutAnOwnerRow() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertEquals(0, index.countRegisteredPartitions());

			// entries of later slots only: the walk meets a key of another slot first and counts nothing
			index.putRepresentativeTuple(1, GALLERY);
			index.putRepresentativeTuple(2, THUMBNAIL);
			assertEquals(0, index.countRegisteredPartitions());
		}

		@Test
		@DisplayName("counts each partition once, however many slots hold an entry of it")
		void shouldCountEachPartitionOnceAcrossItsSlots() {
			final AttributeIndexKey chain = new AttributeIndexKey(REFERENCE_NAME, "chain", null);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(
				VirtualReferencePartitionIndexSortValueTest.ORDER,
				VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC,
				0,
				() -> SortIndex.createLegacyNormalizerFor(VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC, 0)
			);
			index.declareChainKey(chain);
			for (int partition = 1; partition <= 3; partition++) {
				index.putPartitionOwner(partition, partition * 10, partition * 100);
				index.putRepresentativeTuple(partition, GALLERY);
				index.putSortValue(VirtualReferencePartitionIndexSortValueTest.ORDER, partition, partition * 5);
				index.putChainPredecessor(chain, partition, -1);
			}

			assertEquals(3, index.countRegisteredPartitions());

			index.removePartitionOwner(2);
			assertEquals(2, index.countRegisteredPartitions(), "Only the owner row makes a partition count");
		}

		@Test
		@DisplayName("counts the rows of a tree spanning many leaves, followed by as many entries of another slot")
		void shouldCountRowsAcrossManyLeaves() {
			// far above one leaf of 256 entries, so the walk crosses leaves inside the owner slot and stops inside the
			// leaf where the tuple slot begins
			final int partitions = 1_000;
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			for (int partition = 1; partition <= partitions; partition++) {
				index.putPartitionOwner(partition, partition, -partition);
				index.putRepresentativeTuple(partition, partition % 2 == 0 ? GALLERY : THUMBNAIL);
			}

			assertEquals(partitions, index.countRegisteredPartitions());
		}

		@Test
		@DisplayName("a transaction counts its own rows, and only a commit shows them to others")
		@Tag(TRANSACTION)
		void shouldCountTheRowsOfTheStateItReads() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> {
					original.putPartitionOwner(2, 20, 200);
					original.putPartitionOwner(3, 30, 300);
					assertEquals(3, original.countRegisteredPartitions(), "The transaction sees its own rows");
				},
				(original, committed) -> {
					assertEquals(1, original.countRegisteredPartitions(), "The original must not see the commit");
					assertEquals(3, committed.countRegisteredPartitions());
				}
			);
			assertStateAfterRollback(
				index,
				original -> original.removePartitionOwner(1),
				(original, committed) -> assertEquals(1, original.countRegisteredPartitions())
			);
		}
	}

	@Nested
	@DisplayName("Transactional memory")
	@Tag(TRANSACTION)
	class TransactionalMemory {

		@Test
		@DisplayName("a commit produces a new instance and leaves the original untouched")
		void shouldCommitIntoANewInstance() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final Content before = contentOf(index);

			assertStateAfterCommit(
				index,
				original -> {
					original.putPartitionOwner(2, 20, 200);
					original.putRepresentativeTuple(2, THUMBNAIL);
					original.putRepresentativeTuple(1, COMPOUND);
					// the transaction sees its own writes
					assertEquals(OptionalLong.of(NumberUtils.pack(20, 200)), original.getPartitionOwner(2));
					assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, original.getTupleId(GALLERY));
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(before, contentOf(original), "The original must not see the committed changes");
					assertNotEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, original.getTupleId(GALLERY));
					assertEquals(OptionalLong.of(NumberUtils.pack(20, 200)), committed.getPartitionOwner(2));
					assertArrayEquals(COMPOUND, committed.getTuple(committed.getRepresentativeTupleId(1)));
					assertArrayEquals(THUMBNAIL, committed.getTuple(committed.getRepresentativeTupleId(2)));
					assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, committed.getTupleId(GALLERY));
				}
			);
		}

		@Test
		@DisplayName("a commit without a write returns the same instance")
		void shouldReturnTheSameInstanceWithoutWrites() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.resetDirty();
			assertStateAfterCommit(
				index,
				original -> original.getPartitionOwner(1),
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("user counts carry over across commits")
		void shouldCarryUserCountsAcrossCommits() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			final VirtualReferencePartitionIndex[] committed = new VirtualReferencePartitionIndex[1];
			assertStateAfterCommit(
				index,
				original -> {
					original.putRepresentativeTuple(1, GALLERY);
					original.putRepresentativeTuple(2, GALLERY);
				},
				(original, result) -> committed[0] = result
			);
			assertStateAfterCommit(
				committed[0],
				original -> original.removeRepresentativeTuple(1),
				(original, result) -> committed[0] = result
			);
			assertNotEquals(
				VirtualReferencePartitionIndex.NO_TUPLE_ID, committed[0].getTupleId(GALLERY),
				"The second user keeps the tuple"
			);
			assertStateAfterCommit(
				committed[0],
				original -> original.removeRepresentativeTuple(2),
				(original, result) -> committed[0] = result
			);
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, committed[0].getTupleId(GALLERY));
			assertTrue(committed[0].isEmpty());
		}

		@Test
		@DisplayName("a rollback leaves the original exactly as it was")
		void shouldLeaveTheOriginalOnRollback() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final Content before = contentOf(index);

			assertStateAfterRollback(
				index,
				original -> {
					original.removePartitionOwner(1);
					original.putRepresentativeTuple(1, THUMBNAIL);
					original.putRepresentativeTuple(2, COMPOUND);
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(before, contentOf(original));
					assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, original.getTupleId(THUMBNAIL));
					assertTrue(flush(original).isEmpty(), "A rolled-back index must not be dirty");
				}
			);
		}

		@Test
		@DisplayName("removing the layers discards every uncommitted write")
		void shouldDiscardUncommittedWritesWhenTheLayersAreRemoved() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();
			final Content before = contentOf(index);

			assertStateAfterCommit(
				index,
				original -> {
					original.putPartitionOwner(2, 20, 200);
					original.putRepresentativeTuple(1, THUMBNAIL);
					original.removeRepresentativeTuple(1);
					original.putRepresentativeTuple(3, COMPOUND);
					original.removeLayer(Transaction.getTransactionalLayerMaintainer());
					assertEquals(before, contentOf(original), "Without its layers the index reads its committed state");
				},
				// the commit verifies that no layer of the index was left behind
				Assertions::assertSame
			);
		}
	}

	@Nested
	@DisplayName("Warm-up savepoint")
	@Tag(TRANSACTION)
	class WarmUpSavepointRollback {

		@Test
		@DisplayName("a rollback restores both slots and the tuple dictionary")
		void shouldRestoreEntriesAndDictionaryOnRollback() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertWarmUpSavepointRollbackRestores(
				index,
				baseline -> {
					baseline.putPartitionOwner(1, 10, 100);
					baseline.putPartitionOwner(2, 20, 200);
					baseline.putRepresentativeTuple(1, GALLERY);
					baseline.putRepresentativeTuple(2, GALLERY);
				},
				VirtualReferencePartitionIndexTest::contentOf,
				savepoint -> {
					savepoint.putPartitionOwner(1, 11, 101);
					savepoint.removePartitionOwner(2);
					savepoint.putRepresentativeTuple(1, THUMBNAIL);
					savepoint.removeRepresentativeTuple(2);
					savepoint.putRepresentativeTuple(3, COMPOUND);
				}
			);
			// the content above cannot see dictionary entries no partition refers to, nor the user counts
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(THUMBNAIL));
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(COMPOUND));
			index.removeRepresentativeTuple(1);
			assertNotEquals(
				VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(GALLERY),
				"The restored dictionary must count both users of the tuple again"
			);
			index.removeRepresentativeTuple(2);
			assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(GALLERY));
		}

		@Test
		@DisplayName("a rollback restores the dirty state of the dictionary")
		void shouldRestoreDictionaryDirtyFlagOnRollback() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putRepresentativeTuple(1, GALLERY);
			index.resetDirty();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			index.putRepresentativeTuple(2, THUMBNAIL);
			savepoint.rollback();

			assertTrue(flush(index).isEmpty(), "A rolled-back savepoint must leave the index clean");
		}

		@Test
		@DisplayName("a commit keeps every change")
		void shouldKeepChangesOnCommit() {
			assertWarmUpSavepointCommitKeeps(
				new VirtualReferencePartitionIndex(),
				baseline -> baseline.putRepresentativeTuple(1, GALLERY),
				VirtualReferencePartitionIndexTest::contentOf,
				savepoint -> {
					savepoint.putPartitionOwner(1, 10, 100);
					savepoint.putRepresentativeTuple(2, THUMBNAIL);
				}
			);
		}
	}

	@Nested
	@DisplayName("Flush emission")
	class FlushEmission {

		@Test
		@DisplayName("a fresh index emits nothing")
		void shouldEmitNothingWhenNeverWritten() {
			assertTrue(flush(new VirtualReferencePartitionIndex()).isEmpty());
		}

		@Test
		@DisplayName("a small index emits one inline root carrying entries and dictionary")
		void shouldEmitInlineRoot() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(2, Integer.MIN_VALUE, Integer.MAX_VALUE);
			index.putRepresentativeTuple(2, COMPOUND);

			final List<StoragePart> parts = flush(index);
			assertEquals(1, parts.size(), "A small index emits its inline root only: " + parts);
			final VirtualReferencePartitionIndexStoragePart root =
				assertInstanceOf(VirtualReferencePartitionIndexStoragePart.class, parts.getFirst());
			assertFalse(root.isPaged());
			assertArrayEquals(
				new long[]{
					NumberUtils.pack(VirtualReferencePartitionSlot.PARTITION_OWNER.getId(), 2),
					NumberUtils.pack(VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE.getId(), 2)
				},
				root.getKeys()
			);
			assertArrayEquals(
				new long[]{NumberUtils.pack(Integer.MIN_VALUE, Integer.MAX_VALUE), index.getTupleId(COMPOUND)},
				root.getPayloads()
			);
			assertArrayEquals(new int[]{index.getTupleId(COMPOUND)}, root.getTupleIds());
			assertArrayEquals(COMPOUND, root.getTuples()[0]);

			index.resetDirty();
			assertTrue(flush(index).isEmpty(), "A flushed index emits nothing until written again");
		}

		@Test
		@DisplayName("an index emptied after a flush removes its root and writes none")
		void shouldRemoveTheRootWhenEmptied() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.putRepresentativeTuple(1, GALLERY);
			flush(index);
			index.resetDirty();

			index.removePartitionOwner(1);
			index.removeRepresentativeTuple(1);

			final List<StoragePart> parts = flush(index);
			assertEquals(1, parts.size(), "An emptied index emits the removal of its root only: " + parts);
			assertInstanceOf(VirtualReferencePartitionRootRemoval.class, parts.getFirst());
		}

		@Test
		@DisplayName("an index emptied before its first flush writes no root")
		void shouldWriteNoRootWhenEmptiedBeforeTheFirstFlush() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.removePartitionOwner(1);

			for (final StoragePart part : flush(index)) {
				assertFalse(
					part instanceof VirtualReferencePartitionIndexStoragePart,
					"An empty index must never persist a root: " + part
				);
			}
		}
	}

}
