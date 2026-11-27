/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.bitmap;

import com.carrotsearch.hppc.predicates.IntPredicate;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import net.openhft.hashing.Access;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link BaseBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("BaseBitmap")
@Tag(INDEXING)
@Tag(DATA_TYPE)
class BaseBitmapTest {

	private static int[] toArray(OfInt iterator, int size) {
		final int[] result = new int[size];
		int index = 0;
		while (iterator.hasNext() && index < size) {
			result[index++] = iterator.next();
		}
		assertEquals(size, index);
		assertFalse(iterator.hasNext());
		return result;
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty bitmap with default constructor")
		void shouldCreateEmptyBitmapWithDefaultConstructor() {
			final BaseBitmap bitmap = new BaseBitmap();
			assertTrue(bitmap.isEmpty());
			assertEquals(0, bitmap.size());
		}

		@Test
		@DisplayName("should create bitmap from varargs")
		void shouldCreateBitmapFromVarargs() {
			final BaseBitmap bitmap = new BaseBitmap(5, 3, 8, 1);
			assertEquals(4, bitmap.size());
			// BaseBitmap sorts its elements
			assertArrayEquals(new int[]{1, 3, 5, 8}, bitmap.getArray());
		}

		@Test
		@DisplayName("should create bitmap from Bitmap copy")
		void shouldCreateBitmapFromBitmapCopy() {
			final BaseBitmap original = new BaseBitmap(1, 2, 3);
			final BaseBitmap copy = new BaseBitmap(original);
			assertEquals(3, copy.size());
			assertArrayEquals(new int[]{1, 2, 3}, copy.getArray());
			// modifying the copy should not affect the original
			copy.add(4);
			assertEquals(3, original.size());
		}

		@Test
		@DisplayName("should create bitmap from PersistentRoaringBitmap")
		void shouldCreateBitmapFromRoaringBitmap() {
			final PersistentRoaringBitmap roaring = PersistentRoaringBitmap.bitmapOf(10, 20, 30);
			final BaseBitmap bitmap = new BaseBitmap(roaring);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{10, 20, 30}, bitmap.getArray());
		}
	}

	@Nested
	@DisplayName("Adaptive construction from int[]")
	class AdaptiveConstructionEquivalenceTest {

		/**
		 * Asserts that {@link BaseBitmap#BaseBitmap(int...)} — whichever internal build path its density dispatch
		 * selects (incremental append vs. the constant-memory writer) — yields a bitmap holding exactly the
		 * sorted-distinct input, and that it is {@link Object#equals(Object) equal} (and hash-equal) to the same ids
		 * built through the reference incremental path. This pins the invariant the optimization relies on: the writer
		 * path can never diverge from incremental in container type or content, so bitmap equality (used by formula
		 * caching) is preserved regardless of which path built a given id set.
		 */
		private static void assertConstructsEquivalently(int[] ids) {
			// reference: the always-incremental build (fresh bitmap + add), wrapped without a further rebuild
			final PersistentRoaringBitmap reference = new PersistentRoaringBitmap();
			reference.add(ids);
			final BaseBitmap referenceBitmap = new BaseBitmap(reference);

			// the adaptive constructor under test — may internally route to the writer for large, dense inputs
			final BaseBitmap adaptive = new BaseBitmap(ids);

			final int[] expected = sortedDistinct(ids);
			assertArrayEquals(expected, adaptive.getArray());
			assertEquals(expected.length, adaptive.size());
			assertEquals(referenceBitmap, adaptive, "adaptive build must equal the incremental build");
			assertEquals(referenceBitmap.hashCode(), adaptive.hashCode(), "equal bitmaps must share a hash code");
			for (int id : ids) {
				assertTrue(adaptive.contains(id), () -> "missing id after adaptive build");
			}
		}

		/**
		 * Returns the ascending, duplicate-free form of the input — the exact contents a {@link BaseBitmap} must hold.
		 */
		private static int[] sortedDistinct(int[] ids) {
			final int[] copy = Arrays.copyOf(ids, ids.length);
			Arrays.sort(copy);
			int write = 0;
			for (int read = 0; read < copy.length; read++) {
				if (write == 0 || copy[read] != copy[write - 1]) {
					copy[write++] = copy[read];
				}
			}
			return Arrays.copyOf(copy, write);
		}

		/**
		 * Contiguous ids `[1, size]` — a single (or few) densely packed containers, the realistic entity-primary-key
		 * shape that drives the writer path above the dispatch threshold.
		 */
		private static int[] dense(int size) {
			final int[] ids = new int[size];
			for (int i = 0; i < size; i++) {
				ids[i] = 1 + i;
			}
			return ids;
		}

		/**
		 * Ids strided by 700 — ≈93 per container, spread across many containers, the sparse layout the dispatch must
		 * keep on the incremental path.
		 */
		private static int[] sparse(int size) {
			final int[] ids = new int[size];
			for (int i = 0; i < size; i++) {
				ids[i] = 1 + i * 700;
			}
			return ids;
		}

		/**
		 * Deterministically permutes a copy of the input so the density probe sees non-ascending ends.
		 */
		private static int[] shuffled(int[] ids, long seed) {
			final int[] copy = Arrays.copyOf(ids, ids.length);
			final Random random = new Random(seed);
			for (int i = copy.length - 1; i > 0; i--) {
				final int j = random.nextInt(i + 1);
				final int swap = copy[i];
				copy[i] = copy[j];
				copy[j] = swap;
			}
			return copy;
		}

		/**
		 * Concatenates `containers` full-density runs of `perContainer` contiguous ids, one run per
		 * 65 536-wide container, ordered from the highest container down to the lowest. This
		 * guarantees `ids[0]` sits in a strictly higher container than `ids[ids.length - 1]` — the
		 * one input shape neither {@link #shouldSortUnsortedLargeDense()} (confined to a single
		 * container, so the ends can never disagree) nor {@link #shouldSortUnsortedLargeSparse()}
		 * (rejected by the density arithmetic regardless of end order) reaches.
		 */
		private static int[] descendingContainerOrder(int containers, int perContainer) {
			final int[] ids = new int[containers * perContainer];
			int index = 0;
			for (int container = containers - 1; container >= 0; container--) {
				final int base = container << 16;
				for (int i = 0; i < perContainer; i++) {
					ids[index++] = base + i;
				}
			}
			return ids;
		}

		@Test
		@DisplayName("should build a small input via the incremental path")
		void shouldBuildSmallInput() {
			assertConstructsEquivalently(dense(64));
		}

		@Test
		@DisplayName("should build just below the dispatch threshold via the incremental path")
		void shouldBuildJustBelowThreshold() {
			assertConstructsEquivalently(dense(4095));
		}

		@Test
		@DisplayName("should build at the exact threshold identically to the incremental path")
		void shouldBuildAtThreshold() {
			assertConstructsEquivalently(dense(4096));
		}

		@Test
		@DisplayName("should build a large dense input via the writer path, content-identical to incremental")
		void shouldBuildLargeDenseSorted() {
			assertConstructsEquivalently(dense(16384));
		}

		@Test
		@DisplayName("should build a large sparse input via the incremental path, content-identical to incremental")
		void shouldBuildLargeSparseSorted() {
			assertConstructsEquivalently(sparse(16384));
		}

		@Test
		@DisplayName("should build a multi-container input mixing bitmap and array containers, identically to incremental")
		void shouldBuildMultiContainerMixedTypes() {
			// 200 000 contiguous ids: containers 0–2 hold 65 536 ids each (bitmap), the tail holds 3 393 (array) —
			// both build paths must agree on every container's type for the bitmaps to compare equal
			assertConstructsEquivalently(dense(200_000));
		}

		@Test
		@DisplayName("should sort an unsorted large dense input regardless of build path")
		void shouldSortUnsortedLargeDense() {
			assertConstructsEquivalently(shuffled(dense(16384), 42L));
		}

		@Test
		@DisplayName("should sort an unsorted large sparse input regardless of build path")
		void shouldSortUnsortedLargeSparse() {
			assertConstructsEquivalently(shuffled(sparse(16384), 7L));
		}

		@Test
		@DisplayName("should build a multi-container dense input whose ends disagree on container order, identically to incremental")
		void shouldBuildMultiContainerDenseInputWithReversedContainerOrder() {
			// 3 containers × 5 000 contiguous ids each, emitted highest-container-first so ids[0] lands in a
			// higher container than the last id — the only shape that exercises isDense's lastContainer <
			// firstContainer guard (a sorted version would exceed the density threshold and take the writer)
			assertConstructsEquivalently(descendingContainerOrder(3, 5000));
		}
	}

	@Nested
	@DisplayName("Direct construction via RoaringBitmapBackedBitmap.fromArray")
	class FromArrayDirectConstructionTest {

		/**
		 * Returns `count` contiguous ids starting at `fromInclusive` — a densely packed run confined to
		 * a single 65 536-wide container when `count` fits within it, the shape that drives the writer
		 * path.
		 */
		private static int[] contiguousIds(int fromInclusive, int count) {
			final int[] ids = new int[count];
			for (int i = 0; i < count; i++) {
				ids[i] = fromInclusive + i;
			}
			return ids;
		}

		/**
		 * Returns `count` ids strided by `stride`, starting at 1 — spread thinly across many containers
		 * so the mean ids-per-container stays far below the writer dispatch density.
		 */
		private static int[] stridedIds(int count, int stride) {
			final int[] ids = new int[count];
			for (int i = 0; i < count; i++) {
				ids[i] = 1 + i * stride;
			}
			return ids;
		}

		@Test
		@DisplayName("should build a single-record bitmap holding exactly that record")
		void shouldBuildSingleRecordBitmap() {
			final PersistentRoaringBitmap result = RoaringBitmapBackedBitmap.fromArray(42);
			assertArrayEquals(new int[]{42}, result.toSignedArray());
			assertEquals(1, result.getCardinality());
		}

		@Test
		@DisplayName("should build a tiny scattered-id bitmap holding every id")
		void shouldBuildTinyScatteredBitmap() {
			final PersistentRoaringBitmap result = RoaringBitmapBackedBitmap.fromArray(3, 900000, 40);
			assertArrayEquals(new int[]{3, 40, 900000}, result.toSignedArray());
			assertEquals(3, result.getCardinality());
		}

		@Test
		@DisplayName("should keep a large but thinly-spread input on the incremental path")
		void shouldBuildLargeSparseInputAcrossManyContainers() {
			// 8192 ids strided by 700 span roughly 90 containers at under 100 ids each — well below
			// the writer dispatch density despite exceeding its length gate, so the container-span
			// arithmetic (not just the length check) must reject the writer path
			final int[] ids = stridedIds(8192, 700);
			final PersistentRoaringBitmap result = RoaringBitmapBackedBitmap.fromArray(ids);

			final PersistentRoaringBitmap incrementalBuilt = new PersistentRoaringBitmap();
			incrementalBuilt.add(ids);

			assertArrayEquals(incrementalBuilt.toSignedArray(), result.toSignedArray());
			assertEquals(incrementalBuilt.getCardinality(), result.getCardinality());
			assertEquals(incrementalBuilt, result);
			assertEquals(incrementalBuilt.hashCode(), result.hashCode());
		}

		@Test
		@DisplayName("should route a large dense single-container input through the writer path, equal to incremental in content and hash")
		void shouldBuildLargeDenseSingleContainerBitmap() {
			// 8192 contiguous ids all fall in container 0, crossing WRITER_DISPATCH_DENSITY within a
			// single container — the writer path's removeRunCompression normalization must make its
			// output indistinguishable from the always-correct incremental build
			final int[] ids = contiguousIds(0, 8192);
			final PersistentRoaringBitmap result = RoaringBitmapBackedBitmap.fromArray(ids);

			final PersistentRoaringBitmap incrementalBuilt = new PersistentRoaringBitmap();
			incrementalBuilt.add(ids);

			assertArrayEquals(incrementalBuilt.toSignedArray(), result.toSignedArray());
			assertEquals(incrementalBuilt.getCardinality(), result.getCardinality());
			assertEquals(incrementalBuilt, result, "writer-built bitmap must equal the incremental build");
			assertEquals(
				incrementalBuilt.hashCode(), result.hashCode(),
				"equal bitmaps must share a hash code"
			);
		}

		@Test
		@DisplayName("should round-trip negative ids in signed ascending order")
		void shouldBuildBitmapWithNegativeIds() {
			final int[] ids = {Integer.MIN_VALUE, -1000, 0, 5};
			final BaseBitmap wrapped = new BaseBitmap(RoaringBitmapBackedBitmap.fromArray(ids));
			assertArrayEquals(new int[]{Integer.MIN_VALUE, -1000, 0, 5}, wrapped.getArray());
			assertEquals(4, wrapped.size());
		}

		@Test
		@DisplayName("should build correct content from an unsorted input array")
		void shouldBuildBitmapFromUnsortedInput() {
			final PersistentRoaringBitmap result = RoaringBitmapBackedBitmap.fromArray(5, 1, 900000, 2);
			assertArrayEquals(new int[]{1, 2, 5, 900000}, result.toSignedArray());
			assertEquals(4, result.getCardinality());
		}
	}

	@Nested
	@DisplayName("Read operations")
	class ReadOperationsTest {

		/**
		 * Checks array ordering and index lookup for records supplied out of order.
		 */
		@Test
		@DisplayName("should read all data with indexOf")
		void shouldReadAllData() {
			final BaseBitmap tested = new BaseBitmap(5, 8, 10, 3);
			assertEquals(4, tested.size());
			assertArrayEquals(new int[]{3, 5, 8, 10}, tested.getArray());
			assertEquals(1, tested.indexOf(5));
			assertEquals(0, tested.indexOf(3));
			assertEquals(-1, tested.indexOf(0));
			assertEquals(-2, tested.indexOf(4));
			assertEquals(-3, tested.indexOf(6));
			assertEquals(-4, tested.indexOf(9));
			assertEquals(-5, tested.indexOf(11));
		}

		@Test
		@DisplayName("should iterate over all data")
		void shouldIterateOverAllData() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			final OfInt it = tested.iterator();
			assertArrayEquals(new int[]{3, 5, 8, 10}, toArray(it, tested.size()));
		}

		@Test
		@DisplayName("should contain existing element")
		void shouldContainExistingElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertTrue(tested.contains(3));
			assertTrue(tested.contains(5));
			assertTrue(tested.contains(8));
			assertTrue(tested.contains(10));
		}

		@Test
		@DisplayName("should not contain missing element")
		void shouldNotContainMissingElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertFalse(tested.contains(0));
			assertFalse(tested.contains(4));
			assertFalse(tested.contains(11));
		}

		@Test
		@DisplayName("should return first element")
		void shouldReturnFirstElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertEquals(3, tested.getFirst());
		}

		@Test
		@DisplayName("should return last element")
		void shouldReturnLastElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertEquals(10, tested.getLast());
		}

		@Test
		@DisplayName("should throw on getFirst when empty")
		void shouldThrowOnGetFirstWhenEmpty() {
			final BaseBitmap tested = new BaseBitmap();
			assertThrows(IndexOutOfBoundsException.class, tested::getFirst);
		}

		@Test
		@DisplayName("should throw on getLast when empty")
		void shouldThrowOnGetLastWhenEmpty() {
			final BaseBitmap tested = new BaseBitmap();
			assertThrows(IndexOutOfBoundsException.class, tested::getLast);
		}

		@Test
		@DisplayName("should return isEmpty true when empty")
		void shouldReturnIsEmptyTrueWhenEmpty() {
			final BaseBitmap tested = new BaseBitmap();
			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("should return isEmpty false when non-empty")
		void shouldReturnIsEmptyFalseWhenNonEmpty() {
			final BaseBitmap tested = new BaseBitmap(1);
			assertFalse(tested.isEmpty());
		}

		@Test
		@DisplayName("should expose PersistentRoaringBitmap")
		void shouldExposeRoaringBitmap() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			final PersistentRoaringBitmap roaringBitmap = tested.getRoaringBitmap();
			assertNotNull(roaringBitmap);
			assertEquals(3, roaringBitmap.getCardinality());
		}

		@Test
		@DisplayName("should return range")
		void shouldReturnRange() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10, 12, 15, 20, 23);
			assertArrayEquals(new int[]{8, 10, 12}, tested.getRange(2, 5));
			assertArrayEquals(new int[]{15, 20, 23}, tested.getRange(5, 8));
			assertArrayEquals(new int[]{3, 5, 8, 10, 12}, tested.getRange(0, 5));
		}

		@Test
		@DisplayName("should fail to return range when size is exceeded")
		void shouldFailReturnRangeWhenSizeIsExceeded() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10, 12, 15, 20, 23);
			assertThrows(IndexOutOfBoundsException.class, () -> tested.getRange(7, 10));
		}
	}

	@Nested
	@DisplayName("Add and remove operations")
	class AddAndRemoveOperationsTest {

		/**
		 * Checks that single-record insertions are visible through the array and iterator.
		 */
		@Test
		@DisplayName("should add and see data")
		void shouldAddAndSeeData() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.add(1);
			tested.add(15);
			assertEquals(6, tested.size());
			assertArrayEquals(new int[]{1, 3, 5, 8, 10, 15}, tested.getArray());
			assertArrayEquals(new int[]{1, 3, 5, 8, 10, 15}, toArray(tested.iterator(), tested.size()));
		}

		/**
		 * Checks that single-record removals are visible through the array and iterator.
		 */
		@Test
		@DisplayName("should remove and miss data")
		void shouldRemoveAndMissData() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.remove(3);
			tested.remove(8);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{5, 10}, tested.getArray());
			assertArrayEquals(new int[]{5, 10}, toArray(tested.iterator(), tested.size()));
		}

		@Test
		@DisplayName("should return true on add when element is new")
		void shouldReturnTrueOnAddWhenElementIsNew() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertTrue(tested.add(5));
		}

		@Test
		@DisplayName("should return false on add when element is duplicate")
		void shouldReturnFalseOnAddWhenElementIsDuplicate() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertFalse(tested.add(2));
		}

		@Test
		@DisplayName("should return true on remove when element exists")
		void shouldReturnTrueOnRemoveWhenElementExists() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertTrue(tested.remove(2));
		}

		@Test
		@DisplayName("should return false on remove when element does not exist")
		void shouldReturnFalseOnRemoveWhenElementDoesNotExist() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertFalse(tested.remove(99));
		}

		@Test
		@DisplayName("should addAll from Bitmap instance")
		void shouldAddAllFromBitmapInstance() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			final BaseBitmap other = new BaseBitmap(4, 5, 6);
			tested.addAll(other);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, tested.getArray());
		}

		@Test
		@DisplayName("should removeAll from RoaringBitmapBackedBitmap instance")
		void shouldRemoveAllFromRoaringBitmapBackedBitmap() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3, 4, 5);
			final BaseBitmap toRemove = new BaseBitmap(2, 4);
			tested.removeAll(toRemove);
			assertArrayEquals(new int[]{1, 3, 5}, tested.getArray());
		}

		@Test
		@DisplayName("should removeAll from non-PersistentRoaringBitmap Bitmap instance")
		void shouldRemoveAllFromNonRoaringBitmapInstance() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3, 4, 5);
			// ArrayBitmap is not a RoaringBitmapBackedBitmap, so the iteration path is used
			final ArrayBitmap toRemove = new ArrayBitmap(2, 4);
			tested.removeAll(toRemove);
			assertArrayEquals(new int[]{1, 3, 5}, tested.getArray());
		}
	}

	@Nested
	@DisplayName("Equals, hashCode, and toString")
	class EqualsHashCodeToStringTest {

		@Test
		@DisplayName("should be equal to identical BaseBitmap")
		void shouldBeEqualToIdenticalBaseBitmap() {
			final BaseBitmap bitmap1 = new BaseBitmap(1, 2, 3);
			final BaseBitmap bitmap2 = new BaseBitmap(1, 2, 3);
			assertEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to different BaseBitmap")
		void shouldNotBeEqualToDifferentBaseBitmap() {
			final BaseBitmap bitmap1 = new BaseBitmap(1, 2, 3);
			final BaseBitmap bitmap2 = new BaseBitmap(4, 5, 6);
			assertNotEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to null")
		@SuppressWarnings({"SimplifiableAssertion", "ObjectEqualsNull"})
		void shouldNotBeEqualToNull() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			// deliberately not `assertNotEquals`: with `null` as its first argument it calls no `equals` at all, so
			// the null check of the class under test would never run
			assertFalse(bitmap.equals(null));
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			final BaseBitmap bitmap1 = new BaseBitmap(1, 2, 3);
			final BaseBitmap bitmap2 = new BaseBitmap(1, 2, 3);
			assertEquals(bitmap1.hashCode(), bitmap2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			final String result = bitmap.toString();
			assertNotNull(result);
			assertEquals("[1, 2, 3]", result);
		}

		@Test
		@DisplayName("should produce empty toString for empty bitmap")
		void shouldProduceEmptyToStringForEmptyBitmap() {
			final BaseBitmap bitmap = new BaseBitmap();
			assertEquals("[]", bitmap.toString());
		}
	}

	/**
	 * Signed-ordering contract around `Integer.MIN_VALUE` / `Integer.MAX_VALUE`. `PersistentRoaringBitmap`
	 * internally uses unsigned 32-bit container keys, so a naive `toArray()` would place negative
	 * values after positives. `BaseBitmap.getArray()` must expose a strictly signed (arithmetic)
	 * ordering — these tests pin that contract at the extremes so any regression in sign
	 * handling (rotation of the negative tail, container-key interpretation, etc.) surfaces
	 * immediately. Required by reference-histogram boundary resolution, which relies on
	 * arithmetic ordering of integer primary keys.
	 */
	@Nested
	@DisplayName("Integer boundary values — signed ordering at MIN/MAX extremes")
	class IntegerBoundaryValuesTest {

		@Test
		@DisplayName("should store and look up Integer.MAX_VALUE without overflow")
		void shouldStoreAndLookUpIntegerMaxValue() {
			final BaseBitmap bitmap = new BaseBitmap();
			bitmap.add(Integer.MAX_VALUE);

			assertEquals(1, bitmap.size());
			assertTrue(bitmap.contains(Integer.MAX_VALUE));
			assertEquals(Integer.MAX_VALUE, bitmap.getLast());
			assertEquals(Integer.MAX_VALUE, bitmap.getFirst());
		}

		@Test
		@DisplayName("should store and look up Integer.MIN_VALUE without sign misinterpretation")
		void shouldStoreAndLookUpIntegerMinValue() {
			final BaseBitmap bitmap = new BaseBitmap();
			bitmap.add(Integer.MIN_VALUE);

			assertEquals(1, bitmap.size());
			assertTrue(bitmap.contains(Integer.MIN_VALUE));
		}

		@Test
		@DisplayName("should return a signed-sorted array spanning MIN_VALUE, negatives, zero, positives and MAX_VALUE")
		void shouldReturnSignedSortedArraySpanningFullIntegerRange() {
			final BaseBitmap bitmap = new BaseBitmap(Integer.MAX_VALUE, -18, 0, 19, Integer.MIN_VALUE);

			assertArrayEquals(
				new int[]{Integer.MIN_VALUE, -18, 0, 19, Integer.MAX_VALUE},
				bitmap.getArray(),
				"BaseBitmap must produce a signed-sorted array"
			);
		}

		@Test
		@DisplayName("should return purely-negative values in signed ascending order (most-negative first)")
		void shouldReturnPurelyNegativeValuesInSignedAscendingOrder() {
			final BaseBitmap bitmap = new BaseBitmap(-1, -10, -5);

			assertArrayEquals(new int[]{-10, -5, -1}, bitmap.getArray());
		}

		@Test
		@DisplayName("should order the -1/0 pair negative-before-positive (unsigned order would invert it)")
		void shouldOrderNegativeOneBeforeZero() {
			// In the native PersistentRoaringBitmap unsigned ordering -1 (0xFFFFFFFF) would sort AFTER 0;
			// signed ordering must place -1 before 0.
			final BaseBitmap bitmap = new BaseBitmap(-1, 0);

			assertArrayEquals(new int[]{-1, 0}, bitmap.getArray());
		}

		@Test
		@DisplayName("should preserve signed order across the unsigned MAX_VALUE→MIN_VALUE wrap point")
		void shouldPreserveSignedOrderAcrossUnsignedWrapPoint() {
			final BaseBitmap bitmap = new BaseBitmap(
				Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE
			);

			assertArrayEquals(
				new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE},
				bitmap.getArray()
			);
		}
	}

	/**
	 * Covers construction from an explicit empty array.
	 */
	@Nested
	@DisplayName("Empty int[] input — zero-length array paths")
	class EmptyVarargsTest {

		@Test
		@DisplayName("should construct an empty bitmap when given an explicit zero-length int array")
		void shouldConstructEmptyBitmapWhenGivenZeroLengthArray() {
			final BaseBitmap bitmap = new BaseBitmap(new int[0]);

			assertTrue(bitmap.isEmpty());
			assertEquals(0, bitmap.size());
			assertArrayEquals(new int[0], bitmap.getArray());
		}
	}

	/**
	 * Pins the memoized content hash. Two properties matter and they pull in opposite directions:
	 *
	 * - it must be memoized, because a `ConstantFormula` over a non-transactional bitmap keys its cache entry on
	 * this value and index memos build one such formula per query — without the memo that is an `O(size)` walk per
	 * call (issue #1458);
	 * - it must never outlive the contents it describes, because the same value is the *sole* cache discriminator
	 * for those formulas — a stale hash serves one bitmap's cached result for another's contents.
	 *
	 * Every mutator therefore gets its own invalidation test. The counting hash function is what distinguishes
	 * "answered correctly" from "answered correctly *and* recomputed".
	 */
	@Nested
	@DisplayName("Memoized content hash")
	class ContentHashTest {

		@Test
		@DisplayName("should hash the contents only once across repeated calls")
		void shouldHashContentsOnlyOnceAcrossRepeatedCalls() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3, 4, 5);
			final CountingHashFunction hashFunction = new CountingHashFunction();

			final long first = bitmap.getContentHash(hashFunction);
			final long second = bitmap.getContentHash(hashFunction);
			final long third = bitmap.getContentHash(hashFunction);

			assertEquals(hashFunction.hashIntsUncounted(new int[]{1, 2, 3, 4, 5}), first);
			assertEquals(first, second);
			assertEquals(first, third);
			assertEquals(1, hashFunction.getHashIntsCallCount(), "the contents must be walked exactly once");
		}

		@Test
		@DisplayName("should answer correctly for a hash function it has not memoized")
		void shouldAnswerCorrectlyForForeignHashFunction() {
			final BaseBitmap bitmap = new BaseBitmap(7, 9, 11);
			final CountingHashFunction functionA = new CountingHashFunction(LongHashFunction.xx3(1L));
			final CountingHashFunction functionB = new CountingHashFunction(LongHashFunction.xx3(2L));

			final long hashA = bitmap.getContentHash(functionA);
			final long hashB = bitmap.getContentHash(functionB);

			assertNotEquals(hashA, hashB, "differently seeded functions must not answer alike");
			assertEquals(functionA.hashIntsUncounted(bitmap.getArray()), hashA);
			assertEquals(functionB.hashIntsUncounted(bitmap.getArray()), hashB);
			// re-asking the first function must still be correct, even though the memo now holds the second's
			assertEquals(hashA, bitmap.getContentHash(functionA));
		}

		@Test
		@DisplayName("should invalidate the hash when add inserts a record")
		void shouldInvalidateHashWhenAddInsertsRecord() {
			final CountingHashFunction hashFunction = new CountingHashFunction();
			final BaseBitmap bitmap = seedAndHash(hashFunction);
			bitmap.add(4);
			assertHashRecomputedAndCorrect(bitmap, hashFunction);
		}

		@Test
		@DisplayName("should keep the memoized hash when add changes nothing")
		void shouldKeepMemoizedHashWhenAddChangesNothing() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			final CountingHashFunction hashFunction = new CountingHashFunction();
			final long before = bitmap.getContentHash(hashFunction);

			assertFalse(bitmap.add(2), "the record is already present");

			assertEquals(before, bitmap.getContentHash(hashFunction));
			assertEquals(1, hashFunction.getHashIntsCallCount(), "a no-op add must not discard the memo");
		}

		@Test
		@DisplayName("should invalidate the hash when addAll receives a bitmap")
		void shouldInvalidateHashWhenAddAllReceivesBitmap() {
			final CountingHashFunction hashFunction = new CountingHashFunction();
			final BaseBitmap bitmap = seedAndHash(hashFunction);
			bitmap.addAll(new BaseBitmap(8, 9));
			assertHashRecomputedAndCorrect(bitmap, hashFunction);
		}

		@Test
		@DisplayName("should invalidate the hash when remove deletes a record")
		void shouldInvalidateHashWhenRemoveDeletesRecord() {
			final CountingHashFunction hashFunction = new CountingHashFunction();
			final BaseBitmap bitmap = seedAndHash(hashFunction);
			bitmap.remove(2);
			assertHashRecomputedAndCorrect(bitmap, hashFunction);
		}

		@Test
		@DisplayName("should keep the memoized hash when remove changes nothing")
		void shouldKeepMemoizedHashWhenRemoveChangesNothing() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			final CountingHashFunction hashFunction = new CountingHashFunction();
			final long before = bitmap.getContentHash(hashFunction);

			assertFalse(bitmap.remove(42), "the record was never present");

			assertEquals(before, bitmap.getContentHash(hashFunction));
			assertEquals(1, hashFunction.getHashIntsCallCount(), "a no-op remove must not discard the memo");
		}

		@Test
		@DisplayName("should invalidate the hash when removeAll receives a bitmap")
		void shouldInvalidateHashWhenRemoveAllReceivesBitmap() {
			final CountingHashFunction hashFunction = new CountingHashFunction();
			final BaseBitmap bitmap = seedAndHash(hashFunction);
			bitmap.removeAll(new BaseBitmap(1, 2));
			assertHashRecomputedAndCorrect(bitmap, hashFunction);
		}

		/**
		 * Builds a three-record bitmap and warms its content-hash memo **with the caller's own function**, so the
		 * mutation the caller then performs is the only thing that can decide whether the next
		 * {@link BaseBitmap#getContentHash} recomputes.
		 *
		 * Warming with any other instance would defeat the whole check: the memo is keyed by function identity, so a
		 * later call carrying a different one misses it whether or not the mutator cleared anything, and the
		 * assertion would hold even against a `BaseBitmap` that never invalidates.
		 */
		@Nonnull
		private BaseBitmap seedAndHash(@Nonnull CountingHashFunction hashFunction) {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			bitmap.getContentHash(hashFunction);
			return bitmap;
		}

		/**
		 * Asserts a mutated bitmap answers for its **current** contents and got there by walking them again rather
		 * than replaying the memo {@link #seedAndHash(CountingHashFunction)} warmed with this same function — one
		 * walk for the seeding, a second forced by the mutation.
		 */
		private void assertHashRecomputedAndCorrect(
			@Nonnull BaseBitmap bitmap,
			@Nonnull CountingHashFunction hashFunction
		) {
			assertEquals(
				hashFunction.hashIntsUncounted(bitmap.getArray()), bitmap.getContentHash(hashFunction),
				"the content hash must describe the bitmap's current contents"
			);
			assertEquals(2, hashFunction.getHashIntsCallCount(), "the mutation must have forced a recomputation");
		}
	}

	/**
	 * {@link LongHashFunction} that records how many times {@link #hashInts(int[])} — the single entry point
	 * {@link Bitmap#getContentHash(LongHashFunction)} uses — was actually invoked, so a test can tell a memo hit
	 * from a recomputation. Everything else is plain delegation.
	 */
	private static final class CountingHashFunction extends LongHashFunction {
		@Serial private static final long serialVersionUID = 6002348816451219923L;
		private final LongHashFunction delegate;
		private int hashIntsCallCount;

		CountingHashFunction() {
			this(LongHashFunction.xx3());
		}

		CountingHashFunction(@Nonnull LongHashFunction delegate) {
			this.delegate = delegate;
		}

		int getHashIntsCallCount() {
			return this.hashIntsCallCount;
		}

		/**
		 * Computes the same value as {@link #hashInts(int[])} without disturbing the call count, so a test can
		 * state its expectation without paying for it.
		 */
		long hashIntsUncounted(@Nonnull int[] input) {
			return this.delegate.hashInts(input);
		}

		@Override
		public long hashInts(int[] input) {
			this.hashIntsCallCount++;
			return this.delegate.hashInts(input);
		}

		@Override
		public long hashLong(long input) {
			return this.delegate.hashLong(input);
		}

		@Override
		public long hashInt(int input) {
			return this.delegate.hashInt(input);
		}

		@Override
		public long hashShort(short input) {
			return this.delegate.hashShort(input);
		}

		@Override
		public long hashChar(char input) {
			return this.delegate.hashChar(input);
		}

		@Override
		public long hashByte(byte input) {
			return this.delegate.hashByte(input);
		}

		@Override
		public long hashVoid() {
			return this.delegate.hashVoid();
		}

		@Override
		public <T> long hash(T input, Access<T> access, long off, long len) {
			return this.delegate.hash(input, access, off, len);
		}
	}
}
