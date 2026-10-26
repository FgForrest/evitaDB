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

package io.evitadb.spike;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import org.openjdk.jmh.annotations.*;

import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;

/**
 * Compares ordinary transactional middle inserts into bulk-built and insert-built int-keyed trees with identical
 * committed contents. Every measured invocation represents one entity mutation under an ALIVE per-entity savepoint.
 * The WAL replay bypasses that savepoint and cannot measure its structural mementos.
 *
 * Trial setup builds the committed tree from spaced ascending keys. Invocation setup binds a fresh transaction and
 * replaces the existing record immediately before each insertion probe with the same value. This creates the target
 * leaves' diff layers before the savepoint opens, so splitting a full leaf captures an existing layer's memento.
 * The measured region opens the savepoint, inserts the absent probes and accepts the entity mutation. Invocation
 * teardown aborts the surrounding transaction, retaining the original leaf occupancy for every invocation.
 *
 * Time per operation covers one batch of `insertCount` inserts and its savepoint; divide by `insertCount` for time
 * per inserted record. Tree construction, transaction setup, pre-touching and transaction abort are outside the timed
 * region. JMH's GC profiler includes invocation fixtures, so its allocation totals also include those fixture costs.
 * Both arms use the same probe sequence, transaction setup and savepoint lifecycle.
 *
 * Run via JMH's runner: `java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * IntKeyedBPlusTreeMiddleInsertBenchmark -prof gc`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class IntKeyedBPlusTreeMiddleInsertBenchmark {

	/**
	 * Inserts one entity's price records, retaining the cost of structural mementos under its savepoint.
	 *
	 * @param state the price tree and precomputed absent records
	 * @return the transaction-local size after the inserts
	 */
	@Benchmark
	public int priceRecords(@Nonnull ElementState state) {
		final TransactionalLayerMaintainer maintainer = state.transaction.getTransactionalMemory()
			.getTransactionalLayerMaintainer();
		final Savepoint savepoint = maintainer.openSavepoint();
		try {
			for (final PriceRecordContract record : state.insertions) {
				state.tree.insert(record);
			}
			return state.tree.size();
		} finally {
			maintainer.commitSavepoint(savepoint);
		}
	}

	/**
	 * Inserts one entity's record-id assignments under the same savepoint lifecycle as the price-record arm.
	 *
	 * @param state the primitive tree and precomputed absent keys
	 * @return the transaction-local size after the inserts
	 */
	@Benchmark
	public int recordIds(@Nonnull IntToLongState state) {
		final TransactionalLayerMaintainer maintainer = state.transaction.getTransactionalMemory()
			.getTransactionalLayerMaintainer();
		final Savepoint savepoint = maintainer.openSavepoint();
		try {
			for (final int key : state.insertionKeys) {
				state.tree.insert(key, key * 10L);
			}
			return state.tree.size();
		} finally {
			maintainer.commitSavepoint(savepoint);
		}
	}

	/**
	 * Shared parameters and deterministic probes, confined to the worker that binds the transaction.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@State(Scope.Thread)
	public abstract static class MiddleInsertState {
		/** Number of existing records, including a size that exercises a multi-level bulk-built spine. */
		@Param({"2016", "10000", "100000"})
		public int treeSize;
		/** Number of middle inserts made by one entity mutation. */
		@Param({"1", "16", "128"})
		public int insertCount;
		/** Construction strategy; all subsequent writes use the ordinary transactional insert API. */
		@Param({"true", "false"})
		public boolean bulkBuilt;
		/** Absent probes spread through the middle half of the committed key range. */
		protected int[] insertionKeys;
		/** Fresh invocation-local transaction, bound only to the state-owning worker. */
		protected Transaction transaction;

		/**
		 * Precomputes unique absent probes with a predecessor in the same leaf before any split.
		 */
		@Setup(Level.Trial)
		public void prepareProbes() {
			Assert.isPremiseValid(this.insertCount <= this.treeSize / 2, "Middle probes must be distinct.");
			this.insertionKeys = new int[this.insertCount];
			for (int i = 0; i < this.insertCount; i++) {
				final int ordinal = this.treeSize / 4 + (int) ((long) i * (this.treeSize / 2) / this.insertCount);
				this.insertionKeys[i] = (ordinal + 1) * 4 + 1;
			}
		}

		/**
		 * Aborts the invocation's transaction so the next entity starts with the same committed occupancy.
		 */
		@TearDown(Level.Invocation)
		public void abortTransaction() {
			this.transaction.setRollbackOnly();
			this.transaction.close();
		}
	}

	/**
	 * Price-record tree state with identical base records and insertion bodies in both construction arms.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@State(Scope.Thread)
	public static class ElementState extends MiddleInsertState {
		/** Committed price tree, reused after each transaction abort. */
		private TransactionalElementBPlusTree<PriceRecordContract> tree;
		/** Preallocated insertion records, keeping record construction outside measurement. */
		private PriceRecordContract[] insertions;

		/**
		 * Builds identical spaced prices with the selected construction strategy.
		 */
		@Setup(Level.Trial)
		public void buildTree() {
			final PriceRecordContract[] records = new PriceRecordContract[this.treeSize];
			for (int i = 0; i < records.length; i++) {
				records[i] = record((i + 1) * 4);
			}
			if (this.bulkBuilt) {
				this.tree = TransactionalElementBPlusTree.bulkBuild(
					PriceRecordContract.class, PriceRecordContract::internalPriceId, records
				);
			} else {
				this.tree = new TransactionalElementBPlusTree<>(
					PriceRecordContract.class, PriceRecordContract::internalPriceId
				);
				for (final PriceRecordContract record : records) {
					this.tree.insert(record);
				}
			}
			this.insertions = new PriceRecordContract[this.insertCount];
			for (int i = 0; i < this.insertCount; i++) {
				this.insertions[i] = record(this.insertionKeys[i]);
			}
		}

		/**
		 * Opens an ordinary transaction and pre-touches the leaves before the measured per-entity savepoint.
		 */
		@Setup(Level.Invocation)
		public void beginTransaction() {
			Assert.isPremiseValid(this.tree.size() == this.treeSize, "An invocation must retain the committed base.");
			this.transaction = new Transaction(this.tree);
			this.transaction.bindTransactionToThread();
			for (final int key : this.insertionKeys) {
				this.tree.insert(this.tree.search(key - 1));
			}
		}
	}

	/**
	 * Primitive record-id tree state, following the same key distribution and transaction protocol as prices.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@State(Scope.Thread)
	public static class IntToLongState extends MiddleInsertState {
		/** Committed inverse tree, reused after each transaction abort. */
		private TransactionalIntToLongBPlusTree tree;

		/**
		 * Builds the same key-value pairs either through the bulk builder or through ascending inserts.
		 */
		@Setup(Level.Trial)
		public void buildTree() {
			this.tree = new TransactionalIntToLongBPlusTree();
			final int[] keys = new int[this.treeSize];
			final long[] values = new long[this.treeSize];
			for (int i = 0; i < this.treeSize; i++) {
				keys[i] = (i + 1) * 4;
				values[i] = keys[i] * 10L;
				if (!this.bulkBuilt) {
					this.tree.insert(keys[i], values[i]);
				}
			}
			if (this.bulkBuilt) {
				this.tree.bulkBuild(keys, values);
			}
		}

		/**
		 * Creates pre-existing leaf layers so a full-leaf split must journal their structural state.
		 */
		@Setup(Level.Invocation)
		public void beginTransaction() {
			Assert.isPremiseValid(this.tree.size() == this.treeSize, "An invocation must retain the committed base.");
			this.transaction = new Transaction(this.tree);
			this.transaction.bindTransactionToThread();
			for (final int key : this.insertionKeys) {
				this.tree.insert(key - 1, (key - 1) * 10L);
			}
		}
	}

	/**
	 * Creates a deterministic record body whose internal price id determines its ordering.
	 *
	 * @param key the internal price id
	 * @return a price record with a distinct entity and external price id
	 */
	@Nonnull
	private static PriceRecordContract record(int key) {
		return new PriceRecord(key, key, key, key * 100 + 21, key * 100);
	}
}
