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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Predecessor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark attributing the cost of the always-on WARM_UP per-entity savepoint
 * {@link WarmUpSavepoint} to the individual index structures that have to journal underneath it.
 *
 * **The question it was written to answer, and the answer it gave.** Making every WARM_UP entity write atomic
 * (issue #1432) was accepted at a measured +2.17 % of bulk-ingest CPU — the number that carried the "always on, no
 * switch" decision. Every profile behind it was taken on a SINGLE corpus, and the campaign had converted only three
 * places to cheap per-slot journalling: {@link TransactionalBitmap}, {@link TransactionalBucketBPlusTree}'s
 * bucket-leaf mutators, and `UnorderedLookupTree`'s spine COUNT adjustments. This benchmark measured each structure
 * directly instead of hoping a corpus exercised it, and found the others paying up to **97×** the converted ones.
 * They were converted in turn — marginal allocation per additional instance touched inside one savepoint:
 *
 * | structure | before | after |
 * |---|---|---|
 * | {@link #chainIndex} (both backing trees) | 5 972 B | 890 B |
 * | {@link #rangeIndex} / `TransactionalLongBPlusTree` | 6 576 B | 512 B |
 * | {@link #priceRecords} / `TransactionalElementBPlusTree` | 320 B | 24 B |
 * | {@link #bitmap} | 68 B | 68 B |
 * | {@link #bucketTree} | 116 B | 113 B |
 *
 * **What it is for NOW: a regression guard.** All five structures journal per slot today, so **every arm is expected
 * near-flat** — tens of bytes per additional instance, not hundreds or thousands. An arm that climbs into the
 * hundreds has regressed to whole-node mementos. Note that this inverts the file's original reading rule: a flat
 * result across the board used to mean the benchmark was broken, and now means the code is healthy.
 *
 * **The two arms.** Exactly one thing differs between them: whether a savepoint is open. Both arms perform the
 * identical sequence of mutations against identically pre-populated structures, replaying a pre-generated operation
 * stream so the measured region does no random-number work. The `savepoint = true` arm brackets each simulated entity
 * write with {@link WarmUpSavepoint#open()} / {@link WarmUpSavepoint#commit()} exactly as
 * `LocalMutationExecutorCollector` does on the warm-up path — commit rather than rollback, so both arms leave the same
 * structure state behind and only the journalling is added. The delta between the arms IS the mechanism's cost.
 *
 * **How to read an arm.** The measurement to take is the MARGINAL cost per additional instance touched inside one
 * savepoint — `(alloc@instancesPerEntity=10 − alloc@instancesPerEntity=1) / 9` — never the raw figure. Opening and
 * committing a savepoint costs a fixed ~800 B/op whatever it brackets, which swamps a converted structure's true
 * per-instance cost and makes every arm look expensive if read directly.
 *
 * The volume each structure would copy if it fell back to a whole-node memento, which is what the "before" column
 * above is made of and what a regression would resurrect:
 *
 * - {@link #chainIndex} — {@link ChainIndex} over the paged, head-aware position tree behind a `Predecessor`
 *   attribute such as `orderInCategory`; its leaf is `int[1025]` plus a `long[17]` head mask, ≈ 4.2 KB per capture.
 *   This is the prime suspect and the reason the fan-out parameter exists.
 * - {@link #priceRecords} — {@link TransactionalElementBPlusTree} as the price index uses it; leaf clones `E[64]`,
 *   ≈ 256 B per capture.
 * - {@link #rangeIndex} — {@link RangeIndex} over `TransactionalLongBPlusTree`, which backs price `validity`; leaf
 *   clones `long[64]` plus `V[64]`, ≈ 768 B per capture.
 *
 * For calibration: the bucket leaf that WAS converted copies ~768 B and measured 551 ms per 100 000 entities before
 * its conversion, about 21 % of the bracketed path's allocation.
 *
 * **Why the fan-out parameter is not decoration.** A whole-node memento is captured once per node per savepoint, so
 * the cost of a structure is paid once per savepoint per INSTANCE touched — and instances multiply with the data
 * shape. Reduced indexes exist per referenced entity, so a product in ten categories drives ten separate
 * {@link ChainIndex} instances inside one entity write; price indexes are keyed per price list × currency × inner
 * record handling, and each carries two unconverted trees. {@code instancesPerEntity} models that multiplier
 * directly: comparing its `1` and `10` settings shows whether a structure's cost scales with catalogue shape or stays
 * a fixed tax.
 *
 * **Reading the numbers.** `gc.alloc.rate.norm` (bytes allocated per operation, from `-prof gc`) is the cleanest
 * signal, because a whole-node memento is literally an array clone — always run with the profiler attached. Time per
 * operation matters too, but allocation attributes the cost to the mechanism unambiguously where time can drift with
 * machine state.
 *
 * Run it with JMH's own runner, since the benchmarks jar declares a custom main class:
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * WarmUpSavepointStructureCostBenchmark -prof gc}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
public class WarmUpSavepointStructureCostBenchmark {

	/**
	 * How many entries every structure instance is pre-populated with before measurement starts. A structure smaller
	 * than one full node understates the memento cost catastrophically — the paged position tree behind
	 * {@link ChainIndex} pages at 1024 records, so this has to be comfortably above that for the big leaf shape to be
	 * the one actually exercised.
	 */
	private static final int PREPOPULATED_ENTRIES = 50_000;
	/**
	 * Length of the pre-generated operation stream. A power of two so the cursor can be wrapped with a mask rather
	 * than a division, keeping the measured region free of anything but the mutation under test.
	 */
	private static final int OPERATION_COUNT = 8192;
	/**
	 * Mask wrapping the operation cursor into {@link #OPERATION_COUNT}.
	 */
	private static final int OPERATION_MASK = OPERATION_COUNT - 1;
	/**
	 * Largest value {@code instancesPerEntity} may take; every state pre-builds this many structure instances so a
	 * parameter change costs no extra setup.
	 */
	private static final int MAX_INSTANCES = 10;
	/**
	 * Seed of the operation-stream generator. Fixed so two runs of this benchmark — and both arms within one run —
	 * replay an identical sequence.
	 */
	private static final long SEED = 42L;

	/**
	 * Runs one simulated entity write, bracketed by a savepoint or not according to the state's parameter.
	 *
	 * The bracket mirrors `LocalMutationExecutorCollector`: exactly one {@link WarmUpSavepoint#commit()} in a
	 * `finally`, so a mutation that throws cannot leak the savepoint into the next invocation. Commit rather than
	 * rollback, because the arms must leave identical structure state behind — measuring a rollback would compare a
	 * write against a write plus an undo instead of isolating the journalling.
	 *
	 * @param state the structure state to write into
	 */
	private static void runEntityWrite(@Nonnull AbstractStructureState state) {
		if (state.savepoint) {
			final WarmUpSavepoint openedSavepoint = WarmUpSavepoint.open();
			try {
				state.writeOneEntity();
			} finally {
				openedSavepoint.commit();
			}
		} else {
			state.writeOneEntity();
		}
	}

	/**
	 * Measures {@link ChainIndex} — the structure behind a `Predecessor` sort attribute, whose position tree pages at
	 * 1024 records and therefore carries the largest whole-node memento in the engine.
	 *
	 * @param state pre-built chain indexes and the pre-generated move stream
	 */
	@Benchmark
	public void chainIndex(@Nonnull ChainIndexState state) {
		runEntityWrite(state);
	}

	/**
	 * Measures {@link TransactionalElementBPlusTree} as the price index uses it for its price records.
	 *
	 * @param state pre-built price trees and the pre-generated upsert stream
	 */
	@Benchmark
	public void priceRecords(@Nonnull PriceRecordState state) {
		runEntityWrite(state);
	}

	/**
	 * Measures {@link RangeIndex}, which backs price `validity` and every range-typed attribute through
	 * `TransactionalLongBPlusTree`.
	 *
	 * @param state pre-built range indexes and the pre-generated range stream
	 */
	@Benchmark
	public void rangeIndex(@Nonnull RangeIndexState state) {
		runEntityWrite(state);
	}

	/**
	 * {@link TransactionalBucketBPlusTree}, whose bucket-leaf mutators were the first converted to per-slot
	 * journalling. It was the reference arm the others were judged against while they were still unconverted, and it
	 * is a plain regression guard now that they are not.
	 *
	 * @param state pre-built bucket trees and the pre-generated churn stream
	 */
	@Benchmark
	public void bucketTree(@Nonnull BucketTreeState state) {
		runEntityWrite(state);
	}

	/**
	 * {@link TransactionalBitmap}, converted to per-operation journalling by the same campaign and serving the same
	 * purpose as {@link #bucketTree} — the cheapest arm, and the one a harness fault would show up in first.
	 *
	 * @param state pre-built bitmaps and the pre-generated churn stream
	 */
	@Benchmark
	public void bitmap(@Nonnull BitmapState state) {
		runEntityWrite(state);
	}

	/**
	 * Shared behaviour of the five per-structure states: the two benchmark parameters, the operation cursor, and the
	 * pre-generated operation stream every subclass reads its operands from.
	 *
	 * Subclasses build their structures in a trial-level setup and implement {@link #write(int, int)} to perform the
	 * writes one entity makes against one structure instance.
	 *
	 * It carries {@link State} itself because JMH's annotation processor refuses a {@link Param} declared in a class
	 * that is not state-annotated, even when every concrete subclass is.
	 */
	@State(Scope.Benchmark)
	public abstract static class AbstractStructureState {
		/**
		 * Whether a {@link WarmUpSavepoint} is open around each simulated entity write. This is the only difference
		 * between the two arms.
		 */
		@Param({"true", "false"})
		public boolean savepoint;
		/**
		 * How many distinct structure instances one simulated entity write touches — the catalogue-shape multiplier
		 * described in the class javadoc. Each touched instance costs its own whole-node memento in an unconverted
		 * structure, and nothing at all in a converted one.
		 */
		@Param({"1", "10"})
		public int instancesPerEntity;
		/**
		 * First operand stream, pre-generated so the measured region performs no random-number work. Its meaning is
		 * structure-specific and documented on each subclass's {@link #write(int, int)}.
		 */
		protected int[] primaryOperands;
		/**
		 * Second operand stream, pre-generated alongside {@link #primaryOperands}.
		 */
		protected int[] secondaryOperands;
		/**
		 * Monotonically increasing invocation counter, masked into the operation streams. Not reset between
		 * iterations: the streams are cyclic and every position is equivalent, so wrapping mid-iteration changes
		 * nothing about what is measured.
		 */
		private int cursor;

		/**
		 * Generates the shared operand streams. Subclasses that need different operand semantics override
		 * {@link #generateOperands(Random)} rather than this method.
		 */
		@Setup(Level.Trial)
		public void setUpStreams() {
			final Random random = new Random(SEED);
			this.primaryOperands = new int[OPERATION_COUNT];
			this.secondaryOperands = new int[OPERATION_COUNT];
			generateOperands(random);
			setUpStructures();
		}

		/**
		 * Fills {@link #primaryOperands} and {@link #secondaryOperands} with the default semantics: two distinct
		 * record identifiers drawn from the pre-populated key range.
		 *
		 * @param random the seeded generator to draw from
		 */
		protected void generateOperands(@Nonnull Random random) {
			for (int i = 0; i < OPERATION_COUNT; i++) {
				final int first = random.nextInt(PREPOPULATED_ENTRIES) + 1;
				int second = random.nextInt(PREPOPULATED_ENTRIES) + 1;
				if (second == first) {
					second = first == PREPOPULATED_ENTRIES ? first - 1 : first + 1;
				}
				this.primaryOperands[i] = first;
				this.secondaryOperands[i] = second;
			}
		}

		/**
		 * Performs the writes one simulated entity makes: the same operation against {@code instancesPerEntity}
		 * distinct structure instances, which is how a single entity upsert reaches several reduced indexes or
		 * several price indexes at once.
		 */
		final void writeOneEntity() {
			final int operation = this.cursor++ & OPERATION_MASK;
			for (int instance = 0; instance < this.instancesPerEntity; instance++) {
				write(instance, operation);
			}
		}

		/**
		 * Builds this state's structure instances, pre-populated to {@link #PREPOPULATED_ENTRIES}.
		 */
		protected abstract void setUpStructures();

		/**
		 * Performs the write one entity makes against one structure instance.
		 *
		 * @param instance  index of the structure instance to write into, below {@link #instancesPerEntity}
		 * @param operation index into the pre-generated operand streams
		 */
		protected abstract void write(int instance, int operation);
	}

	/**
	 * State for {@link WarmUpSavepointStructureCostBenchmark#chainIndex}: {@code MAX_INSTANCES} chain indexes, each
	 * holding a single chain of {@link #PREPOPULATED_ENTRIES} elements so the position tree is many full 1024-record
	 * pages deep.
	 */
	@State(Scope.Benchmark)
	public static class ChainIndexState extends AbstractStructureState {
		/**
		 * The chain indexes under measurement; one entity write touches the first {@code instancesPerEntity} of them.
		 */
		private ChainIndex[] indexes;

		@Override
		protected void setUpStructures() {
			this.indexes = new ChainIndex[MAX_INSTANCES];
			for (int i = 0; i < MAX_INSTANCES; i++) {
				final ChainIndex index = new ChainIndex(
					new AttributeIndexKey("category", "orderInCategory", null)
				);
				index.upsertPredecessor(Predecessor.HEAD, 1);
				for (int pk = 2; pk <= PREPOPULATED_ENTRIES; pk++) {
					index.upsertPredecessor(new Predecessor(pk - 1), pk);
				}
				if (!index.isConsistent()) {
					throw new GenericEvitaInternalError(
						"Pre-populated chain index is not a single consistent chain - the benchmark would measure " +
							"the fragmented-chain path instead of the append path it is meant to measure!"
					);
				}
				this.indexes[i] = index;
			}
		}

		/**
		 * Appends a new element after the chain's tail and removes it again — the shape a bulk ingest actually has,
		 * where every entity upsert adds one element to the end of each chain it participates in. The removal is only
		 * a bounding device so an unbounded number of invocations leaves the structure the size it started at; it
		 * lands on the same leaf the append just touched, which the savepoint's first-touch dedup then recognises, so
		 * the pair costs one capture exactly as a lone append would.
		 *
		 * **Random moves were tried first and rejected.** Moving an existing element after another randomly chosen
		 * element fragments the chain into unreachable segments that {@link ChainIndex} parks in its element-state
		 * map instead of relocating runs in the position tree — it measured a quarter of the cost of a real append
		 * and would have understated the memento badly. Whatever a benchmark of chain churn should look like, it is
		 * not that.
		 *
		 * @param instance  index of the chain index to write into
		 * @param operation index into the operand streams; used only to vary the appended primary key
		 */
		@Override
		protected void write(int instance, int operation) {
			final ChainIndex index = this.indexes[instance];
			final int appended = PREPOPULATED_ENTRIES + 1 + operation;
			index.upsertPredecessor(new Predecessor(PREPOPULATED_ENTRIES), appended);
			index.removePredecessor(appended);
		}
	}

	/**
	 * State for {@link WarmUpSavepointStructureCostBenchmark#priceRecords}: {@code MAX_INSTANCES} element trees
	 * standing in for the per-price-list × currency price indexes, each holding {@link #PREPOPULATED_ENTRIES} price
	 * records.
	 */
	@State(Scope.Benchmark)
	public static class PriceRecordState extends AbstractStructureState {
		/**
		 * The price-record trees under measurement.
		 */
		private TransactionalElementBPlusTree<PriceLikeElement>[] trees;

		@SuppressWarnings("unchecked")
		@Override
		protected void setUpStructures() {
			this.trees = new TransactionalElementBPlusTree[MAX_INSTANCES];
			for (int i = 0; i < MAX_INSTANCES; i++) {
				final TransactionalElementBPlusTree<PriceLikeElement> tree =
					new TransactionalElementBPlusTree<>(PriceLikeElement.class, PriceLikeElement::priceId);
				for (int pk = 1; pk <= PREPOPULATED_ENTRIES; pk++) {
					tree.insert(new PriceLikeElement(pk, pk * 100L));
				}
				this.trees[i] = tree;
			}
		}

		/**
		 * Replaces an existing price record with a new one carrying the same key — the tree's upsert semantics, and
		 * what a price update does. The key already exists, so the leaf is written in place and never splits, which
		 * keeps the structure bounded across an unbounded number of invocations.
		 *
		 * @param instance  index of the tree to write into
		 * @param operation index into the operand streams; primary is the price identifier, secondary seeds the value
		 */
		@Override
		protected void write(int instance, int operation) {
			this.trees[instance].insert(
				new PriceLikeElement(this.primaryOperands[operation], this.secondaryOperands[operation] * 100L)
			);
		}
	}

	/**
	 * State for {@link WarmUpSavepointStructureCostBenchmark#rangeIndex}: {@code MAX_INSTANCES} range indexes, each
	 * pre-populated with {@link #PREPOPULATED_ENTRIES} ranges.
	 */
	@State(Scope.Benchmark)
	public static class RangeIndexState extends AbstractStructureState {
		/**
		 * The range indexes under measurement.
		 */
		private RangeIndex[] indexes;

		@Override
		protected void setUpStructures() {
			this.indexes = new RangeIndex[MAX_INSTANCES];
			for (int i = 0; i < MAX_INSTANCES; i++) {
				final RangeIndex index = new RangeIndex();
				for (int pk = 1; pk <= PREPOPULATED_ENTRIES; pk++) {
					index.addRecord(pk * 10L, pk * 10L + 5L, pk);
				}
				this.indexes[i] = index;
			}
		}

		/**
		 * Adds a range and removes it again — a validity update in miniature. The pair leaves the index exactly as it
		 * found it, which is what keeps the structure bounded, while still performing two genuine leaf writes; both
		 * arms perform the identical pair, so the bracket is the only difference between them.
		 *
		 * @param instance  index of the range index to write into
		 * @param operation index into the operand streams; primary is the record, secondary offsets the range
		 */
		@Override
		protected void write(int instance, int operation) {
			final RangeIndex index = this.indexes[instance];
			final int record = this.primaryOperands[operation];
			final long from = record * 10L + 1L;
			final long to = from + this.secondaryOperands[operation] % 7;
			index.addRecord(from, to, record + PREPOPULATED_ENTRIES);
			index.removeRecord(from, to, record + PREPOPULATED_ENTRIES);
		}
	}

	/**
	 * CONTROL state for {@link WarmUpSavepointStructureCostBenchmark#bucketTree} on the already-converted
	 * {@link TransactionalBucketBPlusTree}.
	 */
	@State(Scope.Benchmark)
	public static class BucketTreeState extends AbstractStructureState {
		/**
		 * The bucket trees under measurement.
		 */
		private TransactionalBucketBPlusTree<Integer>[] trees;

		@SuppressWarnings("unchecked")
		@Override
		protected void setUpStructures() {
			this.trees = new TransactionalBucketBPlusTree[MAX_INSTANCES];
			for (int i = 0; i < MAX_INSTANCES; i++) {
				final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(Integer.class);
				for (int pk = 1; pk <= PREPOPULATED_ENTRIES; pk++) {
					tree.addRecord(pk, pk);
				}
				this.trees[i] = tree;
			}
		}

		/**
		 * Adds a record to an existing bucket and removes it again, mirroring the add/remove pair
		 * {@link RangeIndexState} performs so the control and the suspect differ in structure and not in workload
		 * shape.
		 *
		 * @param instance  index of the tree to write into
		 * @param operation index into the operand streams; primary selects the bucket, secondary the record
		 */
		@Override
		protected void write(int instance, int operation) {
			final TransactionalBucketBPlusTree<Integer> tree = this.trees[instance];
			final Integer bucket = this.primaryOperands[operation];
			final int record = this.secondaryOperands[operation] + PREPOPULATED_ENTRIES;
			tree.addRecord(bucket, record);
			tree.removeRecord(bucket, record);
		}
	}

	/**
	 * CONTROL state for {@link WarmUpSavepointStructureCostBenchmark#bitmap} on the already-converted
	 * {@link TransactionalBitmap}.
	 */
	@State(Scope.Benchmark)
	public static class BitmapState extends AbstractStructureState {
		/**
		 * The bitmaps under measurement.
		 */
		private TransactionalBitmap[] bitmaps;

		@Override
		protected void setUpStructures() {
			this.bitmaps = new TransactionalBitmap[MAX_INSTANCES];
			for (int i = 0; i < MAX_INSTANCES; i++) {
				final TransactionalBitmap bitmap = new TransactionalBitmap();
				for (int pk = 1; pk <= PREPOPULATED_ENTRIES; pk++) {
					bitmap.add(pk);
				}
				this.bitmaps[i] = bitmap;
			}
		}

		/**
		 * Adds a record identifier outside the pre-populated range and removes it again, keeping the bitmap bounded
		 * while performing two genuine writes.
		 *
		 * @param instance  index of the bitmap to write into
		 * @param operation index into the operand streams; primary selects the record identifier
		 */
		@Override
		protected void write(int instance, int operation) {
			final TransactionalBitmap bitmap = this.bitmaps[instance];
			final int record = this.primaryOperands[operation] + PREPOPULATED_ENTRIES;
			bitmap.add(record);
			bitmap.remove(record);
		}
	}

	/**
	 * Stand-in for a price record: an integer-keyed element of the size the real one has, which is all the element
	 * tree's leaf sees. The leaf's memento clones the array of references, so the element's own size never enters the
	 * measurement — only the reference array does.
	 *
	 * @param priceId       the tree's ordering and identity key
	 * @param priceWithTax  payload standing in for the price itself
	 */
	public record PriceLikeElement(int priceId, long priceWithTax) implements Serializable {
	}

}
