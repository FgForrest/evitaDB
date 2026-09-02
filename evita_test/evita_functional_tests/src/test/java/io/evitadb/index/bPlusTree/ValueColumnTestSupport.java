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

package io.evitadb.index.bPlusTree;

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared assertions for the {@link ValueColumn} test suite. The three concrete-column tests
 * ({@code LongValueColumnTest}, {@code IntValueColumnTest}, {@code InstantValueColumnTest}) each
 * drive a {@link TransactionalBucketBPlusTree} whose leaves use the column under test and verify it
 * against a {@link TreeMap} oracle; the cursor-vs-oracle walk and the structural-consistency check
 * are identical across all of them and live here so the column tests do not each repeat them.
 *
 * {@link #describe(String)} joins them because the two surrogate-carrying suites
 * ({@code FrontCodedStringColumnTest} and {@code Wtf8Test}) both need a failure message that tells
 * unprintable code units apart, and had grown an identical private copy each.
 *
 * {@link #assertValueColumnSizing} and {@link #assertRecordColumnSizing} are the shared grow / trim /
 * `size()` battery. Both column families obey one sizing contract — a logical `capacity()` that never
 * moves over a physical backing that follows the live content — so stating it once and driving every
 * implementation through it is what keeps the seven of them from drifting apart. The record battery
 * lives here rather than in a near-duplicate support class for the same reason.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ValueColumnTestSupport {

	private ValueColumnTestSupport() {
		throw new UnsupportedOperationException("Test support class, not instantiable");
	}

	/**
	 * Asserts the tree's forward cursor enumerates exactly the oracle's `(value → record set)` pairs
	 * in ascending value order.
	 *
	 * @param tree   the bucket tree under test
	 * @param oracle the reference `TreeMap`
	 * @param <K>    the (boxed) bucket-value key type
	 */
	static <K extends Comparable<K>> void assertTreeMatchesOracle(
		@Nonnull TransactionalBucketBPlusTree<K> tree,
		@Nonnull TreeMap<K, TreeSet<Integer>> oracle
	) {
		final BucketCursor<K> cursor = tree.cursor();
		for (final Map.Entry<K, TreeSet<Integer>> entry : oracle.entrySet()) {
			assertTrue(cursor.next(), "Tree ran out of buckets before the oracle did");
			assertEquals(entry.getKey(), cursor.value(), "Bucket value mismatch");
			final int[] expected = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
			final int[] actual = cursor.isSingle()
				? new int[]{cursor.singleRecordId()}
				: cursor.records().getArray();
			assertArrayEquals(expected, actual, "Record set mismatch for value " + entry.getKey());
		}
		assertFalse(cursor.next(), "Tree has more buckets than the oracle");
	}

	/**
	 * Asserts the tree's structural consistency oracle reports a healthy tree.
	 *
	 * @param tree the tree to verify
	 * @param <K>  the (boxed) bucket-value key type
	 */
	static <K extends Comparable<K>> void verifyConsistent(
		@Nonnull TransactionalBucketBPlusTree<K> tree
	) {
		assertEquals(
			ConsistencyState.CONSISTENT,
			tree.getConsistencyReport().state(),
			tree.getConsistencyReport().report()
		);
	}

	/**
	 * The leaf block size every sizing assertion below runs at. Large enough that the physical backing is far
	 * shorter than the logical capacity for most of the battery, small enough to fill in a loop.
	 */
	static final int SIZING_CAPACITY = 64;

	/**
	 * Drives one {@link ValueColumn} implementation through the whole grow / trim / `size()` contract: the logical
	 * capacity never moves, `size()` follows every mutator, `fillEmpty` and `clearAt` truncate rather than poke,
	 * `copyRangeTo` grows its destination (including the leaf's in-place right shift), `duplicate` preserves the
	 * physical shape and `trimmed` reclaims slack only once there is enough of it.
	 *
	 * @param emptyColumnFactory builds a fresh empty column at the given logical capacity
	 * @param keyFactory         builds the key for an ordinal; ordinals must map to ascending distinct keys
	 * @param fixedSlotStorage   whether the column stores one slot per key and therefore has slack to trim;
	 *                           `false` only for {@code FrontCodedStringColumn}, whose blob is already exact
	 * @param <M>                the (boxed) key type
	 */
	static <M extends Comparable<M>> void assertValueColumnSizing(
		@Nonnull IntFunction<ValueColumn<M>> emptyColumnFactory,
		@Nonnull IntFunction<M> keyFactory,
		boolean fixedSlotStorage
	) {
		// an empty column: the logical capacity is already fixed and there is nothing to trim
		final ValueColumn<M> column = emptyColumnFactory.apply(SIZING_CAPACITY);
		assertEquals(SIZING_CAPACITY, column.capacity(), "the logical capacity is set at construction");
		assertEquals(0, column.size(), "a fresh column holds nothing");
		assertSame(column, column.trimmed(), "an empty column has no slack to reclaim");
		final long emptyHeap = column.getHeapSizeInBytes();

		// inserts raise the live count and never the logical capacity
		for (int i = 0; i < 5; i++) {
			column.insertKeyAt(i, keyFactory.apply(i));
		}
		assertEquals(5, column.size());
		assertEquals(SIZING_CAPACITY, column.capacity(), "an insert must never move the logical capacity");
		for (int i = 0; i < 5; i++) {
			assertEquals(keyFactory.apply(i), column.keyAt(i), "key mismatch at slot " + i);
		}

		// the footprint follows the content rather than the block size
		final long fiveKeyHeap = column.getHeapSizeInBytes();
		assertTrue(fiveKeyHeap > emptyHeap, "a populated column must cost more than an empty one");
		final ValueColumn<M> full = emptyColumnFactory.apply(SIZING_CAPACITY);
		for (int i = 0; i < SIZING_CAPACITY; i++) {
			full.insertKeyAt(i, keyFactory.apply(i));
		}
		assertEquals(SIZING_CAPACITY, full.size());
		assertEquals(SIZING_CAPACITY, full.capacity());
		assertTrue(full.getHeapSizeInBytes() > fiveKeyHeap, "a full block must cost more than a five-key one");

		// duplicate keeps the physical shape verbatim - it is the MVCC decouple and the savepoint memento primitive
		final ValueColumn<M> copy = column.duplicate();
		assertEquals(column.size(), copy.size());
		assertEquals(column.capacity(), copy.capacity());
		assertEquals(column.getHeapSizeInBytes(), copy.getHeapSizeInBytes(), "duplicate must not trim");

		// ...and it is content-independent in BOTH directions, which is the property MVCC actually rests on. A
		// shared backing array shows up here as a wrong KEY rather than a wrong size, so the sizes alone prove
		// nothing: write into the copy and read the source back key by key
		copy.insertKeyAt(0, keyFactory.apply(99));
		assertEquals(6, copy.size());
		assertEquals(5, column.size(), "the source must not observe the duplicate's insert");
		for (int i = 0; i < 5; i++) {
			assertEquals(keyFactory.apply(i), column.keyAt(i), "the source must not alias the duplicate at slot " + i);
		}
		// put the copy back to the source's content so the reverse direction can be read below
		copy.removeKeyAt(0);
		assertEquals(5, copy.size());

		// fillEmpty is size-authoritative: the split constructor passes capacity() as its exclusive bound, and a
		// column whose backing is far shorter than that must treat it as a truncation rather than a fill
		column.fillEmpty(2, column.capacity());
		assertEquals(2, column.size());
		assertEquals(SIZING_CAPACITY, column.capacity());
		assertEquals(5, copy.size(), "the duplicate must not observe the source's truncation");
		for (int i = 0; i < 5; i++) {
			assertEquals(keyFactory.apply(i), copy.keyAt(i), "the duplicate must not alias the source at slot " + i);
		}

		// clearAt truncates a live slot and is a strict no-op past the live run
		column.clearAt(5);
		assertEquals(2, column.size(), "clearAt past the live run changes nothing");
		column.clearAt(1);
		assertEquals(1, column.size());

		// removeKeyAt collapses the run and is a no-op past it
		column.removeKeyAt(3);
		assertEquals(1, column.size(), "removeKeyAt past the live run changes nothing");
		column.removeKeyAt(0);
		assertEquals(0, column.size());
		assertEquals(SIZING_CAPACITY, column.capacity(), "draining a column must never move its logical capacity");

		// bulkLoad sizes exactly to the count
		final Object[] bulk = new Object[10];
		for (int i = 0; i < bulk.length; i++) {
			bulk[i] = keyFactory.apply(i);
		}
		final ValueColumn<M> loaded = emptyColumnFactory.apply(SIZING_CAPACITY);
		loaded.bulkLoad(bulk, bulk.length);
		assertEquals(bulk.length, loaded.size());
		assertEquals(SIZING_CAPACITY, loaded.capacity());
		for (int i = 0; i < bulk.length; i++) {
			assertEquals(keyFactory.apply(i), loaded.keyAt(i), "bulk-loaded key mismatch at slot " + i);
		}

		// copyRangeTo grows a destination that has never been written
		final ValueColumn<M> destination = loaded.allocate(SIZING_CAPACITY);
		assertEquals(0, destination.size());
		loaded.copyRangeTo(2, destination, 0, 4);
		assertEquals(4, destination.size(), "the destination must be grown by the copy");
		for (int i = 0; i < 4; i++) {
			assertEquals(keyFactory.apply(i + 2), destination.keyAt(i), "copied key mismatch at slot " + i);
		}

		// ...and grows again for a copy landing past its own live end
		loaded.copyRangeTo(0, destination, 6, 2);
		assertEquals(8, destination.size());
		assertEquals(keyFactory.apply(0), destination.keyAt(6));

		// a source range reaching past the live run is REFUSED rather than filled with empty keys: a key column has
		// no empty key to substitute, so absorbing it would turn a caller bug into a tree holding wrong keys. This
		// is the one place the two column families deliberately disagree - a record column answers zeroes here
		final ValueColumn<M> shortSource = emptyColumnFactory.apply(SIZING_CAPACITY);
		shortSource.insertKeyAt(0, keyFactory.apply(0));
		final ValueColumn<M> receiver = shortSource.allocate(SIZING_CAPACITY);
		assertThrows(
			GenericEvitaInternalError.class,
			() -> shortSource.copyRangeTo(0, receiver, 0, 3),
			"a source range past the live run must be refused"
		);
		assertThrows(
			GenericEvitaInternalError.class,
			() -> shortSource.copyRangeTo(1, receiver, 0, 1),
			"a source range starting past the live run must be refused"
		);
		assertEquals(0, receiver.size(), "a refused copy must leave the destination untouched");

		// the in-place right shift the leaf performs when it steals from its left sibling
		final ValueColumn<M> shifted = emptyColumnFactory.apply(SIZING_CAPACITY);
		for (int i = 0; i < 4; i++) {
			shifted.insertKeyAt(i, keyFactory.apply(i));
		}
		shifted.copyRangeTo(0, shifted, 3, 4);
		assertEquals(7, shifted.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(keyFactory.apply(i), shifted.keyAt(i + 3), "shifted key mismatch at slot " + (i + 3));
		}

		if (fixedSlotStorage) {
			// a full column has no slack; one drained to a single key has plenty
			assertSame(full, full.trimmed(), "a full column has no slack to reclaim");
			full.fillEmpty(1, SIZING_CAPACITY);
			assertEquals(1, full.size());
			final ValueColumn<M> trimmed = full.trimmed();
			assertNotSame(full, trimmed, "a column drained to one key of sixty-four must shrink");
			assertEquals(1, trimmed.size());
			assertEquals(SIZING_CAPACITY, trimmed.capacity(), "a trim must never move the logical capacity");
			assertEquals(keyFactory.apply(0), trimmed.keyAt(0));
			assertTrue(
				trimmed.getHeapSizeInBytes() < full.getHeapSizeInBytes(),
				"the trimmed column must actually be cheaper"
			);
			assertSame(trimmed, trimmed.trimmed(), "trimming is idempotent");
		} else {
			// a front-coded blob is re-trimmed by every write, so there is never anything left for the merge to take
			assertSame(full, full.trimmed(), "a front-coded column is already exact");
			full.fillEmpty(1, SIZING_CAPACITY);
			assertSame(full, full.trimmed(), "a front-coded column stays exact after a truncation");
		}
	}

	/**
	 * Drives one {@link RecordColumn} implementation through the same contract, plus the two things only the record
	 * family carries: a read of a slot that was never written answers `0` rather than throwing, and {@code setAt}
	 * past the live run materializes it and zero-fills the gap. Both are what let a value id column be attached to a
	 * populated leaf empty and then stamped slot by slot.
	 *
	 * @param emptyColumnFactory builds a fresh empty column at the given logical capacity
	 */
	static void assertRecordColumnSizing(@Nonnull IntFunction<RecordColumn> emptyColumnFactory) {
		final RecordColumn column = emptyColumnFactory.apply(SIZING_CAPACITY);
		assertEquals(SIZING_CAPACITY, column.capacity(), "the logical capacity is set at construction");
		assertEquals(0, column.size(), "a fresh column holds nothing");
		assertSame(column, column.trimmed(), "an empty column has no slack to reclaim");
		final long emptyHeap = column.getHeapSizeInBytes();

		// a slot that has never been written reads as zero right up to the logical capacity - the value a
		// fixed, zero-filled block always held there
		assertEquals(0, column.intAt(0));
		assertEquals(0L, column.longAt(SIZING_CAPACITY - 1));

		// inserts raise the live count and never the logical capacity
		for (int i = 0; i < 5; i++) {
			column.insertAt(i, 1_000L + i);
		}
		assertEquals(5, column.size());
		assertEquals(SIZING_CAPACITY, column.capacity(), "an insert must never move the logical capacity");
		for (int i = 0; i < 5; i++) {
			assertEquals(1_000L + i, column.longAt(i), "record mismatch at slot " + i);
		}
		assertTrue(column.getHeapSizeInBytes() > emptyHeap, "a populated column must cost more than an empty one");

		// setAt past the live run materializes the slot and leaves the gap reading as zero
		column.setAt(9, 999L);
		assertEquals(10, column.size(), "setAt past the live run extends it");
		assertEquals(999L, column.longAt(9));
		for (int i = 5; i < 9; i++) {
			assertEquals(0L, column.longAt(i), "the gap opened by setAt must read as zero at slot " + i);
		}

		// insertAt shifts the live tail
		column.insertAt(0, 7L);
		assertEquals(11, column.size());
		assertEquals(7L, column.longAt(0));
		assertEquals(1_000L, column.longAt(1));

		// removeAt collapses the run and is a no-op past it
		column.removeAt(0);
		assertEquals(10, column.size());
		assertEquals(1_000L, column.longAt(0));
		column.removeAt(50);
		assertEquals(10, column.size(), "removeAt past the live run changes nothing");

		// clearAt truncates a live slot and is a strict no-op past the live run
		column.clearAt(20);
		assertEquals(10, column.size(), "clearAt past the live run changes nothing");
		column.clearAt(3);
		assertEquals(3, column.size());
		assertEquals(0L, column.longAt(3), "a truncated slot reads as zero again");

		// fillEmpty is size-authoritative and tolerates capacity() as its exclusive bound
		column.fillEmpty(1, column.capacity());
		assertEquals(1, column.size());
		assertEquals(SIZING_CAPACITY, column.capacity());

		// bulkLoad sizes exactly to the count
		final long[] payloads = new long[10];
		for (int i = 0; i < payloads.length; i++) {
			payloads[i] = 2_000L + i;
		}
		final RecordColumn loaded = emptyColumnFactory.apply(SIZING_CAPACITY);
		loaded.bulkLoad(payloads, payloads.length);
		assertEquals(payloads.length, loaded.size());
		assertEquals(SIZING_CAPACITY, loaded.capacity());
		for (int i = 0; i < payloads.length; i++) {
			assertEquals(payloads[i], loaded.longAt(i), "bulk-loaded record mismatch at slot " + i);
		}

		// duplicate keeps the physical shape verbatim and is independent of the source
		final RecordColumn copy = loaded.duplicate();
		assertEquals(loaded.size(), copy.size());
		assertEquals(loaded.capacity(), copy.capacity());
		assertEquals(loaded.getHeapSizeInBytes(), copy.getHeapSizeInBytes(), "duplicate must not trim");
		copy.setAt(0, 555L);
		assertNotEquals(555L, loaded.longAt(0), "the duplicate must not alias the source");

		// copyRangeTo grows a destination that has never been written
		final RecordColumn destination = loaded.allocate(SIZING_CAPACITY);
		assertEquals(0, destination.size());
		loaded.copyRangeTo(2, destination, 0, 4);
		assertEquals(4, destination.size(), "the destination must be grown by the copy");
		for (int i = 0; i < 4; i++) {
			assertEquals(payloads[i + 2], destination.longAt(i), "copied record mismatch at slot " + i);
		}

		// ...and grows again for a copy landing past its own live end, zeroing the gap
		loaded.copyRangeTo(0, destination, 6, 2);
		assertEquals(8, destination.size());
		assertEquals(0L, destination.longAt(4), "the gap opened by the right shift must read as zero");
		assertEquals(payloads[0], destination.longAt(6));

		// a donor whose own live run is shorter than the copied range hands over zeroes rather than throwing -
		// exactly the shape of a value id column attached to a populated leaf and not yet back-filled
		final RecordColumn unsized = emptyColumnFactory.apply(SIZING_CAPACITY);
		final RecordColumn receiver = emptyColumnFactory.apply(SIZING_CAPACITY);
		unsized.copyRangeTo(0, receiver, 0, 4);
		assertEquals(4, receiver.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(0L, receiver.longAt(i), "an unwritten donor slot copies as zero");
		}

		// the in-place right shift the leaf performs when it steals from its left sibling
		final RecordColumn shifted = emptyColumnFactory.apply(SIZING_CAPACITY);
		for (int i = 0; i < 4; i++) {
			shifted.insertAt(i, 10L + i);
		}
		shifted.copyRangeTo(0, shifted, 3, 4);
		assertEquals(7, shifted.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(10L + i, shifted.longAt(i + 3), "shifted record mismatch at slot " + (i + 3));
		}

		// a full column has no slack; one drained to a single record has plenty
		final RecordColumn full = emptyColumnFactory.apply(SIZING_CAPACITY);
		for (int i = 0; i < SIZING_CAPACITY; i++) {
			full.insertAt(i, 3_000L + i);
		}
		assertSame(full, full.trimmed(), "a full column has no slack to reclaim");
		full.fillEmpty(1, SIZING_CAPACITY);
		assertEquals(1, full.size());
		final RecordColumn trimmed = full.trimmed();
		assertNotSame(full, trimmed, "a column drained to one record of sixty-four must shrink");
		assertEquals(1, trimmed.size());
		assertEquals(SIZING_CAPACITY, trimmed.capacity(), "a trim must never move the logical capacity");
		assertEquals(3_000L, trimmed.longAt(0));
		assertTrue(
			trimmed.getHeapSizeInBytes() < full.getHeapSizeInBytes(),
			"the trimmed column must actually be cheaper"
		);
		assertSame(trimmed, trimmed.trimmed(), "trimming is idempotent");
	}

	/**
	 * Renders `value` as its UTF-16 code units, so a failure message distinguishes shapes that would
	 * otherwise all print as the same unprintable glyph.
	 *
	 * @param value the value to describe
	 * @return the value's code units in hex
	 */
	@Nonnull
	static String describe(@Nonnull String value) {
		final StringBuilder result = new StringBuilder(value.length() * 5 + 2);
		for (int i = 0; i < value.length(); i++) {
			result.append(String.format("%04X ", (int) value.charAt(i)));
		}
		return "[" + result.toString().trim() + "]";
	}
}
