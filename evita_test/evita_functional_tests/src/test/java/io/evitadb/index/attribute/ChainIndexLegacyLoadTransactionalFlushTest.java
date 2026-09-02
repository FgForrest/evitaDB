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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.array.UnorderedLookupTree;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.index.attribute.ChainIndex.ElementState;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for issue #1437: the first transactional flush of a {@link ChainIndex} restored from a
 * **legacy, non-paged** `ChainIndexStoragePart` used to abort the whole commit with
 * `Transaction is already committed / rolled back, no new transactional memory layer may be created at this time!`,
 * which marks the catalog CORRUPTED.
 *
 * The mechanism this class pins down:
 *
 * - `AttributeIndexLoader.fetchChain` rebuilds a legacy part through the `int[][] chains` constructor, so **every**
 *   leaf of the element tree carries `UNASSIGNED_PAGE_SEQUENCE` — unlike a natively-paged index, whose leaves are
 *   restored with their persisted page sequences;
 * - the flush that assigns those sequences (`PageStreamRegistry.collectChangedPages`) runs INSIDE the commit:
 *   `TransactionTrunkFinalizer.commitCatalogChanges` flushes the catalog after
 *   {@link TransactionalLayerMaintainer} has already forbidden new transactional layer creation;
 * - the flush walks EVERY leaf, so it stamps leaves the transaction never touched, and those carry no diff layer.
 *
 * The fixture therefore builds a legacy-shaped index spanning three leaves, mutates only the LAST one, and performs
 * the flush from inside {@link TransactionHandler#commit(TransactionalLayerMaintainer)} — the one moment where the
 * transaction is still bound to the thread but layer creation is already sealed, exactly as on the trunk path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Legacy (non-paged) chain index flushed inside a sealed commit")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class ChainIndexLegacyLoadTransactionalFlushTest {

	/**
	 * Primary key of the owning entity index — mirrors the global index of the collection in the reported incident.
	 */
	private static final int ENTITY_INDEX_PK = 1;
	/**
	 * The chained attribute the reported incident failed on.
	 */
	private static final AttributeIndexKey ORDER_KEY = new AttributeIndexKey(null, "order", null);
	/**
	 * Number of chained records in the fixture. Leaves of a paged tree hold {@link UnorderedLookupTree#PAGE_RECORDS}
	 * records, so this spans three leaves (1024 + 1024 + 52) — matching the three-leaf chain index observed in the
	 * reported incident, and leaving the last leaf room to accept the mutation without splitting.
	 */
	private static final int RECORD_COUNT = (2 * UnorderedLookupTree.PAGE_RECORDS) + 52;
	/**
	 * Primary key appended by the in-transaction mutation. It lands in the LAST leaf, so the first two leaves stay
	 * untouched and therefore carry no transactional diff layer when the flush stamps them.
	 */
	private static final int APPENDED_PK = RECORD_COUNT + 1;

	/**
	 * Builds a {@link ChainIndex} exactly the way `AttributeIndexLoader.fetchChain` rebuilds a **legacy** (non-paged)
	 * `ChainIndexStoragePart`: one chain run `1..RECORD_COUNT` plus the per-element state map, fed through the
	 * `int[][] chains` constructor. Every leaf of the resulting element tree is unpaged.
	 *
	 * @return the legacy-shaped, freshly loaded (clean) chain index
	 */
	@Nonnull
	private static ChainIndex legacyLoadedChainIndex() {
		final int[] chain = new int[RECORD_COUNT];
		final Map<Integer, ChainElementState> elementStates = CollectionUtils.createHashMap(RECORD_COUNT);
		for (int i = 0; i < RECORD_COUNT; i++) {
			final int primaryKey = i + 1;
			chain[i] = primaryKey;
			elementStates.put(
				primaryKey,
				new ChainElementState(
					// every element belongs to the single chain headed by primary key 1
					1,
					// the head's predecessor is the HEAD sentinel, every successor points at the previous element
					i == 0 ? ChainableType.HEAD_PK : primaryKey - 1,
					i == 0 ? ElementState.HEAD : ElementState.SUCCESSOR
				)
			);
		}
		return new ChainIndex(null, ORDER_KEY, new int[][]{chain}, elementStates);
	}

	/**
	 * Drives one transaction over `chainIndex` the way the trunk-incorporation path does: the mutation runs inside the
	 * transaction, and the flush (`appendStorageParts`) runs from inside the commit callback — after the maintainer
	 * sealed layer creation, while the transaction is still bound to the thread. Mirrors
	 * `TransactionManager.commitChangesToSharedCatalog` → `TransactionTrunkFinalizer.commitCatalogChanges` →
	 * `Catalog.flush`.
	 *
	 * @param chainIndex the index to mutate and flush
	 * @param mutation   the mutation to apply inside the transaction
	 * @param sink       collects the storage parts the flush emits
	 * @return the merged (committed) copy of the index
	 */
	@Nonnull
	private static ChainIndex commitWithTrunkFlush(
		@Nonnull ChainIndex chainIndex,
		@Nonnull Runnable mutation,
		@Nonnull TrappedChanges sink
	) {
		final TrunkFlushTransactionHandler handler = new TrunkFlushTransactionHandler(chainIndex, sink);
		final Transaction transaction = new Transaction(UUID.randomUUID(), handler, false);
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				try {
					mutation.run();
				} finally {
					transaction.close();
				}
			}
		);
		final ChainIndex committed = handler.getCommitted();
		assertNotNull(committed, "The commit callback must have produced a merged chain index!");
		return committed;
	}

	/**
	 * Splits the parts collected by a flush into the emitted leaf pages and the (optional) root.
	 *
	 * @param sink the collected changes
	 * @return the flush outcome
	 */
	@Nonnull
	private static FlushOutcome readFlush(@Nonnull TrappedChanges sink) {
		final List<ChainIndexLeafPagePart> leafPages = new ArrayList<>(8);
		ChainIndexStoragePart root = null;
		final Iterator<StoragePart> iterator = sink.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			final StoragePart part = iterator.next();
			if (part instanceof final ChainIndexLeafPagePart leafPage) {
				leafPages.add(leafPage);
			} else if (part instanceof final ChainIndexStoragePart chainRoot) {
				root = chainRoot;
			}
		}
		return new FlushOutcome(leafPages, root);
	}

	@Nested
	@DisplayName("First flush after a legacy load")
	class FirstFlushTest {

		@Test
		@DisplayName("stamps page sequences on leaves the transaction never touched without tripping the sealed layer")
		void shouldStampUntouchedLeavesDuringSealedCommitFlush() {
			final ChainIndex chainIndex = legacyLoadedChainIndex();
			final TrappedChanges sink = new TrappedChanges();

			// before the fix this threw GenericEvitaInternalError: "Transaction is already committed / rolled back,
			// no new transactional memory layer may be created at this time!" while stamping the first untouched leaf
			final ChainIndex committed = commitWithTrunkFlush(
				chainIndex,
				() -> chainIndex.upsertPredecessor(new Predecessor(RECORD_COUNT), APPENDED_PK),
				sink
			);

			final FlushOutcome outcome = readFlush(sink);
			assertNotNull(outcome.root(), "A first PAGED flush must re-emit the root carrying the new page list!");
			assertTrue(outcome.root().isPaged(), "A three-leaf chain index must persist in the PAGED shape!");
			assertArrayEquals(
				new int[]{0, 1, 2},
				outcome.root().getPageSequencesOrThrowException(),
				"Every leaf must have been allocated a page sequence, in ascending key order!"
			);
			assertEquals(
				3, outcome.leafPages().size(),
				"A legacy index has no persisted pages yet, so all three leaves are fresh and must be written!"
			);
			assertEquals(
				RECORD_COUNT + 1,
				committed.getUnorderedLookup().size(),
				"The appended record must be part of the merged index!"
			);
		}

		@Test
		@DisplayName("carries the stamps through the commit merge so the next flush reuses the same pages")
		void shouldCarryPageStampsThroughCommitMerge() {
			final ChainIndex chainIndex = legacyLoadedChainIndex();
			final ChainIndex committed = commitWithTrunkFlush(
				chainIndex,
				() -> chainIndex.upsertPredecessor(new Predecessor(RECORD_COUNT), APPENDED_PK),
				new TrappedChanges()
			);

			final TrappedChanges secondSink = new TrappedChanges();
			commitWithTrunkFlush(
				committed,
				() -> committed.upsertPredecessor(new Predecessor(APPENDED_PK), APPENDED_PK + 1),
				secondSink
			);

			final FlushOutcome outcome = readFlush(secondSink);
			assertEquals(
				1, outcome.leafPages().size(),
				"Only the mutated leaf may be re-written — the stamps assigned by the first flush must have survived " +
					"the commit merge, otherwise every leaf would look fresh again!"
			);
			assertEquals(
				2, outcome.leafPages().get(0).getPageSequence(),
				"The mutation lands in the last leaf, so its already-allocated page must be reused!"
			);
			// a content-only commit leaves the persisted leaf-page list byte-identical, so the root is skipped; a
			// re-emitted root here would mean the page stamps had been re-allocated from scratch
			assertNull(outcome.root(), "The live page list did not change, so the PAGED root must not be re-emitted!");
		}

	}

	/**
	 * Carrier for the parts one flush emitted.
	 *
	 * @param leafPages the emitted leaf pages, in emission order
	 * @param root      the emitted PAGED root, or `null` when the flush skipped it
	 */
	private record FlushOutcome(
		@Nonnull List<ChainIndexLeafPagePart> leafPages,
		@Nullable ChainIndexStoragePart root
	) {
	}

	/**
	 * Transaction handler modelling `TransactionTrunkFinalizer`: the catalog flush runs inside the commit callback,
	 * i.e. after the maintainer has forbidden new transactional layer creation, and before the state copy with merged
	 * changes is produced.
	 */
	private static class TrunkFlushTransactionHandler implements TransactionHandler {
		private final ChainIndex chainIndex;
		private final TrappedChanges sink;
		@Nullable private ChainIndex committed;

		TrunkFlushTransactionHandler(@Nonnull ChainIndex chainIndex, @Nonnull TrappedChanges sink) {
			this.chainIndex = chainIndex;
			this.sink = sink;
		}

		@Nullable
		ChainIndex getCommitted() {
			return this.committed;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
			// no mutation bookkeeping is needed for this fixture
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			// the flush precedes the merge on the trunk path — see TransactionTrunkFinalizer.commitCatalogChanges
			this.chainIndex.appendStorageParts(ENTITY_INDEX_PK, this.sink);
			this.committed = transactionalLayer.getStateCopyWithCommittedChanges(this.chainIndex);
			transactionalLayer.verifyLayerWasFullySwept();
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			throw new IllegalStateException("Rollback is not expected in this fixture!", cause);
		}
	}

}
