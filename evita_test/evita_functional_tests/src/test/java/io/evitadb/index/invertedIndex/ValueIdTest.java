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

package io.evitadb.index.invertedIndex;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.LeafPage;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the stable **value id** contract of {@link InvertedIndex}: that ids exist only when a consumer asks for
 * them, that they name a distinct value for as long as it lives, that they survive every structural operation the
 * bucket tree can perform on the value that owns them, and that they are never reused once their value dies.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Stable value ids in the shared value tree")
class ValueIdTest {

	/**
	 * Stands in for the trigram substring index that will be the first production consumer — the seam takes a name, so
	 * a test consumer needs no production type to exist.
	 */
	private static final String TEST_CONSUMER = "value-id-test";

	/**
	 * The leaf block size of an {@link InvertedIndex} (`InvertedIndex.VALUE_BLOCK_SIZE`). Tests that must provoke a
	 * real leaf split insert more than this many distinct values.
	 */
	private static final int LEAF_BLOCK_SIZE = 256;

	/**
	 * The first stable leaf id a shared value tree hands out (`TransactionalBucketBPlusTree.FIRST_LEAF_ID`).
	 */
	private static final long FIRST_LEAF_ID = 1L;

	/**
	 * Builds a fresh, empty inverted index over `Integer` keys in natural order.
	 *
	 * @return the empty index
	 */
	@Nonnull
	private static InvertedIndex emptyIndex() {
		return new InvertedIndex(Integer.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0);
	}

	/**
	 * Builds an index already carrying value ids, holding one record per value for `values`.
	 *
	 * @param values the values to insert, one record each
	 * @return the populated, id-carrying index
	 */
	@Nonnull
	private static InvertedIndex indexWithIds(@Nonnull int... values) {
		final InvertedIndex index = emptyIndex();
		index.attachValueIdConsumer(TEST_CONSUMER);
		for (int i = 0; i < values.length; i++) {
			index.addRecord(values[i], i + 1);
		}
		return index;
	}

	/**
	 * Runs `insideTransaction` with a real transaction bound to the calling thread, rolling it back afterwards. The
	 * tests that use it assert on what a reader sees WHILE the transaction is still open — never on what it leaves
	 * behind — so the transaction exists only to make the writes performed inside it transaction-local.
	 *
	 * @param insideTransaction the body to run with a transaction on the thread
	 */
	private static void executeInsideTransaction(@Nonnull Runnable insideTransaction) {
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				new TransactionHandler() {
					@Override
					public void registerMutation(@Nonnull Mutation mutation) {
						// no mutation recording is needed for a structure-level test
					}

					@Override
					public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
						// unused - the transaction is always rolled back
					}

					@Override
					public void rollback(
						@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause
					) {
						// the writes made inside are discarded; only what was observed inside matters
					}
				},
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					insideTransaction.run();
				} finally {
					// closed in a finally so a failing assertion cannot leave a transaction bound to the thread and
					// poison every test that runs after it in the same fork
					transaction.setRollbackOnly();
					transaction.close();
				}
			}
		);
	}

	@Nested
	@DisplayName("The id column exists only when a consumer needs it")
	class ConsumerGate {

		@Test
		@DisplayName("a tree nobody asked ids of carries none")
		void shouldNotCarryValueIdsWithoutConsumer() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(10, 2);

			assertFalse(index.carriesValueIds());
			assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, index.getValueId(5));
			assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, index.getNextValueId());
			assertTrue(index.getValueIdConsumerNames().isEmpty());
		}

		@Test
		@DisplayName("registering a consumer switches the tree on and names it")
		void shouldCarryValueIdsAfterConsumerAttaches() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			index.addRecord(5, 1);

			assertTrue(index.carriesValueIds());
			assertEquals(ValueIdAllocator.FIRST_VALUE_ID, index.getValueId(5));
			assertEquals(Set.of(TEST_CONSUMER), index.getValueIdConsumerNames());
		}

		@Test
		@DisplayName("attaching twice is idempotent and does not renumber")
		void shouldNotRenumberWhenSameConsumerAttachesTwice() {
			final InvertedIndex index = indexWithIds(5, 10);
			final int idOfFive = index.getValueId(5);
			final int idOfTen = index.getValueId(10);

			index.attachValueIdConsumer(TEST_CONSUMER);

			assertEquals(idOfFive, index.getValueId(5));
			assertEquals(idOfTen, index.getValueId(10));
			assertEquals(Set.of(TEST_CONSUMER), index.getValueIdConsumerNames());
		}

		@Test
		@DisplayName("ids survive while any consumer still needs them")
		void shouldKeepValueIdsUntilLastConsumerDetaches() {
			final InvertedIndex index = indexWithIds(5, 10);
			index.attachValueIdConsumer("second-consumer");
			final int idOfFive = index.getValueId(5);

			index.detachValueIdConsumer(TEST_CONSUMER);

			assertTrue(index.carriesValueIds());
			assertEquals(idOfFive, index.getValueId(5));
			assertEquals(Set.of("second-consumer"), index.getValueIdConsumerNames());
		}

		@Test
		@DisplayName("the last consumer leaving takes the id column with it")
		void shouldDropValueIdsWhenLastConsumerDetaches() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			assertTrue(index.carriesValueIds());

			index.detachValueIdConsumer(TEST_CONSUMER);

			assertFalse(index.carriesValueIds());
			assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, index.getNextValueId());
			assertTrue(index.getValueIdConsumerNames().isEmpty());
		}

		@Test
		@DisplayName("a tree that already holds values refuses to switch ids on")
		void shouldRefuseEnablingValueIdsOnPopulatedTree() {
			final InvertedIndex index = emptyIndex();
			for (int value = 0; value < 10; value++) {
				index.addRecord(value, value + 1);
			}

			// back-filling the values already present writes id columns into leaves nothing marks dirty, so the ids
			// would never reach disk and a reload would hand those very values different ones
			assertThrows(GenericEvitaInternalError.class, () -> index.attachValueIdConsumer(TEST_CONSUMER));

			assertFalse(index.carriesValueIds(), "a refused attach must not leave the tree half switched on");
			assertTrue(index.getValueIdConsumerNames().isEmpty(), "a refused attach must not record its consumer");
		}

		@Test
		@DisplayName("a tree that already holds values keeps its id column when its last consumer leaves")
		void shouldKeepIdColumnWhenLastConsumerLeavesPopulatedTree() {
			final InvertedIndex index = indexWithIds(5, 10);
			final int idOfFive = index.getValueId(5);

			// dropping the id columns of a populated tree dirties no leaf page, so the ids already written would
			// outlive the drop while the root's high-water went back to unassigned - the pairing the loader refuses
			// outright. The withdrawal of a filter accelerator is deliberately legal and arrives on an ordinary entity
			// write, so it cannot be refused either: the CONSUMER leaves and the column stays behind, unread
			index.detachValueIdConsumer(TEST_CONSUMER);

			assertTrue(
				index.carriesValueIds(),
				"the id column of a populated tree must survive its last consumer, or the persisted pages and the " +
					"root would disagree on the next open"
			);
			assertEquals(idOfFive, index.getValueId(5), "the ids themselves must not move either");
			assertTrue(
				index.getValueIdConsumerNames().isEmpty(),
				"the consumer itself must be gone - nothing reads the column any more"
			);
		}

		@Test
		@DisplayName("inside a transaction the last consumer leaves the id column standing, even on an empty tree")
		void shouldKeepIdColumnWhenLastConsumerLeavesInsideTransaction() {
			// the withdrawal of a filter accelerator is observed by the next ordinary entity write, which on a
			// transactional catalog runs with a transaction bound - so this is the shape every production drop
			// arrives in. The allocator and the tree's minter are owner-resident rather than transactional: clearing
			// them writes straight through to the LIVE index, and an abort would then restore the consumer's own
			// structures around a tree that had lost the ids they post against, failing the next upsert on the tree's
			// own premise. The consumer is unregistered - which is what stops its cost from outliving it - and the
			// column is left to the empty-tree drop outside a transaction
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);

			executeInsideTransaction(() -> {
				index.detachValueIdConsumer(TEST_CONSUMER);

				assertTrue(
					index.carriesValueIds(),
					"an aborting transaction must not be able to take the ids away from the live index"
				);
				assertTrue(index.getValueIdConsumerNames().isEmpty(), "the consumer itself leaves regardless");
			});
		}

		@Test
		@DisplayName("detaching a consumer that never attached changes nothing")
		void shouldIgnoreDetachOfConsumerThatNeverAttached() {
			// a tree that was never switched on has no registry at all, so the detach must return before it can reach
			// anything that would refuse it
			final InvertedIndex neverEnabled = emptyIndex();
			neverEnabled.detachValueIdConsumer("nobody");
			assertFalse(neverEnabled.carriesValueIds());
			assertTrue(neverEnabled.getValueIdConsumerNames().isEmpty());

			// and on a populated id-carrying tree the unknown name unregisters nothing, so the registry never reports
			// the transition to an unclaimed column and the tree is not touched at all
			final InvertedIndex enabled = indexWithIds(5, 10);
			final int idOfFive = enabled.getValueId(5);

			enabled.detachValueIdConsumer("nobody");

			assertTrue(enabled.carriesValueIds());
			assertEquals(idOfFive, enabled.getValueId(5));
			assertEquals(Set.of(TEST_CONSUMER), enabled.getValueIdConsumerNames());
		}

		@Test
		@DisplayName("a consumer with a blank name is refused without switching the tree on behind it")
		void shouldRejectBlankValueIdConsumerName() {
			final InvertedIndex index = indexWithIds(5, 10);
			final int idOfFive = index.getValueId(5);

			assertThrows(GenericEvitaInternalError.class, () -> index.attachValueIdConsumer("  "));

			// the id column is switched on BEFORE the registry records the consumer, so a refused name must leave the
			// two still agreeing - a tree that carries ids nobody is registered for could never be switched off again
			assertTrue(index.carriesValueIds(), "a refused attach must not take the id column away either");
			assertEquals(idOfFive, index.getValueId(5));
			assertEquals(Set.of(TEST_CONSUMER), index.getValueIdConsumerNames());
		}

		@Test
		@DisplayName("dropping the ids leaves the root's high-water mark due for a rewrite")
		void shouldMarkHighWaterDirtyWhenValueIdsAreDropped() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			index.addRecord(5, 1);
			index.markValueIdHighWaterEmitted();
			assertFalse(index.isValueIdHighWaterDirty(), "a root that was just emitted is not due for a rewrite");

			// emptying the tree is what makes the last consumer's departure legal
			index.removeRecord(5, 1);
			index.detachValueIdConsumer(TEST_CONSUMER);

			// the last emitted root claimed a high-water mark and this tree no longer has one, so the root MUST be
			// rewritten. Left alone, the persisted root would go on claiming ids its leaf pages no longer carry, and
			// the loader refuses exactly that pairing rather than loading it - the catalog stops opening at all
			assertTrue(
				index.isValueIdHighWaterDirty(),
				"a tree whose ids were dropped must force the root out, or the persisted root outlives the ids"
			);
		}

		@Test
		@DisplayName("the consumer names are a snapshot rather than a live view of the registry")
		void shouldHandOutConsumerNamesAsSnapshot() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			final Set<String> namesBeforeSecond = index.getValueIdConsumerNames();

			index.attachValueIdConsumer("second-consumer");

			assertEquals(
				Set.of(TEST_CONSUMER), namesBeforeSecond,
				"a set handed out earlier must not observe a consumer that registered afterwards"
			);
			assertEquals(Set.of(TEST_CONSUMER, "second-consumer"), index.getValueIdConsumerNames());
		}
	}

	@Nested
	@DisplayName("Ids are minted per distinct value, never per write")
	class MintingDiscipline {

		@Test
		@DisplayName("distinct values get distinct ids, ascending from the first")
		void shouldMintOneIdPerDistinctValue() {
			final InvertedIndex index = indexWithIds(30, 10, 20);

			assertEquals(ValueIdAllocator.FIRST_VALUE_ID, index.getValueId(30));
			assertEquals(ValueIdAllocator.FIRST_VALUE_ID + 1, index.getValueId(10));
			assertEquals(ValueIdAllocator.FIRST_VALUE_ID + 2, index.getValueId(20));
			assertEquals(ValueIdAllocator.FIRST_VALUE_ID + 3, index.getNextValueId());
		}

		@Test
		@DisplayName("adding another record to an existing value mints nothing")
		void shouldNotMintWhenRecordJoinsExistingValue() {
			final InvertedIndex index = indexWithIds(5);
			final int idOfFive = index.getValueId(5);
			final int highWaterBefore = index.getNextValueId();

			index.addRecord(5, 100);
			index.addRecord(5, 101);
			index.addRecord(5, 100);

			assertEquals(highWaterBefore, index.getNextValueId(), "churn on an existing value must cost no id");
			assertEquals(idOfFive, index.getValueId(5), "churn on an existing value must not renumber it");
		}

		@Test
		@DisplayName("a value that dies leaves a hole its id is never handed out again from")
		void shouldNotReuseValueIdOfRemovedValue() {
			final InvertedIndex index = indexWithIds(5, 10);
			final int idOfTen = index.getValueId(10);

			index.removeRecord(10, 2);
			assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, index.getValueId(10));

			index.addRecord(10, 99);
			assertNotEquals(idOfTen, index.getValueId(10), "a re-inserted value must not inherit the dead id");
			assertTrue(index.getValueId(10) > idOfTen);
		}

		@Test
		@DisplayName("the id space refuses to wrap when it is exhausted")
		void shouldThrowWhenValueIdSpaceIsExhaustedOutsideTransaction() {
			final ValueIdAllocator allocator = new ValueIdAllocator(Integer.MAX_VALUE);

			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, allocator::allocate);

			// how many ids were burnt is the one fact that tells an operator whether this is genuine exhaustion or a
			// counter that ran away, so the refusal has to carry it
			assertTrue(
				error.getPrivateMessage().contains(String.valueOf(Integer.MAX_VALUE)),
				"the refusal must say how many ids have been minted, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("the id space refuses to wrap inside a transaction too, in exactly the same words")
		void shouldThrowWhenValueIdSpaceIsExhaustedInsideTransaction() {
			// the exhaustion guard exists twice - once on the direct single-writer branch asserted above, once on the
			// per-transaction diff layer - and only the layered one is reachable while a transaction is bound to the
			// thread. Asserting one of them says nothing about the other
			final GenericEvitaInternalError outside = assertThrows(
				GenericEvitaInternalError.class, new ValueIdAllocator(Integer.MAX_VALUE)::allocate
			);

			executeInsideTransaction(() -> {
				final GenericEvitaInternalError inside = assertThrows(
					GenericEvitaInternalError.class, new ValueIdAllocator(Integer.MAX_VALUE)::allocate
				);

				// one condition, one shape: neither a caller writing a handler nor an operator matching on the text
				// should have to know whether a transaction happened to be open when the space ran out
				assertEquals(outside.getPrivateMessage(), inside.getPrivateMessage());
				assertEquals(outside.getPublicMessage(), inside.getPublicMessage());
			});
		}

		@Test
		@DisplayName("an allocator cannot be restored below the first id it could ever have handed out")
		void shouldRefuseRestoringAllocatorBelowFirstValueId() {
			// the unassigned sentinel is not a position the sequence can occupy: restoring there would hand the very
			// next value an id that every empty column slot already carries
			assertThrows(
				GenericEvitaInternalError.class, () -> new ValueIdAllocator(ValueIdAllocator.UNASSIGNED_VALUE_ID)
			);
		}
	}

	@Nested
	@DisplayName("Ids survive every structural operation on the tree")
	class StructuralStability {

		@Test
		@DisplayName("a leaf split keeps every value's id")
		void shouldPreserveValueIdsAcrossLeafSplit() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			// insert ascending so the ids are minted in key order, then verify they are still attached to the SAME
			// value after the tree has been re-paginated by several splits
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value, value + 1);
			}

			for (int value = 0; value < valueCount; value++) {
				assertEquals(
					ValueIdAllocator.FIRST_VALUE_ID + value, index.getValueId(value),
					"value " + value + " lost its id across the splits"
				);
			}
		}

		@Test
		@DisplayName("ids follow their value when an insert shifts it to a new slot")
		void shouldPreserveValueIdsWhenSlotsShift() {
			// insert descending: every insert lands at slot 0 and shifts every existing bucket one slot right, so an
			// id column that failed to shift in lockstep would misattribute every value but the newest
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			final int valueCount = 50;
			for (int i = 0; i < valueCount; i++) {
				index.addRecord(valueCount - i, i + 1);
			}

			for (int i = 0; i < valueCount; i++) {
				assertEquals(
					ValueIdAllocator.FIRST_VALUE_ID + i, index.getValueId(valueCount - i),
					"value " + (valueCount - i) + " was misattributed after the slot shifts"
				);
			}
		}

		@Test
		@DisplayName("leaf merges and steals keep every surviving value's id")
		void shouldPreserveValueIdsAcrossLeafMerge() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value, value + 1);
			}
			// drain most of the tree so the leaves rebalance and merge repeatedly
			for (int value = 0; value < valueCount; value++) {
				if (value % 3 != 0) {
					index.removeRecord(value, value + 1);
				}
			}

			for (int value = 0; value < valueCount; value += 3) {
				assertEquals(
					ValueIdAllocator.FIRST_VALUE_ID + value, index.getValueId(value),
					"value " + value + " lost its id across the merges"
				);
			}
		}
	}

	@Nested
	@DisplayName("Ids follow the transaction they were minted in")
	class TransactionalBehaviour {

		@Test
		@DisplayName("a committed transaction keeps its ids and its high-water mark")
		void shouldKeepValueIdsAfterCommit() {
			final InvertedIndex index = indexWithIds(5, 10);
			final int idOfFive = index.getValueId(5);

			assertStateAfterCommit(
				index,
				original -> original.addRecord(15, 3),
				(original, committed) -> {
					assertTrue(committed.carriesValueIds());
					assertEquals(idOfFive, committed.getValueId(5), "an untouched value must keep its id");
					assertEquals(ValueIdAllocator.FIRST_VALUE_ID + 2, committed.getValueId(15));
					assertEquals(ValueIdAllocator.FIRST_VALUE_ID + 3, committed.getNextValueId());
				}
			);
		}

		@Test
		@DisplayName("a committed tree mints from where the commit left off")
		void shouldContinueMintingAfterCommit() {
			final InvertedIndex index = indexWithIds(5);

			assertStateAfterCommit(
				index,
				original -> original.addRecord(10, 2),
				(original, committed) -> {
					committed.addRecord(15, 3);
					assertEquals(ValueIdAllocator.FIRST_VALUE_ID + 2, committed.getValueId(15));
					assertNotEquals(committed.getValueId(10), committed.getValueId(15));
				}
			);
		}

		@Test
		@DisplayName("a rolled-back transaction leaves neither the value nor its id behind")
		void shouldDiscardValueIdsAfterRollback() {
			final InvertedIndex index = indexWithIds(5);
			final int highWaterBefore = index.getNextValueId();

			assertStateAfterRollback(
				index,
				original -> original.addRecord(10, 2),
				(original, committed) -> {
					assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, original.getValueId(10));
					assertEquals(
						highWaterBefore, original.getNextValueId(),
						"an aborted transaction must give its minted ids back"
					);
				}
			);
		}

		@Test
		@DisplayName("dropping the index's layers sweeps the allocator's layer with them")
		void shouldSweepAllocatorLayerWhenIndexLayerIsRemoved() {
			// `assertStateAfterRollback` cannot cover this: its handler's rollback does nothing, so a layer the index
			// forgot to sweep is never noticed. Only the maintainer's own sweep check sees it — and an unswept layer is
			// a genuine defect (`StaleTransactionMemoryException`: part of a transaction's changes would be lost).
			final InvertedIndex index = indexWithIds(5);
			Transaction.executeInTransactionIfProvided(
				new Transaction(
					UUID.randomUUID(),
					new TransactionHandler() {
						@Override
						public void registerMutation(@Nonnull Mutation mutation) {
							// no mutation recording is needed for a structure-level test
						}

						@Override
						public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
							index.removeLayer(transactionalLayer);
							transactionalLayer.verifyLayerWasFullySwept();
						}

						@Override
						public void rollback(
							@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause
						) {
							// unused - the sweep is asserted on the commit path
						}
					},
					false
				),
				() -> {
					try (Transaction transaction = Transaction.getTransaction().orElseThrow()) {
						// mints a value id, which creates the allocator's diff layer
						index.addRecord(10, 2);
					}
				}
			);
		}

		@Test
		@DisplayName("a savepoint rollback rewinds the ids with the values")
		void shouldRestoreValueIdsOnSavepointRollback() {
			final InvertedIndex index = indexWithIds(5, 10);

			assertSavepointRollbackRestores(
				index,
				original -> original.addRecord(15, 3),
				original -> original.getValueId(5) + "/" + original.getValueId(10) + "/" + original.getValueId(15),
				original -> {
					original.addRecord(20, 4);
					original.addRecord(25, 5);
				}
			);
		}

		@Test
		@DisplayName("a committed savepoint keeps the ids it minted")
		void shouldKeepValueIdsOnSavepointCommit() {
			final InvertedIndex index = indexWithIds(5, 10);

			assertSavepointCommitKeeps(
				index,
				original -> original.addRecord(15, 3),
				original -> original.getValueId(5) + "/" + original.getValueId(15) + "/" + original.getValueId(20),
				original -> original.addRecord(20, 4)
			);
		}

		@Test
		@DisplayName("a transaction that drains most of the tree keeps every survivor's id")
		void shouldPreserveValueIdsWhenTransactionMergesLeaves() {
			// Every other structural case here rebalances OUTSIDE a transaction, where a leaf is mutated in place, and
			// every transactional case only inserts. Draining four leaf blocks down to a fifth forces steals and merges
			// while a diff layer is bound, which is the only way the id column's transactional branches run at all.
			//
			// Calibration: dropping the `copyValueIdRange(..., layer.valueIds, ...)` calls of `stealFromLeft` and
			// `mergeWithLeft` makes this fail on the very first survivor - the values move between leaves while their
			// ids stay behind in the slots they used to occupy, so a survivor answers with an id minted for some
			// unrelated value elsewhere in the tree. It is the only test in the suite that notices that change.
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			final int valueCount = LEAF_BLOCK_SIZE * 4;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value, value + 1);
			}
			final Map<Integer, Integer> idByValue = CollectionUtils.createHashMap(valueCount / 5 + 1);
			for (int value = 0; value < valueCount; value += 5) {
				idByValue.put(value, index.getValueId(value));
			}

			assertStateAfterCommit(
				index,
				original -> {
					// four out of every five values die, so the surviving fifth cannot stay where it was: the leaves
					// steal from each other and then merge repeatedly all the way down the tree
					for (int value = 0; value < valueCount; value++) {
						if (value % 5 != 0) {
							original.removeRecord(value, value + 1);
						}
					}
				},
				(original, committed) -> {
					for (final Map.Entry<Integer, Integer> survivor : idByValue.entrySet()) {
						final int value = survivor.getKey();
						final int expectedId = survivor.getValue();
						assertEquals(
							expectedId, committed.getValueId(value),
							"value " + value + " lost its id across the rebalancing the transaction caused"
						);
						assertEquals(
							value, committed.getValueById(expectedId),
							"id " + expectedId + " no longer names value " + value + " after the commit"
						);
					}
				}
			);
		}

		@Test
		@DisplayName("a commit that mints nothing carries the allocator forward by reference")
		void shouldCarryAllocatorByReferenceWhenCommitMintsNothing() {
			// every other transactional case here mints, so the allocator always has a diff layer to merge. A commit
			// whose only write joins an EXISTING value creates no layer at all, and the merge then has to carry the
			// allocator across untouched rather than rebuild it from a layer that is not there
			final InvertedIndex index = indexWithIds(5, 10);
			final int highWaterBefore = index.getNextValueId();

			assertStateAfterCommit(
				index,
				original -> original.addRecord(5, 99),
				(original, committed) -> {
					assertTrue(committed.carriesValueIds(), "a commit that minted nothing must not drop the ids");
					assertEquals(
						highWaterBefore, committed.getNextValueId(),
						"a commit that minted nothing must not move the high-water mark"
					);
					assertEquals(ValueIdAllocator.FIRST_VALUE_ID, committed.getValueId(5));

					committed.addRecord(20, 4);
					assertEquals(
						highWaterBefore, committed.getValueId(20),
						"the carried-forward allocator must keep minting from where it stood"
					);
				}
			);
		}

		@Test
		@DisplayName("attaching to a tree the transaction has already touched still stamps the ids it mints")
		void shouldStampValueIdsWhenConsumerAttachesToALeafAlreadyTouchedByTheTransaction() {
			final InvertedIndex index = emptyIndex();

			executeInsideTransaction(() -> {
				// the tree ends up empty again - so the attach is legal - but the leaf now carries a transactional
				// diff layer of its own. The back-fill that switches the ids on allocates the column on the BASE
				// leaf, and unless the layer is seeded with it too, the very next stamp reaches through the layer and
				// finds no column there
				index.addRecord(5, 1);
				index.removeRecord(5, 1);

				index.attachValueIdConsumer(TEST_CONSUMER);
				index.addRecord(10, 2);

				assertEquals(
					ValueIdAllocator.FIRST_VALUE_ID, index.getValueId(10),
					"a value inserted after the attach must carry the id the attach's minter handed it"
				);
			});
		}
	}

	@Nested
	@DisplayName("Ids resolve back to the values they name")
	class Resolution {

		@Test
		@DisplayName("every id resolves to its own value, and unknown ids to nothing")
		void shouldResolveEveryValueId() {
			final InvertedIndex index = indexWithIds(30, 10, 20);

			for (final int value : new int[]{10, 20, 30}) {
				assertEquals(value, index.getValueById(index.getValueId(value)));
			}
			assertNull(index.getValueById(ValueIdAllocator.UNASSIGNED_VALUE_ID));
			assertNull(index.getValueById(index.getNextValueId()), "an id never minted names nothing");
			assertNull(index.getValueById(-1));
		}

		@Test
		@DisplayName("a tree without ids resolves nothing")
		void shouldResolveNothingWithoutValueIds() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);

			assertNull(index.getValueById(ValueIdAllocator.FIRST_VALUE_ID));
		}

		@Test
		@DisplayName("resolution follows a value across the splits that move it")
		void shouldResolveAcrossLeafSplits() {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value, value + 1);
			}

			for (int value = 0; value < valueCount; value++) {
				assertEquals(
					value, index.getValueById(index.getValueId(value)),
					"value " + value + " does not resolve back to itself after the splits"
				);
			}
		}

		@Test
		@DisplayName("a dead value's id resolves to nothing rather than to whatever took its slot")
		void shouldNotResolveDeadValueIdToItsSuccessor() {
			final InvertedIndex index = indexWithIds(10, 20, 30);
			final int idOfTwenty = index.getValueId(20);
			// resolve WHILE the value is still alive, so the directory actually gains the entry that is about to go
			// stale - without this the entry would never exist and the test would pass without exercising anything
			assertEquals(20, index.getValueById(idOfTwenty));

			index.removeRecord(20, 2);

			// the entry is deliberately not swept, so this is exactly the case the per-hit validation in `valueOf`
			// exists to catch: the slot it still points at now holds a different value
			assertNull(index.getValueById(idOfTwenty));
			assertEquals(10, index.getValueById(index.getValueId(10)));
			assertEquals(30, index.getValueById(index.getValueId(30)));
		}

		@Test
		@DisplayName("a reverse lookup is refused while a transaction is open, instead of under-reporting")
		void shouldRefuseResolvingValueIdInsideTransaction() {
			final InvertedIndex index = indexWithIds(10, 20, 30);
			final int idOfTen = index.getValueId(10);
			// force the directory into existence outside any transaction, so what the transaction below meets is a
			// built directory - otherwise the "nothing was ever built" branch would answer and the test prove nothing
			assertEquals(10, index.getValueById(idOfTen));

			executeInsideTransaction(() -> {
				index.addRecord(40, 4);
				final int mintedInside = index.getValueId(40);
				assertNotEquals(
					ValueIdAllocator.UNASSIGNED_VALUE_ID, mintedInside,
					"the forward lookup must see the id the open transaction minted, or the refusals below are "
						+ "asserted against an index that changed nothing"
				);

				assertThrows(GenericEvitaInternalError.class, () -> index.getValueById(mintedInside));
				// an id minted BEFORE the transaction is refused just the same: the refusal is about the state the
				// directory addresses, not about which transaction minted the id
				assertThrows(GenericEvitaInternalError.class, () -> index.getValueById(idOfTen));
			});

			assertEquals(10, index.getValueById(idOfTen), "the refusal must be scoped to the open transaction");
		}

		@Test
		@DisplayName("a commit keeps the leaves' identity instead of renumbering or restarting it")
		void shouldKeepLeafIdentityAcrossCommit() {
			// Leaf-id stability has no behavioural symptom: losing it still resolves correctly, it just burns the id
			// space and leaves the directory full of entries under ids nothing points at. So this asserts on the leaf
			// id counter directly rather than on any answer
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			// keys are strided so the transaction below can insert BETWEEN them and thereby dirty every leaf, not
			// just the last one - a transaction that only appends rebuilds one leaf and would not notice the loss
			final int valueCount = LEAF_BLOCK_SIZE * 8;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value * 10, value + 1);
			}
			assertEquals(0, index.getValueById(index.getValueId(0)));
			final long nextLeafIdBefore = index.getNextLeafId();
			final long leafCountBefore = nextLeafIdBefore - ValueIdTest.FIRST_LEAF_ID;
			assertTrue(leafCountBefore > 8, "the fixture must span many leaves, not one");

			assertStateAfterCommit(
				index,
				original -> {
					// one insert per ~128 keys, so essentially every leaf is rebuilt by the merge while only a
					// handful of them actually split
					for (int value = 0; value < valueCount; value += 128) {
						original.addRecord(value * 10 + 5, valueCount + value + 1);
					}
				},
				(original, committed) -> {
					assertTrue(
						committed.getNextLeafId() >= nextLeafIdBefore,
						"leaf ids are never reused, so a commit must never restart their numbering - was "
							+ committed.getNextLeafId() + ", expected at least " + nextLeafIdBefore
					);
					assertTrue(
						committed.getNextLeafId() - nextLeafIdBefore < leafCountBefore / 2,
						"a commit must mint ids only for genuinely new leaves, not renumber the ones it carried "
							+ "forward - minted " + (committed.getNextLeafId() - nextLeafIdBefore) + " ids over "
							+ leafCountBefore + " existing leaves"
					);
				}
			);
		}

		@Test
		@DisplayName("resolution survives a commit that re-shells the leaves")
		void shouldResolveAfterCommit() {
			// MANY leaves on purpose: with a single leaf, leaf ids could be re-minted from scratch on every commit
			// and resolution would still work by accident. Across many leaves a re-minted id collides with one a
			// carried-forward leaf already holds, and the loser's values stop resolving
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value, value + 1);
			}
			// force the directory into existence before the commit, so the commit is genuinely updating one
			assertEquals(0, index.getValueById(index.getValueId(0)));

			assertStateAfterCommit(
				index,
				original -> original.addRecord(valueCount, valueCount + 1),
				(original, committed) -> {
					for (int value = 0; value <= valueCount; value++) {
						assertEquals(
							value, committed.getValueById(committed.getValueId(value)),
							"value " + value + " does not resolve after the commit"
						);
					}
				}
			);
		}

		@Test
		@DisplayName("a commit leaves the previous version resolving against its own directory")
		void shouldKeepPreviousVersionResolvingAfterCommitRebuildsTheDirectory() {
			// The directory is built once per published version and is what buys MVCC here without a diff layer, so
			// the two versions must own separate location arrays. The commit-merge rebuild reuses the entries of the
			// leaves it carried forward, which means it starts from the PREVIOUS version's array.
			//
			// Calibration: dropping the `Arrays.copyOf` from `TransactionalBucketBPlusTree#rebuildValueIdDirectory`
			// makes this fail. The rebuild then stamps its slots straight into the array the previous version's
			// published directory still hands out, under leaf ids that are stable across the merge, so each read
			// repairs its own version by breaking the other's - which is why the two are read in turn below rather
			// than once each. Reading only one version cannot fail, because the lazy catch-up in `getValueById`
			// silently rebuilds whichever version is asked first.
			//
			// That copy is also what lets the merge carry the previous array BY REFERENCE rather than copying it a
			// second time: nothing writes into a published location array any more, which is the same property that
			// closes the reader-versus-rebuild window (see `ValueIdDirectory`).
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			// strided keys so the transaction below can insert BETWEEN them and dirty many leaves rather than only the
			// last one - a commit that merely appends re-stamps one leaf and would not disturb the older version
			final int valueCount = LEAF_BLOCK_SIZE * 4;
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value * 10, value + 1);
			}
			final int[] idsBefore = new int[valueCount];
			for (int value = 0; value < valueCount; value++) {
				idsBefore[value] = index.getValueId(value * 10);
			}
			// force the previous version's directory into existence BEFORE the commit, so what the commit could
			// corrupt is a directory that was really built rather than one nothing had asked for yet
			assertEquals(0, index.getValueById(idsBefore[0]));

			assertStateAfterCommit(
				index,
				original -> {
					for (int value = 0; value < valueCount; value += 32) {
						original.addRecord(value * 10 + 5, valueCount + value + 1);
					}
				},
				(original, committed) -> {
					// the older version first - the one a long-running query still holds
					assertResolvesEveryValue(original, idsBefore, valueCount, "the previously published version");
					// then the committed one, which reading the older version must not have disturbed
					for (int value = 0; value < valueCount; value++) {
						assertEquals(
							value * 10, committed.getValueById(committed.getValueId(value * 10)),
							"value " + (value * 10) + " stopped resolving in the committed version after the "
								+ "previously published one had been read"
						);
					}
					// and the older version once more, to prove the two are not repairing themselves by turns at each
					// other's expense
					assertResolvesEveryValue(original, idsBefore, valueCount, "the previously published version");
				}
			);
		}

		/**
		 * Asserts that `index` resolves every one of `valueCount` strided values through the id it was minted under.
		 *
		 * @param index      the version to probe
		 * @param ids        the ids the values were minted under, indexed by value ordinal
		 * @param valueCount how many values to check
		 * @param version    names the version in the failure message
		 */
		private static void assertResolvesEveryValue(
			@Nonnull InvertedIndex index, @Nonnull int[] ids, int valueCount, @Nonnull String version
		) {
			for (int value = 0; value < valueCount; value++) {
				assertEquals(
					value * 10, index.getValueById(ids[value]),
					"value " + (value * 10) + " stopped resolving in " + version
				);
			}
		}
	}

	@Nested
	@DisplayName("Ids survive a persistence round trip")
	@Tag(STORAGE)
	class PersistenceRoundTrip {

		/**
		 * Sends `source` through the paged persistence boundary and reads it back.
		 *
		 * ONLY the buckets, their id column and the high-water mark make the crossing — there is no directory on the
		 * wire, and none is handed to the reload. Everything the returned index can answer about ids it therefore
		 * derived for itself.
		 *
		 * @param source the paged, id-carrying index to persist and reload
		 * @return the index as it comes back from its persisted pages
		 */
		@Nonnull
		private static InvertedIndex reloadThroughPages(@Nonnull InvertedIndex source) {
			final PageEmission<LeafPage> emission = source.collectChangedPages();
			final int[] orderedPageSequences = emission.orderedPageSequences();
			final Map<Integer, LeafPage> pagesBySequence = CollectionUtils.createHashMap(orderedPageSequences.length);
			for (final LeafPage page : emission.changedPages()) {
				pagesBySequence.put(page.pageSequence(), page);
			}
			final ValueToRecord[][] perPageBuckets = new ValueToRecord[orderedPageSequences.length][];
			final int[][] perPageValueIds = new int[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final LeafPage page = pagesBySequence.get(orderedPageSequences[i]);
				assertNotNull(page, "A fresh index must emit every one of its leaf pages.");
				perPageBuckets[i] = page.buckets();
				perPageValueIds[i] = page.valueIds();
			}

			final InvertedIndex reloaded = InvertedIndex.fromPersistedPages(
				Integer.class, orderedPageSequences, perPageBuckets, perPageValueIds,
				emission.highWaterPageSequence(), FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			reloaded.restoreValueIds(source.getNextValueId());
			return reloaded;
		}

		/**
		 * Builds a paged, id-carrying index holding `valueCount` distinct values, one record each.
		 *
		 * @param valueCount how many distinct values to insert
		 * @return the populated, id-carrying index
		 */
		@Nonnull
		private static InvertedIndex pagedIndexWithIds(int valueCount) {
			final InvertedIndex index = emptyIndex();
			index.attachValueIdConsumer(TEST_CONSUMER);
			for (int value = 0; value < valueCount; value++) {
				index.addRecord(value, value + 1);
			}
			assertTrue(index.isPaged(), "The fixture must be large enough to be persisted as leaf pages.");
			return index;
		}

		@Test
		@DisplayName("a paged index reloads with every id attached to the value it named")
		void shouldRestoreValueIdsOfPagedIndex() {
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			final InvertedIndex index = pagedIndexWithIds(valueCount);

			final InvertedIndex reloaded = reloadThroughPages(index);

			assertEquals(index.getNextValueId(), reloaded.getNextValueId());
			for (int value = 0; value < valueCount; value++) {
				assertEquals(
					index.getValueId(value), reloaded.getValueId(value),
					"value " + value + " did not survive the round trip with its id"
				);
			}
		}

		@Test
		@DisplayName("persisted pages whose id columns do not line up with them are refused")
		void shouldRefusePersistedPagesWhoseValueIdColumnsDoNotAlign() {
			final InvertedIndex index = pagedIndexWithIds(LEAF_BLOCK_SIZE * 3);
			final PageEmission<LeafPage> emission = index.collectChangedPages();
			final int[] orderedPageSequences = emission.orderedPageSequences();
			final Map<Integer, LeafPage> pagesBySequence = CollectionUtils.createHashMap(orderedPageSequences.length);
			for (final LeafPage page : emission.changedPages()) {
				pagesBySequence.put(page.pageSequence(), page);
			}
			final ValueToRecord[][] perPageBuckets = new ValueToRecord[orderedPageSequences.length][];
			// one id column short of the page list: the shape a caller reaches by pairing the pages of one generation
			// with the id columns of another, which would silently misattribute every value from the seam onwards
			final int[][] tooFewValueIdColumns = new int[orderedPageSequences.length - 1][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final LeafPage page = pagesBySequence.get(orderedPageSequences[i]);
				assertNotNull(page, "A fresh index must emit every one of its leaf pages.");
				perPageBuckets[i] = page.buckets();
				if (i < tooFewValueIdColumns.length) {
					tooFewValueIdColumns[i] = page.valueIds();
				}
			}

			assertThrows(
				GenericEvitaInternalError.class,
				() -> InvertedIndex.fromPersistedPages(
					Integer.class, orderedPageSequences, perPageBuckets, tooFewValueIdColumns,
					emission.highWaterPageSequence(), FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
				)
			);
		}

		@Test
		@DisplayName("an inline index reloads with its id column stamped back in key order")
		void shouldRestoreValueIdsOfInlineIndex() {
			final InvertedIndex index = indexWithIds(30, 10, 20, 40);
			assertFalse(index.isPaged(), "The fixture must be small enough to be persisted inline.");

			final InvertedIndex reloaded = new InvertedIndex(
				Integer.class, index.getValueToRecordBitmap(), FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder(), 0
			);
			reloaded.restoreValueIds(index.getNextValueId(), index.getValueIds());

			assertEquals(index.getNextValueId(), reloaded.getNextValueId());
			for (final int value : new int[]{10, 20, 30, 40}) {
				assertEquals(index.getValueId(value), reloaded.getValueId(value));
			}
		}

		@Test
		@DisplayName("the directory is derived state: nothing persists it, and a reload rebuilds it identically")
		void shouldRebuildDirectoryOnLoadWithoutPersistingIt() {
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			final InvertedIndex index = pagedIndexWithIds(valueCount);

			final InvertedIndex reloaded = reloadThroughPages(index);

			for (int value = 0; value < valueCount; value++) {
				final int valueId = index.getValueId(value);
				assertEquals(
					index.getValueById(valueId), reloaded.getValueById(valueId),
					"the rebuilt directory disagrees with the original for value " + value
				);
				assertEquals(value, reloaded.getValueById(valueId));
			}
			assertTrue(
				reloaded.getValueIdDirectoryHeapSizeInBytes() > 0,
				"the reloaded index must have actually built a directory, not merely answered from the tree"
			);
		}

		@Test
		@DisplayName("the directory's footprint is zero without ids and grows with the id space it addresses")
		void shouldReportDirectoryFootprintProportionalToTheIdSpace() {
			// the directory is the one term deliberately left out of the byte-exact heap walks, so a floor and a
			// direction are all the coverage it can have - and without them a figure of zero would look healthy
			assertEquals(
				0L, emptyIndex().getValueIdDirectoryHeapSizeInBytes(),
				"a tree nobody asked ids of must pay nothing for a directory it does not have"
			);

			final InvertedIndex smaller = reloadThroughPages(pagedIndexWithIds(LEAF_BLOCK_SIZE * 3));
			final InvertedIndex larger = reloadThroughPages(pagedIndexWithIds(LEAF_BLOCK_SIZE * 12));

			assertTrue(
				larger.getValueIdDirectoryHeapSizeInBytes() > smaller.getValueIdDirectoryHeapSizeInBytes(),
				"the location array is indexed BY value id, so four times the ids must occupy strictly more - was "
					+ larger.getValueIdDirectoryHeapSizeInBytes() + " against "
					+ smaller.getValueIdDirectoryHeapSizeInBytes()
			);
		}

		@Test
		@DisplayName("a reloaded index mints onward without colliding with what it restored")
		void shouldContinueMintingAfterReload() {
			final InvertedIndex index = indexWithIds(30, 10, 20);
			final InvertedIndex reloaded = new InvertedIndex(
				Integer.class, index.getValueToRecordBitmap(), FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder(), 0
			);
			reloaded.restoreValueIds(index.getNextValueId(), index.getValueIds());

			reloaded.addRecord(50, 4);

			final int mintedAfterReload = reloaded.getValueId(50);
			assertEquals(index.getNextValueId(), mintedAfterReload);
			for (final int value : new int[]{10, 20, 30}) {
				assertNotEquals(
					reloaded.getValueId(value), mintedAfterReload,
					"a restored sequence must never hand out an id it already gave to a live value"
				);
			}
		}
	}

	@Nested
	@DisplayName("Ids come back with the pages they were persisted in")
	class Restoration {

		@Test
		@DisplayName("restoring continues the sequence rather than restarting it")
		void shouldContinueSequenceAfterRestore() {
			final InvertedIndex index = emptyIndex();
			index.restoreValueIds(500);
			index.addRecord(5, 1);

			assertTrue(index.carriesValueIds());
			assertEquals(500, index.getValueId(5));
			assertEquals(501, index.getNextValueId());
		}

		@Test
		@DisplayName("restoring over an already id-carrying tree is refused")
		void shouldRefuseRestoringOverEnabledValueIds() {
			final InvertedIndex index = indexWithIds(5);

			assertThrows(GenericEvitaInternalError.class, () -> index.restoreValueIds(500));
		}
	}

	@Nested
	@DisplayName("Verifying candidates by their value ids")
	class CandidateVerification {

		@Test
		@DisplayName("the four-argument form without a byte pattern answers exactly as the three-argument one")
		void shouldDelegateToTheThreeArgumentFormWithoutAPattern() {
			// the three-argument overload is now a delegation, and nothing else pins it - so a later edit that gave
			// the two forms different defaults would go unnoticed
			final InvertedIndex index = indexWithIds(10, 20, 30, 40);
			final int[] candidates = {
				index.getValueId(10), index.getValueId(20), index.getValueId(30), index.getValueId(40)
			};
			final Predicate<Serializable> divisibleByTwenty = value -> ((Integer) value) % 20 == 0;

			final MatchedBuckets threeArgument = index.getRecordsOfValueIdsMatching(
				candidates, candidates.length, divisibleByTwenty
			);
			final MatchedBuckets fourArgument = index.getRecordsOfValueIdsMatching(
				candidates, candidates.length, divisibleByTwenty, null
			);

			assertEquals(2, threeArgument.recordSets().length, "two of the four values must match, or this is vacuous");
			assertMatchesAgree(threeArgument, fourArgument);
		}

		@Test
		@DisplayName("a byte pattern returns exactly what the predicate it stands in for returns")
		void shouldAnswerAByteFormExactlyAsItsPredicateWould() {
			// The byte form REPLACES the predicate where it applies rather than pre-filtering for it, so the two must
			// be the same question - an obligation the method's javadoc places on the caller and nothing at this level
			// checked. A String-keyed index is what makes the byte path apply at all: its key column stores UTF-8.
			final InvertedIndex index = stringIndexWithIds(
				"alpha item", "beta widget", "an item of gamma", "delta", "itemised"
			);
			final int[] candidates = valueIdsOf(
				index, "alpha item", "beta widget", "an item of gamma", "delta", "itemised"
			);
			final Predicate<Serializable> containsItem = value -> String.valueOf(value).contains("item");

			final MatchedBuckets viaPredicate = index.getRecordsOfValueIdsMatching(
				candidates, candidates.length, containsItem
			);
			final MatchedBuckets viaBytes = index.getRecordsOfValueIdsMatching(
				candidates, candidates.length, containsItem, "item".getBytes(StandardCharsets.UTF_8)
			);

			assertEquals(
				3, viaPredicate.recordSets().length,
				"three of the five values contain the pattern, so both a match and a rejection are exercised"
			);
			assertMatchesAgree(viaPredicate, viaBytes);
		}

		@Test
		@DisplayName("a key column that cannot match bytes falls back to the predicate rather than refusing")
		void shouldFallBackToThePredicateWhereBytesCannotBeMatched() {
			// The byte path applies only where the key column stores UTF-8, and an `Integer`-keyed tree does not - so
			// the pattern is ignored and the predicate answers. Nothing reaches this arm through the trigram path,
			// because every `String` key is given a front-coded column; it is reachable only by asking directly, and
			// it is what the requirement to pass a predicate ALONGSIDE a pattern exists for.
			final InvertedIndex index = indexWithIds(10, 20, 30);
			final int[] candidates = {index.getValueId(10), index.getValueId(20), index.getValueId(30)};
			final Predicate<Serializable> isTwenty = value -> (Integer) value == 20;

			final MatchedBuckets matched = index.getRecordsOfValueIdsMatching(
				candidates, candidates.length, isTwenty, "20".getBytes(StandardCharsets.UTF_8)
			);

			assertEquals(1, matched.recordSets().length, "the predicate, not the pattern, must have decided");
			assertArrayEquals(new int[]{2}, matched.recordSets()[0].getArray());
		}

		/**
		 * Builds a `String`-keyed index already carrying value ids, one record per value in insertion order. The key
		 * column of such a tree is the front-coded one, which is the only implementation able to match bytes.
		 *
		 * @param values the values to insert, one record each
		 * @return the populated, id-carrying index
		 */
		@Nonnull
		private static InvertedIndex stringIndexWithIds(@Nonnull String... values) {
			final AttributeIndexKey key = new AttributeIndexKey(null, "name", null);
			final InvertedIndex index = new InvertedIndex(
				String.class,
				FilterIndex.getNormalizer(String.class, 0),
				FilterIndex.getComparator(key, String.class),
				0
			);
			index.attachValueIdConsumer(TEST_CONSUMER);
			for (int i = 0; i < values.length; i++) {
				index.addRecord(values[i], i + 1);
			}
			return index;
		}

		/**
		 * @param index  the index to resolve against
		 * @param values the values whose ids are wanted
		 * @return the values' ids, in the order they were named
		 */
		@Nonnull
		private static int[] valueIdsOf(@Nonnull InvertedIndex index, @Nonnull String... values) {
			final int[] ids = new int[values.length];
			for (int i = 0; i < values.length; i++) {
				ids[i] = index.getValueId(values[i]);
				assertTrue(ids[i] > 0, "`" + values[i] + "` must be in the index or the candidate set is a fiction");
			}
			return ids;
		}

		/**
		 * Asserts two answers hold the same buckets, in the same order, with the same staleness token set.
		 *
		 * @param expected the answer to compare against
		 * @param actual   the answer under test
		 */
		private static void assertMatchesAgree(@Nonnull MatchedBuckets expected, @Nonnull MatchedBuckets actual) {
			assertEquals(expected.recordSets().length, actual.recordSets().length, "different bucket counts");
			for (int i = 0; i < expected.recordSets().length; i++) {
				assertArrayEquals(
					expected.recordSets()[i].getArray(), actual.recordSets()[i].getArray(),
					"bucket " + i + " differs"
				);
			}
			assertArrayEquals(
				expected.leafVersionIds(), actual.leafVersionIds(),
				"the two forms must depend on exactly the same leaves, or they would not share a cache entry"
			);
		}
	}

	/**
	 * Value ids are minted per distinct value and stamped into a parallel id column that moves with the keys through
	 * every split, merge and steal. A range-typed tree is the one shape where those keys are RECONSTRUCTED on every
	 * read, so an id that stayed with the wrong key would surface here and nowhere else.
	 */
	@Nested
	@DisplayName("Ids over a range-keyed tree")
	class RangeKeyedIds {

		/**
		 * Builds an ascending date-time range whose zone offset varies with the ordinal.
		 *
		 * @param ordinal the ordinal to derive the range from
		 * @return the range
		 */
		@Nonnull
		private static DateTimeRange range(int ordinal) {
			final ZoneOffset offset = ZoneOffset.ofTotalSeconds((ordinal % 5 - 2) * 1800);
			final LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0).plusHours(ordinal);
			return DateTimeRange.between(from.atOffset(offset), from.plusDays(1).atOffset(offset));
		}

		/**
		 * @return an empty, id-carrying inverted index over date-time ranges
		 */
		@Nonnull
		private static InvertedIndex emptyRangeIndexWithIds() {
			final InvertedIndex index = new InvertedIndex(
				DateTimeRange.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0);
			index.attachValueIdConsumer(TEST_CONSUMER);
			return index;
		}

		@Test
		@DisplayName("a single-leaf range tree resolves every id back to the value that owns it")
		void shouldResolveIdsOnASingleLeafRangeTree() {
			final InvertedIndex index = emptyRangeIndexWithIds();
			for (int i = 0; i < 10; i++) {
				index.addRecord(range(i), 100 + i);
			}
			assertFalse(index.isPaged(), "The fixture must be small enough to be persisted inline.");

			for (int i = 0; i < 10; i++) {
				final int valueId = index.getValueId(range(i));
				assertNotEquals(-1, valueId, "every live value must carry an id");
				assertEquals(range(i), index.getValueById(valueId), "id " + valueId + " resolved to the wrong value");
			}
		}

		@Test
		@DisplayName("ids survive the splits of a paged range tree, each still naming its own value")
		void shouldKeepIdsAlignedAcrossRangeTreeSplits() {
			final InvertedIndex index = emptyRangeIndexWithIds();
			final int valueCount = LEAF_BLOCK_SIZE * 3;
			for (int i = 0; i < valueCount; i++) {
				index.addRecord(range(i), i);
			}
			assertTrue(index.isPaged(), "The fixture must be large enough to be persisted as leaf pages.");

			// ids are minted in insertion order, and the value each one names must survive every split the tree
			// performed after it was stamped — including the three-array lockstep of the range column
			for (int i = 0; i < valueCount; i++) {
				final int valueId = index.getValueId(range(i));
				assertNotEquals(-1, valueId, "value " + i + " lost its id across the splits");
				assertEquals(range(i), index.getValueById(valueId), "id " + valueId + " resolved to the wrong value");
			}
		}
	}
}
