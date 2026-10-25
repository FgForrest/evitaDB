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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.ChainPredecessorSlot;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.ORDER;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.REFERENCE_NAME;
import static io.evitadb.index.VirtualReferencePartitionIndexTest.flush;
import static io.evitadb.index.VirtualReferencePartitionIndexTest.rootOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the chain predecessor slots of {@link VirtualReferencePartitionIndex} in memory: declaring and retiring
 * chain keys, recording and forgetting the predecessor of a partition, that a partition without an entry is not chained
 * whatever else the index knows about it, and the transactional (STM) and warm-up savepoint behaviour.
 *
 * The fixture has the shape of a reference whose rows are ordered by a chain on the referencing side: many owners,
 * hundreds of single-owner partitions each, every partition chained to the previous row of its owner except the ones
 * the fixture leaves unchained.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index chain predecessor slots")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexChainPredecessorTest {
	/**
	 * A chain of `ReferencedEntityPredecessor` - the predecessor is the referenced entity of the previous row.
	 */
	static final AttributeIndexKey CHAIN = new AttributeIndexKey(REFERENCE_NAME, "parameterOrder", null);
	/**
	 * A localized chain of `Predecessor` - the predecessor is the owner of the previous row.
	 */
	static final AttributeIndexKey LOCALIZED_CHAIN =
		new AttributeIndexKey(REFERENCE_NAME, "localizedOrder", Locale.forLanguageTag("cs"));
	/**
	 * The head of a chain as the chain index receives it.
	 */
	static final int HEAD = -1;

	/**
	 * Builds the chained fixture: `owners` owners with `rowsPerOwner` partitions each. Every partition records its
	 * owner, and every partition whose number is not divisible by 17 is chained in {@link #CHAIN} to the referenced
	 * entity of the previous row of its owner (the first row being the head); every third partition is also chained in
	 * {@link #LOCALIZED_CHAIN}, to the previous owner.
	 *
	 * @param index        the index, both chain keys declared
	 * @param owners       how many owners
	 * @param rowsPerOwner how many partitions each owner has
	 * @return the predecessor of every chained partition per chain key
	 */
	@Nonnull
	static Map<AttributeIndexKey, Map<Integer, Integer>> populate(
		@Nonnull VirtualReferencePartitionIndex index,
		int owners,
		int rowsPerOwner
	) {
		final Map<AttributeIndexKey, Map<Integer, Integer>> models = new HashMap<>();
		models.put(CHAIN, new TreeMap<>());
		models.put(LOCALIZED_CHAIN, new TreeMap<>());
		int partition = 1;
		for (int owner = 1; owner <= owners; owner++) {
			int previousTarget = HEAD;
			for (int row = 0; row < rowsPerOwner; row++) {
				final int target = 100_000 + owner * 1_000 + row;
				index.putPartitionOwner(partition, owner, target);
				if (partition % 17 != 0) {
					index.putChainPredecessor(CHAIN, partition, previousTarget);
					models.get(CHAIN).put(partition, previousTarget);
				}
				if (partition % 3 == 0) {
					final int predecessor = owner == 1 ? HEAD : owner - 1;
					index.putChainPredecessor(LOCALIZED_CHAIN, partition, predecessor);
					models.get(LOCALIZED_CHAIN).put(partition, predecessor);
				}
				previousTarget = target;
				partition++;
			}
		}
		return models;
	}

	/**
	 * Creates an index with both chain keys declared.
	 *
	 * @return the index
	 */
	@Nonnull
	static VirtualReferencePartitionIndex declaredIndex() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.declareChainKey(CHAIN);
		index.declareChainKey(LOCALIZED_CHAIN);
		return index;
	}

	/**
	 * Applies random predecessor changes and removals of both chains to the index and mirrors them in the models.
	 *
	 * @param index      the index, both chain keys declared
	 * @param models     the predecessor of every chained partition per chain key, updated in step
	 * @param random     the random source
	 * @param operations how many operations to apply
	 * @param partitions the partition primary keys are drawn from `1..partitions`
	 */
	static void churn(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull Map<AttributeIndexKey, Map<Integer, Integer>> models,
		@Nonnull Random random,
		int operations,
		int partitions
	) {
		for (int i = 0; i < operations; i++) {
			final AttributeIndexKey key = random.nextBoolean() ? CHAIN : LOCALIZED_CHAIN;
			final Map<Integer, Integer> model = models.computeIfAbsent(key, it -> new TreeMap<>());
			final int partition = 1 + random.nextInt(partitions);
			if (random.nextInt(3) == 0) {
				assertEquals(
					model.remove(partition) != null, index.removeChainPredecessor(key, partition),
					"Removal of partition " + partition + " from " + key
				);
			} else {
				final int predecessor = random.nextInt(5) == 0 ? HEAD : random.nextInt();
				index.putChainPredecessor(key, partition, predecessor);
				model.put(partition, predecessor);
			}
		}
	}

	/**
	 * Asserts every partition up to `partitions` is chained exactly as the models say - with the recorded predecessor,
	 * or not at all.
	 *
	 * @param index      the index
	 * @param models     the predecessor of every chained partition per chain key
	 * @param partitions the partition primary keys to check, `1..partitions`
	 */
	static void assertMatchesModels(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull Map<AttributeIndexKey, Map<Integer, Integer>> models,
		int partitions
	) {
		for (final AttributeIndexKey key : List.of(CHAIN, LOCALIZED_CHAIN)) {
			final Map<Integer, Integer> model = models.getOrDefault(key, Map.of());
			for (int partition = 1; partition <= partitions; partition++) {
				final Integer expected = model.get(partition);
				assertEquals(
					expected == null ? OptionalInt.empty() : OptionalInt.of(expected),
					index.getChainPredecessor(key, partition),
					"Partition " + partition + " of " + key
				);
			}
		}
	}

	/**
	 * Copies the models deeply, so a churn that is rolled back can be mirrored without touching the originals.
	 *
	 * @param models the predecessor of every chained partition per chain key
	 * @return the independent copy
	 */
	@Nonnull
	static Map<AttributeIndexKey, Map<Integer, Integer>> copyOf(
		@Nonnull Map<AttributeIndexKey, Map<Integer, Integer>> models
	) {
		final Map<AttributeIndexKey, Map<Integer, Integer>> copy = new HashMap<>();
		models.forEach((key, model) -> copy.put(key, new TreeMap<>(model)));
		return copy;
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
	@DisplayName("Declaring and retiring chain keys")
	class Declaration {

		@Test
		@DisplayName("a declared key has its own slot apart from the sort keys and keeps the index from being empty")
		void shouldDeclareKeyInItsOwnSlot() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0, () -> SortIndex.createLegacyNormalizerFor(INTEGER_ASC, 0));
			index.declareChainKey(CHAIN);
			index.declareChainKey(CHAIN);
			index.retireSortKey(ORDER);
			assertFalse(index.isEmpty(), "A declared chain key is state the index must persist");

			final VirtualReferencePartitionIndexStoragePart root = rootOf(flush(index));
			assertEquals(1, root.getChainPredecessorSlots().length, "A repeated declaration adds nothing");
			final ChainPredecessorSlot slot = root.getChainPredecessorSlots()[0];
			assertEquals(CHAIN, slot.attributeIndexKey());
			assertTrue(slot.slotId() > VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID, "The sort key took the first id");

			// a new key never takes an id in use - neither a chain's nor a sort key's
			index.declareSortKey(ORDER, INTEGER_ASC, 0, () -> SortIndex.createLegacyNormalizerFor(INTEGER_ASC, 0));
			index.declareChainKey(LOCALIZED_CHAIN);
			final VirtualReferencePartitionIndexStoragePart nextRoot = rootOf(flush(index));
			final int sortSlotId = nextRoot.getSortValueSlots()[0].slotId();
			final int[] chainSlotIds = Arrays.stream(nextRoot.getChainPredecessorSlots())
				.mapToInt(ChainPredecessorSlot::slotId)
				.toArray();
			assertEquals(3, Arrays.stream(new int[]{sortSlotId, chainSlotIds[0], chainSlotIds[1]}).distinct().count());

			assertTrue(index.retireSortKey(ORDER));
			assertTrue(index.retireChainKey(CHAIN));
			assertTrue(index.retireChainKey(LOCALIZED_CHAIN));
			assertFalse(index.retireChainKey(LOCALIZED_CHAIN), "A second retirement finds nothing");
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("an attribute is a sort key or a chain key, never both")
		void shouldRefuseOneAttributeAsBothSortAndChainKey() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0, () -> SortIndex.createLegacyNormalizerFor(INTEGER_ASC, 0));
			index.declareChainKey(CHAIN);
			assertThrows(GenericEvitaInternalError.class, () -> index.declareChainKey(ORDER));
			assertThrows(GenericEvitaInternalError.class, () -> index.declareSortKey(
				CHAIN,
				INTEGER_ASC,
				0,
				() -> SortIndex.createLegacyNormalizerFor(INTEGER_ASC, 0)
			));
		}

		@Test
		@DisplayName("an undeclared chain key is refused")
		void shouldRefuseUndeclaredChainKey() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertThrows(GenericEvitaInternalError.class, () -> index.putChainPredecessor(CHAIN, 1, HEAD));
			assertThrows(GenericEvitaInternalError.class, () -> index.removeChainPredecessor(CHAIN, 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.getChainPredecessor(CHAIN, 1));
		}

		@Test
		@DisplayName("retiring a key removes the predecessor of every partition, the owners stay")
		void shouldReclaimEveryEntryOfARetiredKey() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			// small enough for the inline root, whose keys show the slot is gone
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = populate(index, 2, 30);

			assertTrue(index.retireChainKey(CHAIN));
			assertThrows(GenericEvitaInternalError.class, () -> index.getChainPredecessor(CHAIN, 1));
			final VirtualReferencePartitionIndexStoragePart root = rootOf(flush(index));
			assertFalse(root.isPaged());
			final int chainSlotId = VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID;
			assertTrue(
				Arrays.stream(root.getKeys()).noneMatch(key -> NumberUtils.unpackHigh(key) == chainSlotId),
				"No entry of the retired slot may remain"
			);
			models.remove(CHAIN);
			index.declareChainKey(CHAIN);
			assertMatchesModels(index, models, 60);
			for (int partition = 1; partition <= 60; partition++) {
				assertTrue(index.getPartitionOwner(partition).isPresent(), "The owners stay");
			}
		}
	}

	@Nested
	@DisplayName("Predecessors")
	class Predecessors {

		@Test
		@DisplayName("stores, replaces and forgets the predecessor of a partition, any int included")
		void shouldPutReplaceAndRemovePredecessor() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final int[] predecessors = {HEAD, 0, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, -2};
			for (final int predecessor : predecessors) {
				index.putChainPredecessor(CHAIN, 7, predecessor);
				assertEquals(OptionalInt.of(predecessor), index.getChainPredecessor(CHAIN, 7));
			}
			assertTrue(index.removeChainPredecessor(CHAIN, 7));
			assertFalse(index.removeChainPredecessor(CHAIN, 7), "A second removal finds nothing");
			assertEquals(OptionalInt.empty(), index.getChainPredecessor(CHAIN, 7));
		}

		@Test
		@DisplayName("a partition without a predecessor entry is not chained, whatever else the index records about it")
		void shouldKeepAPartitionWithoutPredecessorUnchained() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = populate(index, 2, 50);
			// partition 17 and 34 have an owner and are left out of the chain by the fixture
			assertTrue(index.getPartitionOwner(17).isPresent());
			assertEquals(OptionalInt.empty(), index.getChainPredecessor(CHAIN, 17));
			assertEquals(OptionalInt.empty(), index.getChainPredecessor(CHAIN, 34));
			// chained in one chain only: partition 3 is in both, partition 1 only in the first
			assertTrue(index.getChainPredecessor(LOCALIZED_CHAIN, 3).isPresent());
			assertEquals(OptionalInt.empty(), index.getChainPredecessor(LOCALIZED_CHAIN, 1));
			// a chain of the other key or a partition of another slot never stands in for a missing entry
			index.putRepresentativeTuple(34, new Serializable[]{"gallery"});
			assertEquals(OptionalInt.empty(), index.getChainPredecessor(CHAIN, 34));
			assertMatchesModels(index, models, 100);
		}

		@Test
		@DisplayName("rewriting the same predecessor leaves the index clean")
		void shouldNotDirtyTheIndexWhenThePredecessorIsUnchanged() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			index.putChainPredecessor(CHAIN, 3, 42);
			index.resetDirty();
			index.putChainPredecessor(CHAIN, 3, 42);
			assertTrue(flush(index).isEmpty(), "An unchanged predecessor must not make the index emit anything");
		}

		@Test
		@DisplayName("random changes of both chains are kept exactly")
		void shouldFollowRandomChanges() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = populate(index, 4, 60);
			churn(index, models, new Random(17), 3_000, 300);
			assertMatchesModels(index, models, 300);
		}
	}

	@Nested
	@DisplayName("Transactional memory")
	@Tag(TRANSACTION)
	class TransactionalMemory {

		@Test
		@DisplayName("a commit produces a new instance with the new chains and leaves the original untouched")
		void shouldCommitPredecessorsIntoANewInstance() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = populate(index, 3, 50);
			index.resetDirty();
			final Map<AttributeIndexKey, Map<Integer, Integer>> before = copyOf(models);

			assertStateAfterCommit(
				index,
				original -> {
					churn(original, models, new Random(3), 600, 200);
					// the transaction sees its own writes
					assertMatchesModels(original, models, 200);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertMatchesModels(original, before, 200);
					assertMatchesModels(committed, models, 200);
				}
			);
		}

		@Test
		@DisplayName("declaring and retiring chain keys inside a transaction commits cleanly")
		void shouldCommitDeclarationAndRetirement() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareChainKey(CHAIN);
			index.putChainPredecessor(CHAIN, 1, HEAD);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> {
					original.declareChainKey(LOCALIZED_CHAIN);
					original.putChainPredecessor(LOCALIZED_CHAIN, 1, 5);
					original.retireChainKey(CHAIN);
				},
				(original, committed) -> {
					assertEquals(OptionalInt.of(HEAD), original.getChainPredecessor(CHAIN, 1));
					assertThrows(GenericEvitaInternalError.class, () -> original.getChainPredecessor(LOCALIZED_CHAIN, 1));
					assertThrows(GenericEvitaInternalError.class, () -> committed.getChainPredecessor(CHAIN, 1));
					assertEquals(OptionalInt.of(5), committed.getChainPredecessor(LOCALIZED_CHAIN, 1));
				}
			);
		}

		@Test
		@DisplayName("a rollback leaves the original exactly as it was")
		void shouldLeaveTheOriginalOnRollback() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = populate(index, 2, 50);
			index.resetDirty();

			assertStateAfterRollback(
				index,
				original -> {
					churn(original, copyOf(models), new Random(4), 300, 100);
					original.retireChainKey(LOCALIZED_CHAIN);
				},
				(original, committed) -> {
					assertNull(committed);
					assertMatchesModels(original, models, 100);
					assertTrue(flush(original).isEmpty(), "A rolled-back index must not be dirty");
				}
			);
		}

		@Test
		@DisplayName("removing the layers discards every uncommitted predecessor")
		void shouldDiscardUncommittedPredecessorsWhenTheLayersAreRemoved() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = populate(index, 2, 50);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> {
					churn(original, copyOf(models), new Random(5), 300, 100);
					original.declareChainKey(new AttributeIndexKey(REFERENCE_NAME, "fresh", null));
					original.removeLayer(Transaction.getTransactionalLayerMaintainer());
					assertMatchesModels(original, models, 100);
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
		@DisplayName("a rollback restores the chains and the declared keys")
		void shouldRestoreChainsOnRollback() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models = new HashMap<>();
			assertWarmUpSavepointRollbackRestores(
				index,
				baseline -> models.putAll(populate(baseline, 3, 40)),
				restored -> {
					final Map<AttributeIndexKey, Map<Integer, OptionalInt>> chains = new HashMap<>();
					for (final AttributeIndexKey key : List.of(CHAIN, LOCALIZED_CHAIN)) {
						final Map<Integer, OptionalInt> chain = new TreeMap<>();
						for (int partition = 1; partition <= 150; partition++) {
							chain.put(partition, restored.getChainPredecessor(key, partition));
						}
						chains.put(key, chain);
					}
					return chains;
				},
				savepoint -> {
					churn(savepoint, copyOf(models), new Random(6), 400, 150);
					savepoint.retireChainKey(CHAIN);
					savepoint.declareChainKey(CHAIN);
				}
			);
			assertMatchesModels(index, models, 150);
			final VirtualReferencePartitionIndexStoragePart root = rootOf(flush(index));
			assertEquals(2, root.getChainPredecessorSlots().length);
			assertNotEquals(
				root.getChainPredecessorSlots()[0].slotId(), root.getChainPredecessorSlots()[1].slotId()
			);
		}
	}
}
