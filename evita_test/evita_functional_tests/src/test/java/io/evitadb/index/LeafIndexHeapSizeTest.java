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

import com.carrotsearch.hppc.LongObjectHashMap;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.JolHeapSize;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import static io.evitadb.index.IndexHeapSizeAssertions.AUTOBOX_CACHE_CEILING;
import static io.evitadb.index.IndexHeapSizeAssertions.assertDivergenceDoesNotGrowWithTheData;
import static io.evitadb.index.IndexHeapSizeAssertions.assertExceedsMeasuredHeapBy;
import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.measuredHeapOf;
import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures every leaf index's `getHeapSizeInBytes` against what JOL actually finds on the heap.
 *
 * # Why each test names the fields it excludes
 *
 * {@link JolHeapSize#ownedSize} walks *everything* an index reaches, while the index's own arithmetic deliberately
 * stops at objects it does not own — the comparator and normalizer handed to it or built from the attribute schema,
 * the page-stream registry that is flush bookkeeping rather than content, the attribute key the enclosing
 * `AttributeIndex` files it under. Each is named here by **field name**, read back through reflection and handed to
 * the walker as a shared root so it is subtracted by identity.
 *
 * Naming the fields rather than reading them through accessors is deliberate twice over: it keeps flush bookkeeping
 * off the production API, where a getter would invite a caller that has no business with it, and it puts each
 * index's exclusion list in one legible line of its own test. A `Class` needs no naming — the walker never descends
 * into one, because its lazily-populated reflection cache would otherwise make the figure depend on test order.
 *
 * # Why several tests measure twice
 *
 * Four of these indexes hold a lazily-built cache — a "valid now" bitmap, a sorted-supplier projection, a flattened
 * chain lookup, a referenced-key projection — that does not exist until something reads it. The reported figure
 * therefore **steps up on first read**, which is a real occupancy change rather than drift, and the tests pin it in
 * both states so that neither is mistaken for a bug later.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("Leaf index heap size")
class LeafIndexHeapSizeTest {

	/**
	 * Translates between the one entity type these tests use and its compact primary key.
	 *
	 * A global unique index takes this as a **method parameter** rather than holding it, so it never appears in a
	 * heap walk and needs no exclusion. The primary key it hands back clears {@link #AUTOBOX_CACHE_CEILING} for the
	 * reason spelled out there: a global unique index boxes it as a map key, and inside the cache that box is the
	 * JVM's rather than the index's — which shifted the reported figure by 16 bytes and nothing else.
	 */
	private static final EntityTypeClassifierResolver RESOLVER = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return AUTOBOX_CACHE_CEILING;
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return "Product";
		}
	};

	@Nested
	@DisplayName("inverted index")
	class InvertedIndexes {

		/**
		 * The normalizer and comparator handed in at construction, the flush bookkeeping, and the bucket tree's two
		 * column-factory lambdas — each excluded by the arithmetic of whichever structure holds it.
		 */
		private static final String[] INVERTED_EXCLUSIONS = {
			"normalizer", "comparator", "pageStreamRegistry",
			"buckets.valueColumnFactory", "buckets.recordColumnFactory"
		};

		/**
		 * As above for a tree that carries value ids, plus the consumer registry (structural bookkeeping, like the
		 * page-stream registry beside it) and the minter lambda.
		 *
		 * The value id **directory** is deliberately absent from this list even though it too goes uncharged: naming a
		 * root subtracts everything that root reaches, and the directory's leaf map reaches the very leaves whose
		 * columns the figure is about. It is priced separately in {@link #assertIdCarryingHeapMatches}.
		 */
		private static final String[] ID_CARRYING_EXCLUSIONS = {
			"normalizer", "comparator", "pageStreamRegistry",
			"buckets.valueColumnFactory", "buckets.recordColumnFactory",
			"valueIdConsumers", "buckets.valueIdMinter"
		};

		/** Names the registration that switches the id column on; only its presence matters, never its value. */
		private static final String ID_CONSUMER = "leaf-index-heap-size-test";

		/**
		 * The one zone offset every date-time-range fixture bound carries. `ZoneOffset.ofHours` interns it JVM-wide,
		 * so it is a shared root rather than anything an index owns.
		 */
		private static final ZoneOffset SHARED_OFFSET = ZoneOffset.ofHours(2);

		/**
		 * Builds a string-keyed inverted index, whose leaves front-code their keys.
		 *
		 * @param distinctValues how many distinct bucket values to seed
		 * @return the seeded index
		 */
		@Nonnull
		private static InvertedIndex stringKeyed(int distinctValues) {
			final InvertedIndex index = emptyStringKeyed();
			for (int i = 0; i < distinctValues; i++) {
				index.addRecord(String.format("value-%05d", i), i + 1);
			}
			return index;
		}

		/**
		 * As above, with a value id consumer registered while the index is still empty — the only moment a tree may be
		 * switched on — so every leaf carries the parallel id column and the index holds an allocator.
		 *
		 * @param distinctValues how many distinct bucket values to seed
		 * @return the seeded, id-carrying index
		 */
		@Nonnull
		private static InvertedIndex stringKeyedWithValueIds(int distinctValues) {
			final InvertedIndex index = emptyStringKeyed();
			index.attachValueIdConsumer(ID_CONSUMER);
			for (int i = 0; i < distinctValues; i++) {
				index.addRecord(String.format("value-%05d", i), i + 1);
			}
			return index;
		}

		/**
		 * Builds a date-time-range-keyed inverted index, whose leaves hold their keys as three parallel `long[]`
		 * columns rather than as boxed objects — the shape whose separator keys this nest otherwise never measures.
		 *
		 * **Every bound gets its own `LocalDate` and `LocalTime`, and that is load-bearing.** The JDK hands out one
		 * cached `LocalTime` for a whole hour and reuses a `LocalDateTime`'s time instance in anything derived from
		 * it, so a fixture built on midnights lets one `LocalTime` stand behind every bound in the index — the walk
		 * then counts it once where `EvitaDataTypes.estimateSize` counts it per bound, and the exactness below turns
		 * into a divergence that grows with the separators. The minute and second offsets are what avoid that.
		 *
		 * @param distinctValues how many distinct bucket values to seed
		 * @return the seeded index
		 */
		@Nonnull
		private static InvertedIndex dateTimeRangeKeyed(int distinctValues) {
			final AttributeIndexKey key = new AttributeIndexKey(null, "validity", null);
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(DateTimeRange.class, 0);
			final Comparator<?> comparator = FilterIndex.getComparator(key, DateTimeRange.class);
			final InvertedIndex index = new InvertedIndex(DateTimeRange.class, normalizer, comparator, 0);
			final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
			for (int i = 0; i < distinctValues; i++) {
				final LocalDateTime from = base.plusDays(i).plusMinutes(i % 59 + 1);
				final LocalDateTime to = from.plusDays(1).plusSeconds(i % 53 + 1);
				index.addRecord(
					DateTimeRange.between(from.atOffset(SHARED_OFFSET), to.atOffset(SHARED_OFFSET)), i + 1);
			}
			return index;
		}

		/**
		 * @return a fresh empty string-keyed inverted index carrying the scaffolding an attribute index hands it
		 */
		@Nonnull
		private static InvertedIndex emptyStringKeyed() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "name", null);
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
			final Comparator<?> comparator = FilterIndex.getComparator(key, String.class);
			return new InvertedIndex(String.class, normalizer, comparator, 0);
		}

		/**
		 * Asserts an id-carrying index's arithmetic against a JOL walk, aligning the two terms the sides deliberately
		 * disagree about.
		 *
		 * The **allocator** is charged by the arithmetic but cannot be left in the walk: the minter is a method
		 * reference that captures it, and the lambda has to be excluded because the arithmetic charges only its slot.
		 * Excluding the lambda subtracts its captured allocator too, so the allocator comes off both sides and is
		 * pinned on its own afterwards — which is what makes the subtraction honest rather than a fudge.
		 *
		 * The **directory** is charged by neither side, but it cannot be named as an exclusion either, for the reason
		 * on {@link #ID_CARRYING_EXCLUSIONS}. It is measured here with the leaves held out of its own walk and taken
		 * off the measurement.
		 *
		 * Everything else — the per-leaf id columns above all — is still validated byte-exact.
		 *
		 * @param index the id-carrying index to check
		 */
		private static void assertIdCarryingHeapMatches(@Nonnull InvertedIndex index) {
			final Object tree = readField(index, "buckets");
			final ValueIdAllocator allocator = (ValueIdAllocator) readField(index, "valueIdAllocator");
			// the directory is one immutable record behind one volatile field, so the whole of it is reachable from
			// that single root - but the leaves it addresses are NOT its own and are held out of its walk, exactly as
			// when it was three separate fields
			final Object valueIdDirectory = readField(tree, "valueIdDirectory");
			final LongObjectHashMap<?> leafById =
				(LongObjectHashMap<?>) readField(valueIdDirectory, "leafById");
			final Object[] directory = {valueIdDirectory};
			final long directoryBytes = JolHeapSize.ownedSize(directory, leafById.values().toArray())
				- JolHeapSize.shallowSize(directory);

			assertEquals(
				measuredHeapOf(index, ID_CARRYING_EXCLUSIONS) - directoryBytes,
				index.getHeapSizeInBytes() - allocator.getHeapSizeInBytes()
			);
			assertEquals(
				JolHeapSize.shallowSize(allocator), allocator.getHeapSizeInBytes(),
				"the allocator taken off both sides above must itself be priced exactly"
			);
		}

		@Test
		void shouldMeasureAnIdCarryingSingleLeafIndexExactly() {
			// one leaf, so the figure carries no separator-key over-report and exactness is attainable
			assertIdCarryingHeapMatches(stringKeyedWithValueIds(50));
		}

		@Test
		void shouldMeasureAnIdCarryingMultiRecordIndexExactly() {
			// every bucket holds many records, so each leaf carries the key column, the record column, the overflow
			// bitmap column AND the id column at once
			final InvertedIndex index = emptyStringKeyed();
			index.attachValueIdConsumer(ID_CONSUMER);
			for (int value = 0; value < 40; value++) {
				for (int record = 0; record < 30; record++) {
					index.addRecord("value-" + value, value * 100 + record + 1);
				}
			}

			assertIdCarryingHeapMatches(index);
		}

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final InvertedIndex index = stringKeyed(0);
			// the bucket tree memoizes its count, and an empty one boxes ZERO - the JVM's own instance. The walk
			// charges it to this index and so does rule 1 - one holder, one box, and the two figures agree
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index,
				"normalizer", "comparator", "pageStreamRegistry",
				"buckets.valueColumnFactory", "buckets.recordColumnFactory"
			);
		}

		@Test
		void shouldMeasureASingleLeafIndexExactly() {
			final InvertedIndex index = stringKeyed(50);
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "normalizer", "comparator", "pageStreamRegistry",
				"buckets.valueColumnFactory", "buckets.recordColumnFactory"
			);
		}

		@Test
		void shouldOverReportAMultiLeafIndexByOnlyItsSeparatorKeysLatinOneSaving() {
			// more than one leaf block (256) so the tree grows internal nodes and the walk has real depth
			final InvertedIndex index = stringKeyed(1_000);
			assertTrue(index.isPaged(), "the seeded index must span several leaves");
			// Every separator key promoted into an internal node is a real String this tree owns, and
			// `EvitaDataTypes.estimateSize` prices a String as UTF-16 while the JVM stores an all-Latin-1 one at a
			// byte per char. For an 11-char key that is 40 bytes charged against 32 occupied, so the arithmetic sits
			// 8 bytes above the measurement per separator - the deliberate over-report its own javadoc declares,
			// taken because detecting the encoding would mean scanning every attribute value on this path. A
			// single-leaf tree has no separators, which is why every other case here is exact
			final long measured = measuredHeapOf(index, INVERTED_EXCLUSIONS);
			final long excess = index.getHeapSizeInBytes() - measured;
			assertTrue(excess > 0, "a paged tree charges its separator keys as UTF-16");
			// eight bytes per separator, and a tree of this size has a handful - so the excess must stay a rounding
			// error against the whole figure rather than anything that could hide a real accounting mistake
			assertTrue(
				excess < measured / 100,
				"the separator over-report must stay under one percent - was " + excess + " of " + measured
			);
		}

		@Test
		void shouldNotLetTheSeparatorOverReportGrowFasterThanTheSeparators() {
			// four times the values means roughly four times the leaves, hence four times the separators - and the
			// over-report must track THAT, not the record count. A term that grew per record would show up here as
			// a ratio far above the leaf ratio
			final InvertedIndex small = stringKeyed(1_000);
			final InvertedIndex large = stringKeyed(4_000);
			final long smallExcess = small.getHeapSizeInBytes() - measuredHeapOf(small, INVERTED_EXCLUSIONS);
			final long largeExcess = large.getHeapSizeInBytes() - measuredHeapOf(large, INVERTED_EXCLUSIONS);
			assertTrue(
				largeExcess < smallExcess * 8,
				"the over-report must scale with leaves, not records - " + smallExcess + " to " + largeExcess
			);
		}

		@Test
		void shouldMeasureMultiRecordBucketsExactly() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "name", null);
			final InvertedIndex index = new InvertedIndex(
				String.class,
				FilterIndex.getNormalizer(String.class, 0),
				FilterIndex.getComparator(key, String.class),
				0
			);
			// every bucket holds many records, so each leaf allocates its overflow bitmap column
			for (int value = 0; value < 40; value++) {
				for (int record = 0; record < 30; record++) {
					index.addRecord("value-" + value, value * 100 + record + 1);
				}
			}
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "normalizer", "comparator", "pageStreamRegistry",
				"buckets.valueColumnFactory", "buckets.recordColumnFactory"
			);
		}

		@Test
		void shouldChargeTheBoxedKeysOfAnIndexThatStoresThemAsObjects() {
			// a UUID has no LongKeyCodec and is not a String, so ValueColumnFactory picks the BOXED column - the one
			// case where the key sizer is consulted at all. Without it every one of these keys would go unreported
			final AttributeIndexKey key = new AttributeIndexKey(null, "code", null);
			final InvertedIndex index = new InvertedIndex(
				UUID.class,
				FilterIndex.getNormalizer(UUID.class, 0),
				FilterIndex.getComparator(key, UUID.class),
				0
			);
			for (int i = 0; i < 100; i++) {
				index.addRecord(UUID.nameUUIDFromBytes(new byte[]{(byte) i}), i + 1);
			}
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "normalizer", "comparator", "pageStreamRegistry",
				"buckets.valueColumnFactory", "buckets.recordColumnFactory"
			);
		}

		@Test
		void shouldNotChargeTheSeparatorKeysOfABoxedIndexTwice() {
			// The multi-leaf half of the boxed-key policy, and the only fixture that exercises it: a separator key
			// in an internal node is the IDENTICAL instance the leaf below it holds, because a split promotes the
			// right leaf's first key by reference. Pricing it in both places would count one key twice per leaf
			// boundary - invisible at 100 keys, which fit in a single leaf and have no separators at all.
			final AttributeIndexKey key = new AttributeIndexKey(null, "code", null);
			final InvertedIndex index = new InvertedIndex(
				UUID.class,
				FilterIndex.getNormalizer(UUID.class, 0),
				FilterIndex.getComparator(key, UUID.class),
				0
			);
			for (int i = 0; i < 2_000; i++) {
				index.addRecord(
					UUID.nameUUIDFromBytes(Integer.toString(i).getBytes(StandardCharsets.UTF_8)),
					AUTOBOX_CACHE_CEILING + i
				);
			}
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, INVERTED_EXCLUSIONS);
		}

		@Test
		void shouldGrowWithTheNumberOfBuckets() {
			assertTrue(
				stringKeyed(1_000).getHeapSizeInBytes() > stringKeyed(50).getHeapSizeInBytes(),
				"a larger index must report a larger footprint"
			);
		}

		@Test
		void shouldKeepAOneKeyIntegralIndexWithinItsSizingBudget() {
			// The whole point of sizing a leaf's columns to their content: an integral index holding ONE value used
			// to pay for a 256-slot key column, a 256-slot record column and their headers - 3472 bytes for a single
			// long and a single int. What is left is structure only, and every one of these bytes is accounted for:
			//
			//   64  the index object                     64  the leaf node
			//   80  the bucket tree object               32  the key column object + 48 its four-slot long[]
			//   80  the tree's two transactional             24  the record column object + 32 its four-slot int[]
			//       reference holders and their           24  the tree's transactional dirty flag
			//       AtomicReferences                      16  the boxed bucket count the tree memoizes
			//
			// That sums to 464, which is what both sides report. The four-slot floor is deliberate (see
			// ColumnSizing): the reduced value trees this sizing exists for are dominated by one to four distinct
			// values, so a floor of four covers the common case in a single allocation and never reallocates. The
			// 480-byte budget therefore leaves room for one more small object without leaving room for a column that
			// has gone back to allocating its whole block.
			final AttributeIndexKey key = new AttributeIndexKey(null, "code", null);
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(Integer.class, 0);
			final Comparator<?> comparator = FilterIndex.getComparator(key, Integer.class);
			final InvertedIndex index = new InvertedIndex(Integer.class, normalizer, comparator, 0);
			index.addRecord(1_000_001, 1);

			final long measured = measuredHeapOf(index, INVERTED_EXCLUSIONS);
			assertEquals(measured, index.getHeapSizeInBytes(), "the index must price itself exactly");
			assertTrue(
				measured <= 480,
				"a one-key integral index must stay within its 480 B budget - was " + measured
			);
		}

		@Test
		void shouldKeepAOneKeyRangeIndexWithinItsSizingBudget() {
			// The same budget for the shape the range column serves, which is the integral one plus its extra bound
			// arrays. Every byte, against the 464 of the integral gate above:
			//
			//   64  the index object                     64  the leaf node
			//   80  the bucket tree object               40  the key column object
			//   80  the tree's two transactional        144  its THREE four-slot long[] arrays, 48 each
			//       reference holders and their          24  the record column object + 32 its four-slot int[]
			//       AtomicReferences                     24  the tree's transactional dirty flag
			//                                            16  the boxed bucket count the tree memoizes
			//
			// That sums to 568 - the integral gate's 464 plus 104 for the wider key column (a 40-byte object holding
			// three arrays rather than a 32-byte one holding a single array). A `DateTimeRange` is the expensive
			// shape: the five numeric kinds park `meta` on the shared empty array and pay 48 bytes less. The
			// 584-byte budget leaves room for one more small object without leaving room for a column that has gone
			// back to allocating its whole block - or for a fourth array
			final AttributeIndexKey key = new AttributeIndexKey(null, "validity", null);
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(DateTimeRange.class, 0);
			final Comparator<?> comparator = FilterIndex.getComparator(key, DateTimeRange.class);
			final InvertedIndex index = new InvertedIndex(DateTimeRange.class, normalizer, comparator, 0);
			index.addRecord(
				DateTimeRange.between(
					LocalDateTime.of(2024, 1, 1, 0, 0).atOffset(ZoneOffset.ofHours(2)),
					LocalDateTime.of(2024, 2, 1, 0, 0).atOffset(ZoneOffset.ofHours(2))
				),
				1
			);

			final long measured = measuredHeapOf(index, INVERTED_EXCLUSIONS);
			assertEquals(measured, index.getHeapSizeInBytes(), "the index must price itself exactly");
			assertTrue(
				measured <= 584,
				"a one-key range index must stay within its 584 B budget - was " + measured
			);
		}

		@Test
		void shouldAccountForTheSeparatorKeysOfAMultiLeafRangeIndex() {
			// A range tree is the one shape that flips the tree's `separatorKeysAreOwned` verdict from false to
			// true - the check is "the leaf's key column is not the boxed one" - so the moment a range index grows
			// internal nodes every separator starts being charged through `EvitaDataTypes.estimateSize`. The gate
			// above is a SINGLE leaf and has no separators at all, and the two multi-leaf cases in this nest key
			// on a String and on a UUID, so this shape is measured nowhere else.
			//
			// A range separator is a freshly minted object - `keyAt` rebuilds it and cannot alias a leaf key the
			// way a boxed column's promoted-by-reference separator does - so it is really retained and really
			// walked, and the two sides agree byte for byte once the one thing neither owns is taken off the walk:
			// the zone offset behind every bound. `ZoneOffset.ofHours` interns it JVM-wide and `estimateSize`
			// deliberately charges only the reference slot pointing at it, so it is named here as the shared root
			// it is. Measured, it is a fixed 72 bytes at any index size - which is what makes exactness attainable
			// here rather than the bounded divergence the front-coded String case has to settle for.
			final Object[] internedOffset = {SHARED_OFFSET};
			final InvertedIndex index = dateTimeRangeKeyed(1_000);
			assertTrue(index.isPaged(), "the seeded index must span several leaves");
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, internedOffset, INVERTED_EXCLUSIONS);

			// four times the values is four times the leaves and four times the separators: a separator term that
			// had gone missing would surface as a divergence growing with them, and this stays exact
			final InvertedIndex larger = dateTimeRangeKeyed(4_000);
			assertMatchesMeasuredHeap(larger.getHeapSizeInBytes(), larger, internedOffset, INVERTED_EXCLUSIONS);
			assertTrue(
				larger.getHeapSizeInBytes() > index.getHeapSizeInBytes(),
				"a larger paged range index must price above a smaller one"
			);
		}
	}

	@Nested
	@DisplayName("range index")
	class RangeIndexes {

		/**
		 * Builds a range index holding `count` distinct validity ranges.
		 *
		 * @param count how many ranges to add
		 * @return the seeded index
		 */
		@Nonnull
		private static RangeIndex seeded(int count) {
			final RangeIndex index = new RangeIndex();
			for (int i = 0; i < count; i++) {
				index.addRecord(i * 10L, i * 10L + 5L, i + 1);
			}
			return index;
		}

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final RangeIndex index = new RangeIndex();
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "pageStreamRegistry", "ranges.transactionalLayerWrapper"
			);
		}

		@Test
		void shouldMeasureASeededIndexExactly() {
			final RangeIndex index = seeded(500);
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "pageStreamRegistry", "ranges.transactionalLayerWrapper"
			);
		}

		@Test
		void shouldStepUpOnceTheValidNowCacheIsBuiltAndStayExactBothTimes() {
			final RangeIndex index = seeded(200);
			final long cold = index.getHeapSizeInBytes();
			assertMatchesMeasuredHeap(cold, index, "pageStreamRegistry", "ranges.transactionalLayerWrapper");

			// build the memoized "valid at now" result the way a real query would
			index.getRecordsValidNowFormula(1_000L);

			final long warm = index.getHeapSizeInBytes();
			assertTrue(warm > cold, "the memoized now-cache must show up as additional occupancy");
			assertMatchesMeasuredHeap(warm, index, "pageStreamRegistry", "ranges.transactionalLayerWrapper");
		}
	}

	@Nested
	@DisplayName("sort index")
	class SortIndexes {

		/**
		 * Everything an owner sort index reaches but does not own: the attribute key it was filed under, the
		 * scaffolding comparator and normalizer built from the attribute type, and — reached through the inverted
		 * index it owns — that tree's own scaffolding and flush bookkeeping.
		 */
		private static final String[] EXCLUDED = {
			"attributeIndexKey", "comparator", "normalizer",
			"ownedTree.normalizer", "ownedTree.comparator", "ownedTree.pageStreamRegistry",
			"ownedTree.buckets.valueColumnFactory", "ownedTree.buckets.recordColumnFactory",
			// a ComparatorSource points at one Class and two enum constants; the walker prunes the Class by path
			// but charges the enums, which belong to the JVM rather than to this index
			"comparatorBase.0.orderDirection", "comparatorBase.0.orderBehaviour"
		};

		@Test
		void shouldOverReportAnEmptyOwnerByTheOneZeroBoxItsThreeCountersShare() {
			final OwnerSortIndex index = ownerSortIndex(0);
			// an empty index has three structures whose size counter boxes ZERO - the owned tree and the two inner
			// trees of the sorted-records facade - and the JVM hands all three the SAME cached Integer. Rule 1
			// charges a box to each holder regardless, because whether one is shared moves with -XX:AutoBoxCacheMax
			// and must not decide what a reading says; a walk dedupes by identity and sees the single instance once.
			// Three holders, one instance, so the gap is two boxes - and it is fixed, not a term that grows: every
			// seeded fixture here starts above the cache ceiling, which is why only empty ones diverge at all
			assertExceedsMeasuredHeapBy(
				index.getHeapSizeInBytes(), 2L * VMLayout.current().sizeOfObject(Integer.BYTES), index, EXCLUDED
			);
		}

		@Test
		void shouldMeasureASeededOwnerExactly() {
			final OwnerSortIndex index = ownerSortIndex(200);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, EXCLUDED);
		}

		@Test
		void shouldStepUpOnceTheSuppliersAreMaterializedAndStayExactBothTimes() {
			final OwnerSortIndex index = ownerSortIndex(200);
			final long cold = index.getHeapSizeInBytes();
			assertMatchesMeasuredHeap(cold, index, EXCLUDED);

			// materialize the committed-snapshot supplier arrays the way a sorted query would
			index.getAscendingOrderRecordsSupplier();

			final long warm = index.getHeapSizeInBytes();
			assertTrue(warm >= cold, "materializing a read projection must never shrink the reported footprint");
			assertMatchesMeasuredHeap(warm, index, EXCLUDED);
		}

		@Test
		void shouldGrowWithTheRecordCount() {
			assertTrue(
				ownerSortIndex(500).getHeapSizeInBytes() > ownerSortIndex(50).getHeapSizeInBytes(),
				"a larger index must report a larger footprint"
			);
		}

		/**
		 * Builds an owner sort index over `records` string values.
		 *
		 * @param records how many records to seed
		 * @return the seeded index
		 */
		@Nonnull
		private static OwnerSortIndex ownerSortIndex(int records) {
			final AttributeIndexKey key = new AttributeIndexKey(null, "priority", null);
			final OwnerSortIndex index = new OwnerSortIndex(String.class, key);
			// primary keys start above the autobox cache: inside it two structures boxing the same count share one
			// JVM-cached Integer, which an identity walk counts once while the arithmetic charges it per holder
			for (int i = 0; i < records; i++) {
				index.addRecord(String.format("value-%05d", i), AUTOBOX_CACHE_CEILING + i);
			}
			return index;
		}
	}

	@Nested
	@DisplayName("chain index")
	class ChainIndexes {

		/**
		 * Everything a chain index reaches but does not charge, including the wrapper lambda of the only one of its
		 * three maps that carries one and the enum constant behind every chain descriptor.
		 */
		private static final String[] EXCLUDED = {
			"attributeIndexKey", "pageStreamRegistry", "successorsByPredecessor.transactionalLayerWrapper"
		};

		@Test
		void shouldOverReportAnEmptyIndexByTheOneZeroBoxItsTwoLookupsShare() {
			final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "order", null));
			// The element array is two structures - a position tree and a value index - and each memoizes its own
			// element count as a boxed `Integer`. Both counts are zero here, and `Integer.valueOf(0)` comes from the
			// JVM's own cache, so the heap holds ONE box where the arithmetic charges two. That is rule 1 working as
			// intended: a figure must not change because someone moved `-XX:AutoBoxCacheMax`. Every other fixture in
			// this class seeds above the cache to sidestep it; an empty structure cannot, since its size really is 0.
			assertExceedsMeasuredHeapBy(
				index.getHeapSizeInBytes(), VMLayout.current().sizeOfObject(Integer.BYTES), index, EXCLUDED
			);
		}

		@Test
		void shouldUnderReportASeededChainByExactlyItsPreSizedTableAndSharedEnum() {
			final ChainIndex index = seededChain(300);
			// Two known, opposite-signed departures from the measurement, both deliberate:
			//
			// - `chains` is built by `CollectionUtils.createHashMap(32)` and holds ONE head, so it really owns a
			//   64-slot table while `tableCapacityFor(1)` can only infer 16 from the entry count. That is the one
			//   case MapHeapSize's javadoc names as unfixable from outside: a map asked for more room than it uses
			//   leaves no trace of having been.
			// - every `ChainDescriptor` points at an `ElementState` constant, which belongs to the JVM for the life
			//   of its class loader and is charged to nobody - but the walker prunes only `Class` by path, so it
			//   still counts the constant, its name and that name's bytes.
			final VMLayout layout = VMLayout.current();
			final long preSizedTableShortfall =
				layout.sizeOfArray(64, layout.referenceSize()) - layout.sizeOfArray(16, layout.referenceSize());
			final long measured = measuredHeapOf(index, EXCLUDED);
			final long shortfall = measured - index.getHeapSizeInBytes();
			assertTrue(
				shortfall > preSizedTableShortfall,
				"the shortfall must cover the pre-sized table (" + preSizedTableShortfall + ") - was " + shortfall
			);
			// and nothing else of consequence: the enum constant with its name is the only other term, so the
			// remainder stays small - and `shouldNotLetTheShortfallGrowWithTheChain` pins that it does not move
			assertTrue(
				shortfall - preSizedTableShortfall < 256,
				"nothing beyond the pre-sized table and one enum constant may be missing - was " +
					(shortfall - preSizedTableShortfall)
			);
		}

		@Test
		void shouldNotLetTheShortfallGrowWithTheChain() {
			// the two departures above are fixed costs - one table, one enum constant. A shortfall that grew with
			// the chain would mean a per-element term going uncharged, which is the failure that actually matters
			//
			// BOTH lengths clear the JVM's boxed-Integer cache, and that is load-bearing rather than incidental: the
			// element array memoizes its size in two places, so a chain of 100 shares one cached box between them
			// while a chain of 1000 allocates two. Comparing those two fixtures would read the cache boundary as a
			// growing shortfall and point the finger at the accounting
			final ChainIndex small = seededChain(200);
			final ChainIndex large = seededChain(1_000);
			assertEquals(
				measuredHeapOf(small, EXCLUDED) - small.getHeapSizeInBytes(),
				measuredHeapOf(large, EXCLUDED) - large.getHeapSizeInBytes(),
				"the shortfall must stay constant as the chain grows"
			);
		}

		@Test
		void shouldGrowWithTheChainLength() {
			assertTrue(
				seededChain(500).getHeapSizeInBytes() > seededChain(50).getHeapSizeInBytes(),
				"a longer chain must report a larger footprint"
			);
		}

		/**
		 * Seeds one head-anchored chain of `length` elements, each pointing at its predecessor.
		 *
		 * @param length how many elements the chain should hold
		 * @return the seeded index
		 */
		@Nonnull
		private static ChainIndex seededChain(int length) {
			final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "order", null));
			// above the autobox cache, for the reason spelled out on AUTOBOX_CACHE_CEILING: three maps here key on
			// the same primary keys, and inside the cache they would share one Integer per key
			index.upsertPredecessor(Predecessor.HEAD, AUTOBOX_CACHE_CEILING);
			for (int i = 1; i < length; i++) {
				index.upsertPredecessor(
					new Predecessor(AUTOBOX_CACHE_CEILING + i - 1), AUTOBOX_CACHE_CEILING + i
				);
			}
			return index;
		}
	}

	@Nested
	@DisplayName("unique indexes")
	class UniqueIndexes {

		/**
		 * Everything a global unique index reaches but does not charge — the catalog key it was filed under, the
		 * scope enum, the scaffolding comparator, the flush bookkeeping and two lambdas.
		 */
		private static final String[] GLOBAL_EXCLUSIONS = {
			"attributeKey", "comparator", "pageStreamRegistry", "scope",
			"tree.valueColumnFactory", "tree.recordColumnFactory",
			"entitiesPerType.transactionalLayerWrapper"
		};

		/**
		 * Everything an owner unique index reaches but does not charge — the key it was filed under, the collection
		 * name, the scaffolding comparator, the flush bookkeeping and the tree's two column-factory lambdas.
		 */
		private static final String[] OWNER_EXCLUSIONS = {
			"attributeIndexKey", "entityType", "comparator", "pageStreamRegistry",
			"tree.valueColumnFactory", "tree.recordColumnFactory"
		};

		@Test
		void shouldMeasureAnEmptyOwnerExactly() {
			final OwnerUniqueIndex index = ownerUniqueIndex(0);
			// its value tree memoizes a count of ZERO, which resolves to the JVM's cached box - one holder, so the
			// walk charges the same single box the arithmetic does
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index,
				"attributeIndexKey", "entityType", "comparator", "pageStreamRegistry",
				"tree.valueColumnFactory", "tree.recordColumnFactory"
			);
		}

		@Test
		void shouldMeasureASeededOwnerExactly() {
			final OwnerUniqueIndex index = ownerUniqueIndex(200);
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index,
				"attributeIndexKey", "entityType", "comparator", "pageStreamRegistry",
				"tree.valueColumnFactory", "tree.recordColumnFactory"
			);
		}

		@Test
		void shouldNotGrowAtAllWhenTheRecordIdsFormulaIsRequested() {
			final OwnerUniqueIndex index = ownerUniqueIndex(200);
			final long cold = index.getHeapSizeInBytes();
			assertMatchesMeasuredHeap(cold, index, OWNER_EXCLUSIONS);

			index.getRecordIdsFormula();

			// This index memoizes NOTHING for a formula request: `getRecordIdsFormula` wraps the record set it
			// already holds in a fresh ConstantFormula that dies with the query it served. Answering a query must
			// therefore leave the footprint untouched - and the measurement exact, because there is no retained
			// scaffolding to price at an upper bound.
			//
			// This is the accounting face of the leak fixed in #1458: a formula node carries the execution context
			// of the first query to initialize it, so an index that kept one pinned that query's session and its
			// whole catalog generation. A step up here would mean a memo came back.
			final long warm = index.getHeapSizeInBytes();
			assertEquals(cold, warm, "asking for the record-ids formula must not change the footprint");
			assertMatchesMeasuredHeap(warm, index, OWNER_EXCLUSIONS);

			// and it must hold however large the index grows - both fixtures stay inside one leaf block, so neither
			// carries the separate separator-key over-report
			final OwnerUniqueIndex larger = ownerUniqueIndex(250);
			larger.getRecordIdsFormula();
			assertDivergenceDoesNotGrowWithTheData(
				warm, index, larger.getHeapSizeInBytes(), larger, OWNER_EXCLUSIONS
			);
		}

		@Test
		void shouldNotLetAGlobalIndexDivergeWithItsSize() {
			// four times the unique values: the only gaps a global unique index has are fixed ones plus the
			// separator keys of its value tree, so a gap that tracked the ENTRY count would mean a real per-value
			// term going uncharged
			final GlobalUniqueIndex small = seededGlobal(200);
			final GlobalUniqueIndex large = seededGlobal(800);
			final long smallGap = small.getHeapSizeInBytes() - measuredHeapOf(small, GLOBAL_EXCLUSIONS);
			final long largeGap = large.getHeapSizeInBytes() - measuredHeapOf(large, GLOBAL_EXCLUSIONS);
			assertTrue(
				largeGap < smallGap + 8L * 16,
				"the gap must track leaves, not values - " + smallGap + " to " + largeGap
			);
		}

		/**
		 * Builds a global unique index over `records` string URLs.
		 *
		 * @param records how many unique values to seed
		 * @return the seeded index
		 */
		@Nonnull
		private static GlobalUniqueIndex seededGlobal(int records) {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("url"), String.class
			);
			for (int i = 0; i < records; i++) {
				index.registerUniqueKey(
					String.format("url-%05d", i), "Product", null, AUTOBOX_CACHE_CEILING + i, RESOLVER
				);
			}
			return index;
		}

		@Test
		void shouldMeasureAnEmptyGlobalIndexExactly() {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("url"), String.class
			);
			// the value tree memoizes its bucket count, and an empty one boxes ZERO - the JVM's own instance. One
			// structure holds it, so the walk charges the same one box the arithmetic does
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, GLOBAL_EXCLUSIONS);
		}

		@Test
		void shouldOverReportALocalizedGlobalIndexByOneBoxPerLocaleAndNoMore() {
			// The locale maps are the point here. A `Locale` comes from the JVM's own per-language cache and must be
			// subtracted from the walk; the boxed locale ids are this index's own and must not be. But the index
			// stores each id ONCE and files it in both directions - the same `Integer` instance is a value in
			// `localeToIdIndex` and a key in `idToLocaleIndex` (verified by identity) - so a walk counts one box per
			// locale where the arithmetic, which charges per holder, counts two.
			//
			// That is rule 1 again, and the right assertion is its exact magnitude: one box per locale, and one only.
			// Ids run from 1 upward and cannot be seeded past the JVM's cache the way every other fixture here is,
			// because the index allocates them itself.
			final VMLayout layout = VMLayout.current();
			final Locale[] locales = {Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.ITALIAN};
			// one instance for both sides: the exclusion set is matched by identity, so sizing one index and walking
			// a second one built the same way compares two unrelated object graphs
			final GlobalUniqueIndex index = localizedGlobal(locales, 200);
			assertExceedsMeasuredHeapBy(
				index.getHeapSizeInBytes(),
				locales.length * layout.sizeOfObject(Integer.BYTES),
				index,
				locales,
				GLOBAL_EXCLUSIONS
			);

			// and it tracks the LOCALE count: half the locales, half the gap. Both fixtures stay inside one leaf
			// block, so neither carries the separator-key over-report - that dimension belongs to
			// `shouldNotLetAGlobalIndexDivergeWithItsSize`, which varies the values and holds the locales out of it
			final Locale[] fewerLocales = {Locale.ENGLISH, Locale.GERMAN};
			final GlobalUniqueIndex fewer = localizedGlobal(fewerLocales, 200);
			assertEquals(
				fewerLocales.length * layout.sizeOfObject(Integer.BYTES),
				fewer.getHeapSizeInBytes() - measuredHeapOf(fewer, fewerLocales, GLOBAL_EXCLUSIONS),
				"halving the locales must halve the gap"
			);
		}

		/**
		 * Builds a global unique index whose values are spread evenly across `locales`.
		 *
		 * @param locales the locales to file the values under
		 * @param records how many unique values to seed
		 * @return the seeded index
		 */
		@Nonnull
		private static GlobalUniqueIndex localizedGlobal(@Nonnull Locale[] locales, int records) {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("url", Locale.ENGLISH), String.class
			);
			for (int i = 0; i < records; i++) {
				index.registerUniqueKey(
					String.format("url-%05d", i), "Product", locales[i % locales.length],
					AUTOBOX_CACHE_CEILING + i, RESOLVER
				);
			}
			return index;
		}

		/**
		 * Builds an owner unique index over `records` string values.
		 *
		 * @param records how many unique keys to seed
		 * @return the seeded index
		 */
		@Nonnull
		private static OwnerUniqueIndex ownerUniqueIndex(int records) {
			final OwnerUniqueIndex index = new OwnerUniqueIndex(
				"Product", new AttributeIndexKey(null, "code", null), String.class
			);
			for (int i = 0; i < records; i++) {
				index.registerUniqueKey(String.format("code-%05d", i), i + 1);
			}
			return index;
		}
	}

	@Nested
	@DisplayName("reference type cardinality index")
	class CardinalityIndexes {

		/**
		 * The flush bookkeeping, the two column-factory lambdas of the cardinality tree, and the wrapper lambda of
		 * the referenced-key map — each excluded by the arithmetic of whichever structure holds it.
		 */
		private static final String[] CARDINALITY_EXCLUSIONS = {
			"pageStreamRegistry",
			"cardinalities.valueColumnFactory", "cardinalities.recordColumnFactory", "cardinalities.comparator",
			"referencedPrimaryKeysIndex.transactionalLayerWrapper"
		};

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final ReferenceTypeCardinalityIndex index = new ReferenceTypeCardinalityIndex();
			// as with every empty structure here: the cardinality tree memoizes a bucket count of ZERO and the JVM
			// hands back its own cached box, charged once by the arithmetic and once by the walk
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, CARDINALITY_EXCLUSIONS);
		}

		@Test
		void shouldNotLetASeededIndexDivergeWithItsSize() {
			// the cardinality tree keys on composed longs and needs no separator strings, so the only gaps left are
			// fixed ones - the shared natural-order comparator and the JVM's cached boxes. Four times the entries
			// must therefore leave the gap untouched
			final ReferenceTypeCardinalityIndex small = seeded(10, 25);
			final ReferenceTypeCardinalityIndex large = seeded(40, 25);
			assertDivergenceDoesNotGrowWithTheData(
				small.getHeapSizeInBytes(), small, large.getHeapSizeInBytes(), large, CARDINALITY_EXCLUSIONS
			);
		}

		@Test
		void shouldStepUpOnceTheReferencedKeyProjectionIsBuiltAndAccountForItExactly() {
			final ReferenceTypeCardinalityIndex index = seeded(10, 20);
			final long cold = index.getHeapSizeInBytes();
			final long coldGap = cold - measuredHeapOf(index, CARDINALITY_EXCLUSIONS);

			index.getAllTrackedReferencedEntityPrimaryKeysAsBitmap();

			final long warm = index.getHeapSizeInBytes();
			assertTrue(warm > cold, "the memoized referenced-key projection must show up as additional occupancy");
			// The projection itself is charged for exactly what it occupies - an arithmetic that mispriced the
			// roaring bitmap would move this gap by the bitmap's size. What it does move by is one 16-byte object,
			// every time and regardless of how much the index holds: building the projection iterates the
			// referenced-key map's `keySet()`, and a `HashMap` caches that view on first call. `MapHeapSize` walks
			// with `forEach` precisely so that *measuring* never allocates one, and it cannot see a view somebody
			// else created either - the field is in `java.util`, which `java.base` does not open, so no exclusion by
			// field path reaches it. The cost is one fixed object per map that has ever been iterated
			final VMLayout layout = VMLayout.current();
			assertEquals(
				coldGap - layout.sizeOfObject(layout.referenceSize()),
				warm - measuredHeapOf(index, CARDINALITY_EXCLUSIONS),
				"building the projection may cost exactly one cached collection view, and nothing else"
			);
		}

		/**
		 * Seeds a cardinality index with a full cross product of index primary keys and referenced entities.
		 *
		 * @param indexes    how many owning index primary keys
		 * @param referenced how many referenced entities each of them points at
		 * @return the seeded index
		 */
		@Nonnull
		private static ReferenceTypeCardinalityIndex seeded(int indexes, int referenced) {
			final ReferenceTypeCardinalityIndex index = new ReferenceTypeCardinalityIndex();
			// both key spaces start above the autobox cache: the referenced keys are boxed into the map, and
			// inside the cache they would be the JVM's instances rather than this index's
			for (int indexPk = 0; indexPk < indexes; indexPk++) {
				for (int referencedPk = 0; referencedPk < referenced; referencedPk++) {
					index.addRecord(AUTOBOX_CACHE_CEILING + indexPk, AUTOBOX_CACHE_CEILING + referencedPk);
				}
			}
			return index;
		}
	}

	@Nested
	@DisplayName("substrate the leaf indexes are built from")
	class Substrate {

		@Test
		void shouldMeasureATransactionalBooleanAsAConstant() {
			final TransactionalBoolean flag = new TransactionalBoolean();
			assertEquals(JolHeapSize.ownedSize(flag), flag.getHeapSizeInBytes());
			// a flag that has been set holds no more than one that has not
			flag.setToTrue();
			assertEquals(JolHeapSize.ownedSize(flag), flag.getHeapSizeInBytes());
		}

		@Test
		void shouldMeasureATransactionalIntArrayExactly() {
			final TransactionalIntArray array = new TransactionalIntArray();
			for (int i = 0; i < 100; i++) {
				array.add(i);
			}
			assertEquals(JolHeapSize.ownedSize(array), array.getHeapSizeInBytes());
		}

		@Test
		void shouldNotChargeTheSharedEmptyArraySingleton() {
			// thousands of childless hierarchy nodes point at the ONE shared empty array - charging it would bill the
			// same allocation to each of them
			final TransactionalIntArray empty = new TransactionalIntArray();
			assertEquals(
				JolHeapSize.shallowSize(empty),
				empty.getHeapSizeInBytes(),
				"an empty array must cost its own object and nothing else"
			);
		}

		@Test
		void shouldChargeAnArrayThatShrankToEmpty() {
			// an array emptied by removal is its OWN allocation, not the shared constant - the identity test is what
			// keeps these two cases apart, and an emptiness test would wrongly zero this one
			final TransactionalIntArray array = new TransactionalIntArray();
			array.add(1);
			array.remove(1);
			assertEquals(JolHeapSize.ownedSize(array), array.getHeapSizeInBytes());
		}
	}
}
