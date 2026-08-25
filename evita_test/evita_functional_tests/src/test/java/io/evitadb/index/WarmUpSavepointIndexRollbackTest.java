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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TransactionalBitmap} and the composite indexes' own memoized state rewind their
 * non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint} bracketing them is rolled back, and keep them
 * when it is committed.
 *
 * The bitmap journals like the collection wrappers — one inverse PER OPERATION, restoring exactly the membership that
 * operation changed, with a bulk write folding its whole delta into a single inverse. It has no whole-structure
 * pre-image to fall back on, so what is worth testing beyond "a write was rewound" is composition: the tests below
 * drive several writes per savepoint across every mutator overload, deliberately make their deltas overlap and rewrite
 * the same bits in both directions, and assert that replaying the recorded inverses newest-first lands back on exactly
 * the pre-savepoint members. A bit written several times inside one savepoint is the case that can only come out right
 * if every inverse is an ABSOLUTE restore of the membership its own operation captured.
 *
 * A bulk write is the one place the inverse is necessarily recorded AFTER the flips it reverts, because a delta is not
 * knowable before the write that produces it — so two tests inject a failure PART-WAY through a bulk walk (see
 * {@link FailingIteratorBitmap}) and assert the rollback still rewinds what the failed write managed to change. That
 * is the property the whole mechanism exists to provide, and the one that would fail silently if it regressed.
 *
 * The composite indexes contribute nothing of their own to a rollback except their memoized caches — everything else
 * they hold is one of the wrapper structures covered by the sibling suites. A cache is left INVALIDATED rather than
 * restored, so the tests deliberately READ each index while the savepoint is open (which repopulates the cache from
 * half-mutated data) before rolling back: an implementation that only relied on the forward mutator's invalidation
 * passes without that read and fails with it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepointScalarAndArrayRollbackTest for the structures whose pre-image is a bare reference
 * @see WarmUpSavepointCollectionRollbackTest for the structures that journal per operation instead
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of bitmaps and composite indexes")
class WarmUpSavepointIndexRollbackTest {

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("TransactionalBitmap")
	class Bitmaps {

		@Test
		@DisplayName("Rollback restores the record set through every mutator kind")
		void shouldRestoreRecordSetAfterMixedMutations() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 3, 5, 7);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(2);
			bitmap.remove(3);
			bitmap.addAll(4, 6);
			bitmap.removeAll(1, 5);
			bitmap.addAll(new BaseBitmap(8, 9));
			bitmap.removeAll(new BaseBitmap(7, 8));
			assertArrayEquals(
				new int[]{2, 4, 6, 9}, bitmap.getArray(), "self-check on the in-savepoint state"
			);
			savepoint.rollback();

			assertArrayEquals(
				new int[]{1, 3, 5, 7}, bitmap.getArray(),
				"Rollback must restore the exact pre-savepoint record set."
			);
		}

		@Test
		@DisplayName("Rollback re-invalidates a cardinality memoized from the half-mutated bitmap")
		void shouldNotLeaveStaleMemoizedCardinality() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);
			assertEquals(3, bitmap.size(), "self-check: the pre-savepoint cardinality is memoized");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(4, 5);
			// reading the size INSIDE the savepoint is what makes this test bite: it recomputes the memo from the
			// half-mutated bitmap, so an inverse that only put the members back would leave 5 behind
			assertEquals(5, bitmap.size(), "self-check: the in-savepoint cardinality is memoized");
			savepoint.rollback();

			assertEquals(3, bitmap.size(), "The memoized cardinality must describe the restored record set.");
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
		}

		@Test
		@DisplayName("Rollback restores members spread across several roaring containers")
		void shouldRestoreAcrossContainerBoundaries() {
			// three distinct chunk keys, so the writes below land in three different roaring containers and the
			// rollback has to reach each of them - including one the savepoint's own writes brought into existence
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 70_000, 140_000);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(2);
			bitmap.add(70_001);
			bitmap.remove(140_000);
			bitmap.addAll(new BaseBitmap(210_000));
			savepoint.rollback();

			assertArrayEquals(
				new int[]{1, 70_000, 140_000}, bitmap.getArray(),
				"Every container the savepoint's writes reached must be rewound, including one it created."
			);
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("Rollback restores a bitmap emptied inside the savepoint")
		void shouldRestoreEmptiedBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.removeAll(new BaseBitmap(1, 2, 3));
			assertTrue(bitmap.isEmpty(), "self-check: the bitmap was emptied inside the savepoint");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("Rollback restores a bitmap that was empty before the savepoint opened")
		void shouldRestoreInitiallyEmptyBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(1, 2, 3);
			savepoint.rollback();

			assertTrue(bitmap.isEmpty(), "A bitmap that held nothing must hold nothing again.");
			assertEquals(0, bitmap.size());
		}

		@Test
		@DisplayName("Each savepoint captures afresh, so successive rollbacks each restore their own pre-state")
		void shouldRestoreAcrossSuccessiveSavepoints() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint first = WarmUpSavepoint.open();
			bitmap.add(3);
			first.rollback();
			assertArrayEquals(new int[]{1, 2}, bitmap.getArray());

			// the second savepoint starts from the state the first one restored, so a journal entry that leaked
			// across the savepoint boundary would be replayed here against a bitmap it no longer describes
			final WarmUpSavepoint second = WarmUpSavepoint.open();
			bitmap.add(4);
			second.commit();
			assertArrayEquals(new int[]{1, 2, 4}, bitmap.getArray());

			final WarmUpSavepoint third = WarmUpSavepoint.open();
			bitmap.remove(1);
			third.rollback();
			assertArrayEquals(
				new int[]{1, 2, 4}, bitmap.getArray(),
				"The third rollback must restore the state the second savepoint committed."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepRecordSetOnCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(3);
			bitmap.removeAll(1);
			savepoint.commit();

			assertArrayEquals(new int[]{2, 3}, bitmap.getArray(), "Commit must keep the savepoint's writes.");
			assertEquals(2, bitmap.size());
		}

		@Test
		@DisplayName("Only the bitmaps actually written inside the savepoint are rewound")
		void shouldLeaveUntouchedBitmapsAlone() {
			final TransactionalBitmap touched = new TransactionalBitmap(1);
			final TransactionalBitmap untouched = new TransactionalBitmap(9);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touched.add(2);
			savepoint.rollback();

			assertArrayEquals(new int[]{1}, touched.getArray(), "The written bitmap must be rewound.");
			assertArrayEquals(new int[]{9}, untouched.getArray(), "A bitmap nobody wrote must be left as it was.");
		}

		@Test
		@DisplayName("A no-op mutator leaves the bitmap untouched on rollback")
		void shouldTolerateNoOpMutations() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// neither call changes anything - `add` of a present id and `remove` of an absent one short-circuit
			bitmap.add(1);
			bitmap.remove(3);
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2}, bitmap.getArray());
			assertEquals(2, bitmap.size());
		}

		@Test
		@DisplayName("A bit rewritten in both directions returns to the membership it had before the savepoint")
		void shouldRestoreBitRewrittenInBothDirections() {
			// 1 starts present and 4 starts absent; each is then flipped three times, so each ends the savepoint on
			// the OPPOSITE membership from where it started. Only inverses that are absolute restores of the
			// membership captured by their own operation - replayed newest-first, so the earliest capture wins last -
			// can land both bits back where they began
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(4);
			bitmap.remove(4);
			bitmap.add(4);
			bitmap.remove(1);
			bitmap.add(1);
			bitmap.remove(1);
			assertArrayEquals(
				new int[]{2, 4}, bitmap.getArray(), "self-check: both bits ended the savepoint flipped"
			);
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2}, bitmap.getArray());
			assertEquals(2, bitmap.size());
		}

		@Test
		@DisplayName("A bulk write carrying duplicate ids is rewound exactly once per id")
		void shouldRestoreBulkWithDuplicateIds() {
			// the repeated ids find the bit already in its new state and must not enter the operation's delta a
			// second time - an inverse that re-applied them would still be correct here, but one that counted them
			// would run off the end of its delta buffer
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(3, 3, 4, 3);
			bitmap.removeAll(1, 2, 1, 2);
			assertArrayEquals(new int[]{3, 4}, bitmap.getArray(), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2}, bitmap.getArray());
			assertEquals(2, bitmap.size());
		}

		@Test
		@DisplayName("A bulk write overlapping earlier single-record writes is rewound")
		void shouldRestoreBulkOverlappingEarlierSingleRecordWrites() {
			// 5 is added singly and then re-offered in bulk (where it no longer changes anything), 1 is removed
			// singly and then re-offered to a bulk removal, while 2 is only ever touched in bulk
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(5);
			bitmap.remove(1);
			bitmap.addAll(new BaseBitmap(4, 5));
			bitmap.removeAll(new BaseBitmap(1, 2));
			assertArrayEquals(new int[]{3, 4, 5}, bitmap.getArray(), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("Single-record writes overlapping an earlier bulk write are rewound")
		void shouldRestoreSingleRecordWritesOverlappingEarlierBulk() {
			// the mirror ordering: the bulk delta is captured first and the single-record inverses are pushed on top
			// of it, so the replay has to reach the bulk entry LAST for 4 and 1 to end up where they started
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(4, 5);
			bitmap.removeAll(1, 2);
			bitmap.remove(4);
			bitmap.add(1);
			assertArrayEquals(new int[]{1, 3, 5}, bitmap.getArray(), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("A bulk write that changes no membership journals nothing and rewinds cleanly")
		void shouldTolerateBulkThatChangesNothing() {
			// every id offered is already in the state the call would put it in, so both bulk writes produce an
			// empty delta - the case where a per-operation inverse must NOT be recorded at all
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(1, 2, 3);
			bitmap.removeAll(7, 8);
			bitmap.addAll(new BaseBitmap(2, 3));
			bitmap.removeAll(new BaseBitmap(9));
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray(), "self-check: nothing changed in the savepoint");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("A bulk add that dies mid-walk still rewinds the members it managed to add")
		void shouldRestoreWhenBulkAdditionFailsMidWalk() {
			// the delta of a bulk write is only knowable once the write has produced it, so it is journalled AFTER
			// the flips - which is only safe if a walk that never reaches its end journals what it already flipped.
			// The iterator below throws with two of its four ids consumed, leaving two bits flipped
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			assertThrows(
				SimulatedWalkFailure.class, () -> bitmap.addAll(new FailingIteratorBitmap(2, 5, 6, 7, 8))
			);
			assertArrayEquals(
				new int[]{1, 2, 5, 6}, bitmap.getArray(),
				"self-check: the walk flipped two bits before its iterator threw"
			);
			savepoint.rollback();

			assertArrayEquals(
				new int[]{1, 2}, bitmap.getArray(),
				"A rollback after a failed bulk write must rewind the flips the write did make."
			);
			assertEquals(2, bitmap.size(), "The memoized cardinality must describe the restored record set.");
		}

		@Test
		@DisplayName("A bulk removal that dies mid-walk still rewinds the members it managed to remove")
		void shouldRestoreWhenBulkRemovalFailsMidWalk() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3, 4);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			assertThrows(
				SimulatedWalkFailure.class, () -> bitmap.removeAll(new FailingIteratorBitmap(2, 1, 2, 3, 4))
			);
			assertArrayEquals(
				new int[]{3, 4}, bitmap.getArray(),
				"self-check: the walk removed two members before its iterator threw"
			);
			savepoint.rollback();

			assertArrayEquals(
				new int[]{1, 2, 3, 4}, bitmap.getArray(),
				"A rollback after a failed bulk write must put back the members the write did remove."
			);
			assertEquals(4, bitmap.size(), "The memoized cardinality must describe the restored record set.");
		}

		@Test
		@DisplayName("Rollback of a long interleaved sequence matches a reference set")
		void shouldMatchReferenceSetAfterInterleavedMutations() {
			final int[] baseline = {1, 4, 9, 70_000, 70_005, 140_000};
			final TransactionalBitmap bitmap = new TransactionalBitmap(baseline);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			applyInterleavedMutations(bitmap);
			savepoint.rollback();

			assertArrayEquals(
				baseline, bitmap.getArray(),
				"Rollback of an interleaved sequence must land on exactly the reference baseline."
			);
			assertEquals(baseline.length, bitmap.size());
		}

		@Test
		@DisplayName("Commit of a long interleaved sequence keeps exactly what a reference set predicts")
		void shouldMatchReferenceSetAfterCommittedInterleavedMutations() {
			final int[] baseline = {1, 4, 9, 70_000, 70_005, 140_000};
			final TransactionalBitmap bitmap = new TransactionalBitmap(baseline);
			// the same sequence applied to a plain sorted set, which is the oracle for what a COMMIT must keep
			final TreeSet<Integer> reference = new TreeSet<>();
			for (final int recordId : baseline) {
				reference.add(recordId);
			}

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			applyInterleavedMutations(bitmap);
			applyInterleavedMutations(reference);
			savepoint.commit();

			assertArrayEquals(
				toArray(reference), bitmap.getArray(),
				"Commit must keep exactly the members the same sequence produces on a reference set."
			);
			assertEquals(reference.size(), bitmap.size());
		}
	}

	/**
	 * Applies one fixed interleaved mutation sequence to a bitmap — every mutator overload, deltas that overlap each
	 * other and the single-record writes, bits flipped in both directions, and ids spread across three roaring
	 * containers. Written once so the rollback oracle and the commit oracle provably drive the SAME sequence.
	 *
	 * @param bitmap the bitmap to mutate
	 */
	private static void applyInterleavedMutations(@Nonnull TransactionalBitmap bitmap) {
		bitmap.add(2);
		bitmap.addAll(2, 3, 4);
		bitmap.remove(4);
		bitmap.removeAll(new BaseBitmap(1, 2, 3));
		bitmap.add(1);
		bitmap.addAll(new BaseBitmap(70_001, 70_005, 210_000));
		bitmap.removeAll(70_000, 70_001, 999_999);
		bitmap.add(70_000);
		bitmap.removeAll(new BaseBitmap(140_000, 210_000));
		bitmap.addAll(9, 140_000);
	}

	/**
	 * Applies the sequence of {@link #applyInterleavedMutations(TransactionalBitmap)} to a plain sorted set, which
	 * serves as the reference oracle a committed savepoint's outcome is compared against.
	 *
	 * @param reference the set to mutate
	 */
	private static void applyInterleavedMutations(@Nonnull TreeSet<Integer> reference) {
		reference.add(2);
		reference.addAll(List.of(2, 3, 4));
		reference.remove(4);
		reference.removeAll(List.of(1, 2, 3));
		reference.add(1);
		reference.addAll(List.of(70_001, 70_005, 210_000));
		reference.removeAll(List.of(70_000, 70_001, 999_999));
		reference.add(70_000);
		reference.removeAll(List.of(140_000, 210_000));
		reference.addAll(List.of(9, 140_000));
	}

	/**
	 * Converts a sorted set of record ids into the ascending `int[]` a {@link org.junit.jupiter.api.Assertions}
	 * comparison against {@link TransactionalBitmap#getArray()} needs.
	 *
	 * @param reference the set to convert
	 * @return its members in ascending order
	 */
	@Nonnull
	private static int[] toArray(@Nonnull TreeSet<Integer> reference) {
		final int[] result = new int[reference.size()];
		int index = 0;
		for (final Integer recordId : reference) {
			result[index++] = recordId;
		}
		return result;
	}

	/**
	 * The failure a {@link FailingIteratorBitmap} raises once it has handed out its quota of record ids. A dedicated
	 * type rather than a stock runtime exception, so a test asserting it cannot accidentally pass on an unrelated
	 * failure raised somewhere inside the mutator.
	 */
	private static class SimulatedWalkFailure extends RuntimeException {
		@Serial private static final long serialVersionUID = 4_004_318_566_931_252_749L;

		SimulatedWalkFailure(int yieldCount) {
			super("Simulated failure after " + yieldCount + " record ids.");
		}
	}

	/**
	 * A hand-written {@link Bitmap} whose iterator yields a fixed prefix of its record ids and then throws
	 * {@link SimulatedWalkFailure} — the fault injection that drives a bulk mutator into failing PART-WAY through its
	 * delegate walk, with some memberships already changed.
	 *
	 * Only {@link #size()} and {@link #iterator()} are implemented, because those are the only two members the bulk
	 * mutators' savepoint-open branch reads. Every other method throws {@link UnsupportedOperationException} on
	 * purpose: an implementation change that started routing a bulk write through, say, {@link #getArray()} would
	 * bypass the fault injection entirely and make these tests pass vacuously, so it has to fail loudly instead.
	 *
	 * The `int[]`-argument overloads cannot be fault-injected this way — nothing about a plain `int[]` walk can be made
	 * to throw without mocking roaring itself, which this project does not do. They are covered by construction: all
	 * four bulk helpers share one shape (reserve the delta slot, flip the bit, push the delta from a `finally`), and
	 * these two tests pin that shape end to end.
	 */
	private static class FailingIteratorBitmap implements Bitmap {
		@Serial private static final long serialVersionUID = -8_921_744_502_115_631_037L;
		/**
		 * The record ids this bitmap reports as its contents, in iteration order.
		 */
		private final int[] recordIds;
		/**
		 * How many of {@link #recordIds} the iterator hands out before it starts throwing.
		 */
		private final int yieldCount;

		FailingIteratorBitmap(int yieldCount, @Nonnull int... recordIds) {
			this.yieldCount = yieldCount;
			this.recordIds = recordIds;
		}

		@Override
		public int size() {
			return this.recordIds.length;
		}

		@Nonnull
		@Override
		public OfInt iterator() {
			return new OfInt() {
				private int index;

				@Override
				public boolean hasNext() {
					return this.index < FailingIteratorBitmap.this.recordIds.length;
				}

				@Override
				public int nextInt() {
					if (this.index >= FailingIteratorBitmap.this.yieldCount) {
						throw new SimulatedWalkFailure(FailingIteratorBitmap.this.yieldCount);
					}
					return FailingIteratorBitmap.this.recordIds[this.index++];
				}
			};
		}

		@Override
		public boolean isEmpty() {
			throw unreached("isEmpty");
		}

		@Override
		public boolean add(int recordId) {
			throw unreached("add");
		}

		@Override
		public void addAll(int... recordId) {
			throw unreached("addAll(int...)");
		}

		@Override
		public void addAll(@Nonnull Bitmap recordIds) {
			throw unreached("addAll(Bitmap)");
		}

		@Override
		public boolean remove(int recordId) {
			throw unreached("remove");
		}

		@Override
		public void removeAll(int... recordId) {
			throw unreached("removeAll(int...)");
		}

		@Override
		public void removeAll(@Nonnull Bitmap recordIds) {
			throw unreached("removeAll(Bitmap)");
		}

		@Override
		public boolean contains(int recordId) {
			throw unreached("contains");
		}

		@Override
		public int indexOf(int recordId) {
			throw unreached("indexOf");
		}

		@Override
		public int get(int index) {
			throw unreached("get");
		}

		@Override
		public int[] getRange(int start, int end) {
			throw unreached("getRange");
		}

		@Override
		public int getFirst() {
			throw unreached("getFirst");
		}

		@Override
		public int getLast() {
			throw unreached("getLast");
		}

		@Override
		public int[] getArray() {
			throw unreached("getArray");
		}

		@Override
		public long getHeapSizeInBytes() {
			throw unreached("getHeapSizeInBytes");
		}

		/**
		 * Builds the failure raised by a member this fake deliberately does not implement.
		 *
		 * @param method the member that was called
		 * @return the exception to throw
		 */
		@Nonnull
		private static UnsupportedOperationException unreached(@Nonnull String method) {
			return new UnsupportedOperationException(
				"FailingIteratorBitmap#" + method + " was called - the bulk mutator under test is expected to read " +
					"this argument only through size() and iterator(), so reaching here means the fault injection " +
					"no longer covers the path it was written for."
			);
		}
	}

	@Nested
	@DisplayName("HierarchyIndex")
	@Tag(HIERARCHY)
	class CompositeIndexes {

		/**
		 * Builds the same small forest every test in this class starts from: two roots (`1` and `2`), two children
		 * under `1`, one child under `2`, and one orphan whose parent does not exist.
		 *
		 * @return a freshly populated hierarchy index
		 */
		@Nonnull
		private HierarchyIndex newSeededHierarchy() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.addNode(2, null);
			index.addNode(3, 1);
			index.addNode(4, 1);
			index.addNode(5, 2);
			index.addNode(6, 99);
			return index;
		}

		@Test
		@DisplayName("Rollback restores the whole hierarchy, memoized formula included")
		void shouldRestoreHierarchyAndInvalidateMemoizedFormula() {
			final HierarchyIndex index = newSeededHierarchy();
			final int[] nodesBefore = index.listHierarchyNodesFromRoot().getArray();
			final int[] orphansBefore = index.getOrphanHierarchyNodes().getArray();
			final int[] allNodesBefore = index.getAllHierarchyNodesFormula().compute().getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			index.addNode(7, 3);
			index.removeNode(4);
			index.addNode(8, null);
			// the read INSIDE the savepoint is the point of this test: it repopulates the memoized formula from the
			// half-mutated hierarchy, so a rollback that rewound only the nodes would answer the next query from it
			final int[] allNodesInside = index.getAllHierarchyNodesFormula().compute().getArray();
			assertTrue(
				allNodesInside.length != allNodesBefore.length,
				"self-check: the in-savepoint batch must have changed the node set"
			);
			savepoint.rollback();

			assertArrayEquals(
				nodesBefore, index.listHierarchyNodesFromRoot().getArray(),
				"Rollback must restore the exact pre-savepoint node order."
			);
			assertArrayEquals(
				orphansBefore, index.getOrphanHierarchyNodes().getArray(),
				"Rollback must restore the orphans as well."
			);
			assertArrayEquals(
				allNodesBefore, index.getAllHierarchyNodesFormula().compute().getArray(),
				"The memoized formula must be recomputed from the restored hierarchy, not served stale."
			);
		}

		@Test
		@DisplayName("Rollback restores a node whose removal orphaned its children")
		void shouldRestoreChildrenOrphanedByARemoval() {
			final HierarchyIndex index = newSeededHierarchy();
			final int[] nodesBefore = index.listHierarchyNodesFromRoot().getArray();
			final int[] orphansBefore = index.getOrphanHierarchyNodes().getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// removing a root pushes its whole subtree into the orphan collection - the widest single mutation this
			// index performs, touching the item index, the level index, the roots array and the orphans array at once
			index.removeNode(1);
			assertTrue(index.getOrphanHierarchyNodes().size() > orphansBefore.length, "self-check: children orphaned");
			savepoint.rollback();

			assertArrayEquals(nodesBefore, index.listHierarchyNodesFromRoot().getArray());
			assertArrayEquals(orphansBefore, index.getOrphanHierarchyNodes().getArray());
		}

		@Test
		@DisplayName("Rollback restores an orphan promoted by the arrival of its parent")
		void shouldRestorePromotedOrphan() {
			final HierarchyIndex index = newSeededHierarchy();
			final int[] nodesBefore = index.listHierarchyNodesFromRoot().getArray();
			final int[] orphansBefore = index.getOrphanHierarchyNodes().getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// node 6 has been waiting for parent 99; creating it promotes 6 out of the orphans and into the tree
			index.addNode(99, null);
			assertEquals(0, index.getOrphanHierarchyNodes().size(), "self-check: the orphan was promoted");
			savepoint.rollback();

			assertArrayEquals(nodesBefore, index.listHierarchyNodesFromRoot().getArray());
			assertArrayEquals(
				orphansBefore, index.getOrphanHierarchyNodes().getArray(),
				"The promoted node must be an orphan again."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepHierarchyOnCommit() {
			final HierarchyIndex index = newSeededHierarchy();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			index.addNode(7, 3);
			savepoint.commit();

			final Bitmap nodes = index.listHierarchyNodesFromRoot();
			assertTrue(nodes.contains(7), "Commit must keep the savepoint's writes.");
		}
	}

}
