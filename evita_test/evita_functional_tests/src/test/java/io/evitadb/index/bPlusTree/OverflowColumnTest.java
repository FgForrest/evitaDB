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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the third parallel column of a bucket tree leaf — the lazy {@link OverflowColumn} that holds one
 * {@link TransactionalBitmap} per multi-record bucket and `null` everywhere else.
 *
 * It carries the same logical-capacity-over-content-sized-backing contract the key and record columns carry, plus one
 * of its own: its {@code duplicate()} is deliberately **shallow**, because each bitmap in it is itself a transactional
 * structure owning its own diff layer and its own savepoint memento. Deep-copying one here would produce a detached
 * instance the commit sweep never sees, so the shallow clone is asserted rather than assumed.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("Leaf overflow column: logical capacity over content-sized backing")
class OverflowColumnTest {

	/**
	 * The leaf block size these assertions use — the production value, so the numbers read as the ones a real
	 * inverted index takes.
	 */
	private static final int BLOCK = 256;

	/**
	 * Builds a distinguishable bitmap for the given ordinal.
	 *
	 * @param ordinal the ordinal to derive the bitmap's content from
	 * @return a two-record bitmap unique to the ordinal
	 */
	@Nonnull
	private static TransactionalBitmap bitmap(int ordinal) {
		return new TransactionalBitmap(1_000 + ordinal, 2_000 + ordinal);
	}

	@Nested
	@DisplayName("capacity is logical, the backing array follows the content")
	class Sizing {

		@Test
		@DisplayName("a fresh column reports the block size and holds nothing")
		void shouldReportLogicalCapacityWhenFreshlyCreated() {
			final OverflowColumn column = new OverflowColumn(BLOCK);
			assertEquals(BLOCK, column.capacity());
			assertEquals(0, column.size());
			assertNull(column.bitmapAt(0), "an unmaterialized slot reads as null");
			assertNull(column.bitmapAt(BLOCK - 1), "the whole logical run reads as null");
		}

		@Test
		@DisplayName("an aligned column observes its whole live run")
		void shouldObserveItsWholeLiveRunWhenAligned() {
			// `observableLiveRun()` is the bound a reader with no happens-before edge to the writer takes instead of
			// `size()`, and its whole value rests on it being EXACTLY `size()` whenever no write is in flight. An
			// implementation that under-reported here would quietly drop the tail buckets of every leaf out of the
			// cursors that bound themselves by it
			final OverflowColumn column = new OverflowColumn(BLOCK);
			assertEquals(0, column.observableLiveRun(), "a fresh column observes nothing");

			for (int i = 0; i < 9; i++) {
				column.insertAt(i, bitmap(i));
			}
			assertEquals(column.size(), column.observableLiveRun(), "nine inserts across two reallocations");

			column.fillEmpty(3, BLOCK);
			assertEquals(column.size(), column.observableLiveRun(), "after a truncation");

			final OverflowColumn loaded = new OverflowColumn(BLOCK);
			loaded.bulkLoad(new TransactionalBitmap[7], 7);
			assertEquals(loaded.size(), loaded.observableLiveRun(), "after a bulk load");

			final OverflowColumn drained = OverflowColumn.withLiveRun(BLOCK, 32);
			drained.fillEmpty(1, BLOCK);
			final OverflowColumn trimmed = drained.trimmed();
			assertEquals(trimmed.size(), trimmed.observableLiveRun(), "after a trim");
		}

		@Test
		@DisplayName("a read past the logical capacity is a programming error")
		void shouldThrowWhenReadingPastTheLogicalCapacity() {
			final OverflowColumn column = new OverflowColumn(BLOCK);
			assertThrows(GenericEvitaInternalError.class, () -> column.bitmapAt(BLOCK));
		}

		@Test
		@DisplayName("a write past the logical capacity is a programming error too")
		void shouldThrowWhenWritingPastTheLogicalCapacity() {
			// a column asked for a slot past the block means the leaf failed to split; growing to serve it would
			// hide that rather than report it, exactly as the read guard above refuses to answer
			assertThrows(
				GenericEvitaInternalError.class, () -> new OverflowColumn(BLOCK).setAt(BLOCK, bitmap(0)));
			assertThrows(
				GenericEvitaInternalError.class, () -> new OverflowColumn(BLOCK).insertAt(BLOCK, bitmap(0)));
			assertThrows(
				GenericEvitaInternalError.class, () -> new OverflowColumn(BLOCK).fillNulls(BLOCK, 1));
		}

		@Test
		@DisplayName("a block below the four-slot floor allocates the block and never exceeds it")
		void shouldGrowToExactlyTheBlockWhenTheBlockIsBelowTheFloor() {
			// three buckets per leaf is the block the bucket tree's own suites run at, and it sits below the
			// four-slot floor every other fixture here lands on
			final OverflowColumn column = new OverflowColumn(3);
			assertEquals(3, column.capacity());
			for (int i = 0; i < 3; i++) {
				column.insertAt(i, bitmap(i));
			}
			assertEquals(3, column.size(), "a tiny block still fills completely");
			for (int i = 0; i < 3; i++) {
				assertEquals(bitmap(i), column.bitmapAt(i), "content mismatch at slot " + i);
			}
			assertThrows(
				GenericEvitaInternalError.class, () -> column.setAt(3, bitmap(3)),
				"the floor must be capped at the block, never allocated past it"
			);
		}

		@Test
		@DisplayName("inserting grows the live run and the backing array without moving the capacity")
		void shouldGrowTheLiveRunWhenInsertingWithoutMovingTheCapacity() {
			final OverflowColumn column = new OverflowColumn(BLOCK);
			final long emptyHeap = column.getHeapSizeInBytes();
			for (int i = 0; i < 12; i++) {
				column.insertAt(i, bitmap(i));
			}
			assertEquals(12, column.size());
			assertEquals(BLOCK, column.capacity(), "no mutation ever moves the logical capacity");
			assertTrue(column.getHeapSizeInBytes() > emptyHeap, "the backing array grew with the content");
			for (int i = 0; i < 12; i++) {
				assertEquals(bitmap(i), column.bitmapAt(i), "content mismatch at slot " + i);
			}
		}

		@Test
		@DisplayName("a column sized to a live run arrives aligned and all null")
		void shouldArriveAlignedWhenCreatedWithALiveRun() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 7);
			assertEquals(7, column.size());
			assertEquals(BLOCK, column.capacity());
			for (int i = 0; i < 7; i++) {
				assertNull(column.bitmapAt(i), "every slot of a freshly aligned column is null");
			}
		}

		@Test
		@DisplayName("bulk loading sizes the backing array exactly to the page")
		void shouldSizeExactlyToThePageWhenBulkLoaded() {
			final TransactionalBitmap[] source = new TransactionalBitmap[5];
			source[1] = bitmap(1);
			source[4] = bitmap(4);
			final OverflowColumn column = new OverflowColumn(BLOCK);
			column.bulkLoad(source, 5);
			assertEquals(5, column.size());
			assertEquals(bitmap(1), column.bitmapAt(1));
			assertEquals(bitmap(4), column.bitmapAt(4));
			assertNull(column.bitmapAt(0));

			// a five-slot page costs its object plus a five-reference array and nothing more - the whole point of
			// sizing at load time rather than growing into the block size
			final OverflowColumn grown = new OverflowColumn(BLOCK);
			for (int i = 0; i < 5; i++) {
				grown.insertAt(i, source[i]);
			}
			assertTrue(
				column.getHeapSizeInBytes() <= grown.getHeapSizeInBytes(),
				"a bulk load must never allocate more than the equivalent run of inserts"
			);

			// an empty page leaves a zero-length array and nothing live at all
			final OverflowColumn emptyPage = new OverflowColumn(BLOCK);
			emptyPage.bulkLoad(source, 0);
			assertEquals(0, emptyPage.size(), "a page holding nothing materializes nothing");
			assertNull(emptyPage.bitmapAt(0));

			// and a source shorter than the page leaves the shortfall null - what a page holding no multi bucket
			// past that point means
			final OverflowColumn shortSource = new OverflowColumn(BLOCK);
			shortSource.bulkLoad(new TransactionalBitmap[]{bitmap(0)}, 4);
			assertEquals(4, shortSource.size(), "the live run follows the page, not the source array");
			assertEquals(bitmap(0), shortSource.bitmapAt(0));
			for (int i = 1; i < 4; i++) {
				assertNull(shortSource.bitmapAt(i), "the shortfall must read as null at slot " + i);
			}
		}
	}

	@Nested
	@DisplayName("slot moves keep the column aligned with the leaf")
	class SlotMoves {

		@Test
		@DisplayName("inserting a null marks the new bucket single and shifts the rest right")
		void shouldShiftRightWhenInsertingANullSlot() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			column.insertAt(0, bitmap(0));
			column.insertAt(1, bitmap(1));
			column.insertAt(1, null);
			assertEquals(3, column.size());
			assertEquals(bitmap(0), column.bitmapAt(0));
			assertNull(column.bitmapAt(1), "the inserted single bucket carries no bitmap");
			assertEquals(bitmap(1), column.bitmapAt(2));
		}

		@Test
		@DisplayName("removing a slot shifts the tail left and nulls the vacated end")
		void shouldNullTheVacatedEndWhenRemovingASlot() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			for (int i = 0; i < 3; i++) {
				column.insertAt(i, bitmap(i));
			}
			column.removeAt(0);
			assertEquals(2, column.size());
			assertEquals(bitmap(1), column.bitmapAt(0));
			assertEquals(bitmap(2), column.bitmapAt(1));
			assertNull(column.bitmapAt(2), "nothing may survive past the live run - an alias would be discarded twice");
		}

		@Test
		@DisplayName("writing past the live run materializes the gap as null")
		void shouldMaterializeTheGapWhenWritingPastTheLiveRun() {
			final OverflowColumn column = new OverflowColumn(BLOCK);
			column.setAt(5, bitmap(5));
			assertEquals(6, column.size());
			for (int i = 0; i < 5; i++) {
				assertNull(column.bitmapAt(i), "the materialized gap must read as null at slot " + i);
			}
			assertEquals(bitmap(5), column.bitmapAt(5));
		}

		@Test
		@DisplayName("clearing and truncating are size-authoritative")
		void shouldTruncateTheLiveRunWhenCleared() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			for (int i = 0; i < 6; i++) {
				column.insertAt(i, bitmap(i));
			}
			column.clearAt(4);
			assertEquals(4, column.size());
			assertNull(column.bitmapAt(4));

			// a truncation at or past the live run is a strict no-op, which is what makes it safe on a committed
			// column a transactional layer still aliases
			column.fillEmpty(4, BLOCK);
			assertEquals(4, column.size());
			column.clearAt(10);
			assertEquals(4, column.size());
		}

		@Test
		@DisplayName("a removal at or past the live run is a strict no-op")
		void shouldIgnoreARemovalPastTheLiveRun() {
			// the region past the live run is already null, so dropping one null out of a run of nulls leaves the
			// run of nulls - the answer every sibling column family gives to the same question
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			column.insertAt(0, bitmap(0));
			column.insertAt(1, bitmap(1));

			column.removeAt(2);
			column.removeAt(BLOCK - 1);
			assertEquals(2, column.size(), "a removal past the live run must not shorten it");
			assertEquals(bitmap(0), column.bitmapAt(0));
			assertEquals(bitmap(1), column.bitmapAt(1));
		}
	}

	@Nested
	@DisplayName("range copies grow their destination")
	class RangeCopies {

		@Test
		@DisplayName("a copy into a never-written destination grows it")
		void shouldGrowTheDestinationWhenCopyingIntoIt() {
			final OverflowColumn source = OverflowColumn.withLiveRun(BLOCK, 0);
			for (int i = 0; i < 6; i++) {
				source.insertAt(i, bitmap(i));
			}
			final OverflowColumn destination = new OverflowColumn(BLOCK);
			source.copyRangeTo(2, destination, 0, 4);
			assertEquals(4, destination.size());
			for (int i = 0; i < 4; i++) {
				assertEquals(bitmap(i + 2), destination.bitmapAt(i), "copied slot mismatch at " + i);
			}
		}

		@Test
		@DisplayName("a copy landing past the destination's live end nulls the gap")
		void shouldNullTheGapWhenCopyingPastTheDestinationLiveEnd() {
			final OverflowColumn source = OverflowColumn.withLiveRun(BLOCK, 0);
			source.insertAt(0, bitmap(0));
			final OverflowColumn destination = OverflowColumn.withLiveRun(BLOCK, 0);
			destination.insertAt(0, bitmap(9));
			source.copyRangeTo(0, destination, 4, 1);
			assertEquals(5, destination.size());
			assertEquals(bitmap(9), destination.bitmapAt(0));
			assertNull(destination.bitmapAt(2), "the gap opened by the copy must read as null");
			assertEquals(bitmap(0), destination.bitmapAt(4));
		}

		@Test
		@DisplayName("the in-place right shift a steal-from-left performs keeps every reference")
		void shouldKeepEveryReferenceWhenShiftingInPlace() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			for (int i = 0; i < 4; i++) {
				column.insertAt(i, bitmap(i));
			}
			column.copyRangeTo(0, column, 3, 4);
			assertEquals(7, column.size());
			for (int i = 0; i < 4; i++) {
				assertEquals(bitmap(i), column.bitmapAt(i + 3), "shifted slot mismatch at " + (i + 3));
			}
		}

		@Test
		@DisplayName("a donor with no column at all leaves the receiver's range null")
		void shouldNullTheRangeWhenTheDonorCarriesNoColumn() {
			final OverflowColumn receiver = OverflowColumn.withLiveRun(BLOCK, 0);
			for (int i = 0; i < 3; i++) {
				receiver.insertAt(i, bitmap(i));
			}
			receiver.copyRangeTo(0, receiver, 2, 3);
			receiver.fillNulls(0, 2);
			assertEquals(5, receiver.size());
			assertNull(receiver.bitmapAt(0), "the vacated range must not alias the shifted-from bitmaps");
			assertNull(receiver.bitmapAt(1));
			assertEquals(bitmap(0), receiver.bitmapAt(2));
		}

		@Test
		@DisplayName("a fill that starts past the live run is refused, one that continues it is not")
		void shouldRefuseToFillNullsPastTheLiveRun() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			column.insertAt(0, bitmap(0));
			column.insertAt(1, bitmap(1));

			// slots 2 and 3 were never materialized, so covering 4 and 5 would raise the live run over a gap and
			// leave the leaf claiming more buckets than its key column holds
			assertThrows(GenericEvitaInternalError.class, () -> column.fillNulls(4, 2));
			assertEquals(2, column.size(), "a refused fill must leave the live run exactly as it found it");

			// continuing the run is the shape every rebalance actually uses: the donated range begins where the
			// receiver's own buckets end
			column.fillNulls(2, 2);
			assertEquals(4, column.size());
			assertEquals(bitmap(0), column.bitmapAt(0), "a fill must not disturb what is live");
			assertEquals(bitmap(1), column.bitmapAt(1));
			assertNull(column.bitmapAt(2), "the appended run must read as null");
			assertNull(column.bitmapAt(3));
		}

		@Test
		@DisplayName("a source range past the live run is refused, as it is on both sibling families")
		void shouldRefuseASourceRangeThatRunsPastTheLiveRun() {
			// a null here is not an empty slot - it is the leaf's single/multi discriminator - so absorbing the
			// shortfall would hand the receiver every multi bucket in it marked single, keeping one record out of
			// each bucket's whole set. That is a caller bug that costs records, so it is reported rather than filled
			final OverflowColumn source = OverflowColumn.withLiveRun(BLOCK, 2);
			source.setAt(1, bitmap(1));
			final OverflowColumn destination = new OverflowColumn(BLOCK);

			assertThrows(GenericEvitaInternalError.class, () -> source.copyRangeTo(0, destination, 0, 4));
			assertEquals(0, destination.size(), "a refused copy must leave the destination untouched");

			// the range that stops at the donor's live end is served as before
			source.copyRangeTo(0, destination, 0, 2);
			assertEquals(2, destination.size());
			assertEquals(bitmap(1), destination.bitmapAt(1), "the multi bucket must arrive as a multi bucket");
		}
	}

	@Nested
	@DisplayName("copies obey the transactional contracts")
	class Copies {

		@Test
		@DisplayName("duplicate shares the bitmaps and nothing else")
		void shouldShareTheBitmapsWhenDuplicated() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			final TransactionalBitmap shared = bitmap(1);
			column.insertAt(0, shared);
			column.insertAt(1, null);

			final OverflowColumn copy = column.duplicate();
			assertNotSame(column, copy);
			assertEquals(column.size(), copy.size());
			assertEquals(column.capacity(), copy.capacity());
			assertEquals(
				column.getHeapSizeInBytes(), copy.getHeapSizeInBytes(),
				"a duplicate keeps the physical shape verbatim and never trims"
			);
			assertSame(
				shared, copy.bitmapAt(0),
				"the clone must be SHALLOW - each bitmap owns its own transactional layer and memento"
			);

			// ...and the arrays are independent, so the layer can null a slot without disturbing the committed leaf
			copy.setAt(0, null);
			assertSame(shared, column.bitmapAt(0), "the duplicate must not alias the source's array");
		}

		@Test
		@DisplayName("trimming waits for enough slack and then lands on a power of two")
		void shouldTrimOnlyWhenSlackIsLarge() {
			final OverflowColumn column = OverflowColumn.withLiveRun(BLOCK, 0);
			for (int i = 0; i < 16; i++) {
				column.insertAt(i, bitmap(i));
			}
			final long fullHeap = column.getHeapSizeInBytes();
			assertSame(column.trimmed(), column.trimmed(), "a trim that is not warranted returns the same instance");
			assertSame(column, column.trimmed(), "sixteen live slots in sixteen is no slack at all");

			for (int i = 15; i >= 3; i--) {
				column.removeAt(i);
			}
			assertEquals(3, column.size());
			final OverflowColumn trimmed = column.trimmed();
			assertNotSame(column, trimmed, "three live slots in sixteen is slack worth paying a copy for");
			assertEquals(3, trimmed.size());
			assertEquals(BLOCK, trimmed.capacity(), "trimming never moves the logical capacity");
			assertTrue(trimmed.getHeapSizeInBytes() < fullHeap, "a trim must actually give the bytes back");
			for (int i = 0; i < 3; i++) {
				assertEquals(bitmap(i), trimmed.bitmapAt(i), "a trim must preserve the content at slot " + i);
			}
		}
	}
}
