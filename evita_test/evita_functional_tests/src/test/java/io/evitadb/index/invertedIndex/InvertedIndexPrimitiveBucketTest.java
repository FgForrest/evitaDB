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

package io.evitadb.index.invertedIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier;
import io.evitadb.store.index.serializer.InvertedIndexSerializer;
import io.evitadb.store.index.serializer.TransactionalIntegerBitmapSerializer;
import io.evitadb.store.index.serializer.ValueToRecordBitmapSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the single-record {@link ValueToRecordPrimitive} optimization of {@link InvertedIndex}: the per-bucket
 * promotion / demotion mechanics, the representation-independent equality contract, the persist / reload boundary,
 * transactional MVCC isolation of the immutable primitive, and the formula-cache identity / staleness behaviour of
 * {@link HistogramBitmapSupplier}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("InvertedIndex single-record primitive bucket")
class InvertedIndexPrimitiveBucketTest {

	@Nonnull
	private static InvertedIndex emptyIndex() {
		return new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());
	}

	@Nested
	@DisplayName("Representation (promotion / no demotion)")
	class RepresentationTest {

		@Test
		@DisplayName("A freshly created single-record bucket is stored in the compact primitive form")
		void shouldStoreSingleRecordAsPrimitive() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);

			assertTrue(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(5).getArray());
			assertEquals(1, index.getLength());
		}

		@Test
		@DisplayName("Adding a second distinct record promotes the bucket to the bitmap form")
		void shouldPromoteToBitmapOnSecondDistinctRecord() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(5, 20);

			assertFalse(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1, 20}, index.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("Re-adding the same record keeps the compact primitive form")
		void shouldStayPrimitiveWhenAddingSameRecordAgain() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(5, 1);

			assertTrue(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("Vararg add of a single id creates a primitive; of several ids creates a bitmap")
		void shouldChooseRepresentationByVarargCardinality() {
			final InvertedIndex single = emptyIndex();
			single.addRecord(5, new int[]{7});
			assertTrue(single.isPrimitiveBucket(5));

			final InvertedIndex multi = emptyIndex();
			multi.addRecord(5, 7, 8);
			assertFalse(multi.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{7, 8}, multi.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("Removing the only record of a primitive bucket deletes the bucket entirely")
		void shouldDeleteBucketWhenRemovingOnlyRecord() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.removeRecord(5, 1);

			assertFalse(index.contains(5));
			assertEquals(0, index.getBucketCount());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("Removing a non-held record from a primitive bucket is a silent no-op")
		void shouldNoOpWhenRemovingNonHeldRecordFromPrimitive() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.removeRecord(5, 999);

			assertTrue(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("A bitmap bucket reduced to a single record is NOT demoted back to a primitive")
		void shouldNotDemoteBitmapToPrimitive() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(5, 20);
			index.removeRecord(5, 20);

			assertFalse(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("Removing with an empty record-id vararg is rejected")
		void shouldThrowWhenRemovingEmptyRecordIds() {
			final InvertedIndex index = emptyIndex();

			assertThrows(EvitaInvalidUsageException.class, () -> index.removeRecord(5));
		}

		@Test
		@DisplayName("Adding the held id alongside a new id promotes and dedupes to the bitmap form")
		void shouldPromotePrimitiveWhenVarargContainsHeldIdPlusNew() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(5, 1, 20);

			assertFalse(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1, 20}, index.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("Removing several ids of which one matches deletes the primitive bucket")
		void shouldDeletePrimitiveWhenRemovingMultipleIdsOneMatching() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.removeRecord(5, 99, 1);

			assertFalse(index.contains(5));
			assertEquals(0, index.getBucketCount());
		}
	}

	@Nested
	@DisplayName("Representation-independent equality")
	class EqualityTest {

		@Test
		@DisplayName("recordSetEquals holds between a primitive {5} and a bitmap {5}")
		void shouldCompareRecordSetsAcrossRepresentations() {
			final ValueToRecord primitive = new ValueToRecordPrimitive(5, 1);
			final ValueToRecord bitmap = new ValueToRecordBitmap(5, 1);

			assertTrue(primitive.recordSetEquals(bitmap));
			assertTrue(bitmap.recordSetEquals(primitive));
			// the canonical content hash must agree across representations
			assertEquals(bitmap.recordSetHashCode(), primitive.recordSetHashCode());
		}

		@Test
		@DisplayName("recordSetEquals is false when contents differ regardless of representation")
		void shouldRejectDifferentRecordSets() {
			final ValueToRecord primitive = new ValueToRecordPrimitive(5, 1);
			final ValueToRecord otherPrimitive = new ValueToRecordPrimitive(5, 2);
			final ValueToRecord bitmap = new ValueToRecordBitmap(5, 1, 2);

			assertFalse(primitive.recordSetEquals(otherPrimitive));
			assertFalse(primitive.recordSetEquals(bitmap));
			assertFalse(bitmap.recordSetEquals(primitive));
		}

		@Test
		@DisplayName("Two indexes with identical contents are equal whatever representation each bucket took")
		void shouldEqualAcrossRepresentations() {
			// built via add -> single-record buckets are primitives
			final InvertedIndex viaAdd = emptyIndex();
			viaAdd.addRecord(5, 1);
			viaAdd.addRecord(10, 2);

			// built via the reconstruction constructor from bitmap buckets
			final InvertedIndex viaBitmaps = new InvertedIndex(
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(5, 1),
					new ValueToRecordBitmap(10, 2)
				},
				FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder()
			);

			assertEquals(viaAdd, viaBitmaps);
			assertEquals(viaAdd.hashCode(), viaBitmaps.hashCode());
		}

		@Test
		@DisplayName("recordSetEquals against a null bucket is false for both representations")
		void shouldReturnFalseFromRecordSetEqualsForNull() {
			assertFalse(new ValueToRecordPrimitive(5, 1).recordSetEquals(null));
			assertFalse(new ValueToRecordBitmap(5, 1).recordSetEquals(null));
		}

		@Test
		@DisplayName("An empty bitmap bucket differs from a single-record bucket but matches another empty one")
		void shouldCompareRecordSetsAgainstEmptyBitmapBucket() {
			final ValueToRecord empty = new ValueToRecordBitmap(5);
			assertEquals(0, empty.size());

			// size mismatch: the empty bucket holds no ids, the primitive holds one
			assertFalse(empty.recordSetEquals(new ValueToRecordPrimitive(5, 1)));

			// two empty buckets share the canonical empty record set
			final ValueToRecord otherEmpty = new ValueToRecordBitmap(5);
			assertTrue(empty.recordSetEquals(otherEmpty));
			assertEquals(empty.recordSetHashCode(), otherEmpty.recordSetHashCode());
		}
	}

	@Nested
	@DisplayName("Persist / reload boundary")
	@Tag(SERIALIZATION)
	class SerializationTest {

		@Test
		@DisplayName("getValueToRecordBitmap materializes primitive buckets back to bitmaps")
		void shouldMaterializePrimitivesToBitmapsAtBoundary() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(10, 2);
			index.addRecord(10, 3);

			final ValueToRecordBitmap[] boundary = index.getValueToRecordBitmap();
			assertEquals(2, boundary.length);
			assertEquals(5, boundary[0].getValue());
			assertArrayEquals(new int[]{1}, boundary[0].getRecordIds().getArray());
			assertEquals(10, boundary[1].getValue());
			assertArrayEquals(new int[]{2, 3}, boundary[1].getRecordIds().getArray());
		}

		@Test
		@DisplayName("A mixed index survives a Kryo round-trip and equals the original")
		void shouldRoundTripMixedIndex() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);            // primitive
			index.addRecord(10, 2);           // primitive
			index.addRecord(10, 3);           // promotes to bitmap
			index.addRecord(15, 4);           // primitive

			final Kryo kryo = new Kryo();
			kryo.register(InvertedIndex.class, new InvertedIndexSerializer());
			kryo.register(ValueToRecordBitmap.class, new ValueToRecordBitmapSerializer());
			kryo.register(TransactionalBitmap.class, new TransactionalIntegerBitmapSerializer());

			final Output output = new Output(1024, -1);
			kryo.writeObject(output, index);
			output.flush();

			final InvertedIndex reloaded = kryo.readObject(new Input(output.getBuffer()), InvertedIndex.class);

			assertEquals(index, reloaded);
			// single-record buckets are normalized back to the compact primitive form on reload
			assertTrue(reloaded.isPrimitiveBucket(5));
			assertTrue(reloaded.isPrimitiveBucket(15));
			assertFalse(reloaded.isPrimitiveBucket(10));
		}

		@Test
		@DisplayName("A bitmap reduced to a single record at runtime is normalized to a primitive on reload")
		void shouldNormalizeRuntimeReducedBitmapToPrimitiveOnReload() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(5, 20);           // promotes to a bitmap
			index.removeRecord(5, 20);        // reduced back to {1} but stays a bitmap at runtime

			// runtime: a reduced bitmap is NOT demoted in place
			assertFalse(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(5).getArray());

			final Kryo kryo = new Kryo();
			kryo.register(InvertedIndex.class, new InvertedIndexSerializer());
			kryo.register(ValueToRecordBitmap.class, new ValueToRecordBitmapSerializer());
			kryo.register(TransactionalBitmap.class, new TransactionalIntegerBitmapSerializer());

			final Output output = new Output(1024, -1);
			kryo.writeObject(output, index);
			output.flush();

			final InvertedIndex reloaded = kryo.readObject(new Input(output.getBuffer()), InvertedIndex.class);

			// reload re-derives the compact representation from the single-record content
			assertTrue(reloaded.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(5).getArray());
		}
	}

	@Nested
	@DisplayName("Transactional MVCC isolation")
	@Tag(TRANSACTION)
	class TransactionalTest {

		@Test
		@DisplayName("Promotion inside a transaction is invisible to the original committed instance")
		void shouldIsolatePromotionFromOriginal() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);

			assertStateAfterCommit(
				index,
				original -> original.addRecord(5, 20),
				(original, committed) -> {
					// the original is untouched: still a primitive holding only record 1
					assertTrue(original.isPrimitiveBucket(5));
					assertArrayEquals(new int[]{1}, original.getRecordsEqualTo(5).getArray());
					// the committed copy was promoted to a bitmap holding both records
					assertFalse(committed.isPrimitiveBucket(5));
					assertArrayEquals(new int[]{1, 20}, committed.getRecordsEqualTo(5).getArray());
				}
			);
		}

		@Test
		@DisplayName("A new single-record bucket added in a transaction is a primitive after commit")
		void shouldCommitNewPrimitiveBucket() {
			final InvertedIndex index = emptyIndex();

			assertStateAfterCommit(
				index,
				original -> original.addRecord(7, 42),
				(original, committed) -> {
					assertFalse(original.contains(7));
					assertTrue(committed.isPrimitiveBucket(7));
					assertArrayEquals(new int[]{42}, committed.getRecordsEqualTo(7).getArray());
				}
			);
		}

		@Test
		@DisplayName("Rolling back a promotion leaves the original primitive intact")
		void shouldDiscardPromotionOnRollback() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);

			assertStateAfterRollback(
				index,
				original -> original.addRecord(5, 20),
				(original, committed) -> {
					assertTrue(original.isPrimitiveBucket(5));
					assertArrayEquals(new int[]{1}, original.getRecordsEqualTo(5).getArray());
				}
			);
		}
	}

	@Nested
	@DisplayName("Formula-cache identity and staleness")
	@Tag(CACHE)
	class CacheIdentityTest {

		@Test
		@DisplayName("A primitive bucket round-trips its record set with parity to a one-element bitmap")
		void shouldExposeRecordSetWithRepresentationParity() {
			// the surviving record-set contract is that a single-record primitive and a cardinality-1 bitmap are
			// record-set equal and hash identically regardless of representation
			final ValueToRecordPrimitive primitive = new ValueToRecordPrimitive(5, 1);
			final ValueToRecordBitmap singletonBitmap = new ValueToRecordBitmap(5, 1);
			assertTrue(primitive.recordSetEquals(singletonBitmap));
			assertTrue(singletonBitmap.recordSetEquals(primitive));
			assertEquals(primitive.recordSetHashCode(), singletonBitmap.recordSetHashCode());
		}

		/** Equal supplier inputs retain their detached key. */
		@Test
		@DisplayName("The supplier key is stable while the bucket values are unchanged")
		void shouldKeepSupplierKeyStableForUnchangedBuckets() {
			final ValueToRecord[] buckets = {
				new ValueToRecordPrimitive(5, 1),
				new ValueToRecordBitmap(10, 2, 3)
			};

			final HistogramBitmapSupplier first = new HistogramBitmapSupplier(1L, buckets);
			final HistogramBitmapSupplier second = new HistogramBitmapSupplier(1L, buckets);

			// Equal bucket values and leaf versions describe the same computation.
			assertEquals(first.getKey(), second.getKey());
			assertEquals(first.getKey().getHash64(), second.getKey().getHash64());
		}

		/** Different bucket values change the detached key. */
		@Test
		@DisplayName("A structurally different bucket set yields a different supplier key")
		void shouldChangeSupplierKeyWhenBucketsDiffer() {
			final HistogramBitmapSupplier original = new HistogramBitmapSupplier(
				1L, new ValueToRecord[]{new ValueToRecordPrimitive(5, 1)}
			);
			final HistogramBitmapSupplier other = new HistogramBitmapSupplier(
				1L, new ValueToRecord[]{new ValueToRecordPrimitive(7, 1)}
			);

			// A different ordered value slice describes a different computation.
			assertNotEquals(original.getKey(), other.getKey());
			assertNotEquals(original.getKey().getHash64(), other.getKey().getHash64());
		}

		@Test
		@DisplayName("Committing a read-only transaction reuses the same index instance (cache stays warm)")
		void shouldReuseSamePrimitiveInstanceOnCleanCommit() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			// clear the dirty flag raised by the add so the upcoming commit is genuinely clean
			index.resetDirty();

			// a no-op (clean) commit must not mint a new index instance: the unchanged primitive keeps its
			// record-set id, so any DeferredFormula cached over it stays valid
			assertStateAfterCommit(
				index,
				original -> { /* read only - no mutation */ },
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("Promoting a single bucket to multi on commit refreshes the field-level index id")
		void shouldRefreshIndexIdWhenSingleBucketPromotedToMultiOnCommit() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);

			final long originalId = index.getId();

			// a mutating commit (promotion to a bitmap) mints a fresh InvertedIndex whose field-level id differs,
			// invalidating every cached histogram range of the field at once
			assertStateAfterCommit(
				index,
				original -> original.addRecord(5, 20),
				(original, committed) -> {
					assertEquals(originalId, original.getId());
					assertNotEquals(originalId, committed.getId());
				}
			);
		}

		@Test
		@DisplayName("Removing a bucket down to empty on commit refreshes the field-level index id")
		void shouldRefreshIndexIdWhenBucketRemovedToEmptyOnCommit() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);

			final long originalId = index.getId();

			// removing the only record empties the field; this is a mutation, so the committed copy carries a fresh id
			assertStateAfterCommit(
				index,
				original -> original.removeRecord(5, 1),
				(original, committed) -> {
					assertTrue(committed.isEmpty());
					assertEquals(originalId, original.getId());
					assertNotEquals(originalId, committed.getId());
				}
			);
		}

		@Test
		@DisplayName("A clean read-only commit keeps the same field-level index id")
		void shouldKeepIndexIdOnCleanCommit() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.resetDirty();

			final long originalId = index.getId();

			// a non-mutating commit returns the very same instance, so its id (and any cached range over it) survives
			assertStateAfterCommit(
				index,
				original -> { /* read only - no mutation */ },
				(original, committed) -> {
					assertEquals(originalId, original.getId());
					assertEquals(originalId, committed.getId());
				}
			);
		}
	}

	@Nested
	@DisplayName("Value ordering across representations")
	class ComparisonTest {

		@Test
		@DisplayName("compareTo orders buckets by value regardless of their representation")
		void shouldOrderValueToRecordAcrossRepresentations() {
			final ValueToRecord lowerPrimitive = new ValueToRecordPrimitive(5, 1);
			final ValueToRecord higherBitmap = new ValueToRecordBitmap(10, 2);

			// lower value sorts before the higher value across representations
			assertTrue(lowerPrimitive.compareTo(higherBitmap) < 0);
			assertTrue(higherBitmap.compareTo(lowerPrimitive) > 0);

			// equal value compares as equal whatever the representation (record ids are not part of ordering)
			assertEquals(0, lowerPrimitive.compareTo(new ValueToRecordPrimitive(5, 99)));
			assertEquals(0, lowerPrimitive.compareTo(new ValueToRecordBitmap(5, 99)));
		}
	}

	@Nested
	@DisplayName("Leaf-granular formula-cache staleness (issue #760)")
	@Tag(CACHE)
	class LeafGranularStalenessTest {

		/**
		 * Number of distinct single-record buckets the overflow fixture holds. The bucket tree's leaf capacity is 256
		 * values, so a slice of `N` buckets spans at least `⌈N/256⌉` leaves; the slice from
		 * {@link #OVERFLOW_SLICE_FROM} to 30 000 holds 29 701 buckets and therefore spans at least `⌈29701/256⌉ = 117`
		 * leaves — past the 100-leaf cap, so the leaf-version token collapses to the single whole-index id.
		 */
		private static final int OVERFLOW_BUCKET_COUNT = 30_000;
		/**
		 * First value of the overflowing slice. A leaf holds at most 256 values, so the leaf holding value `1` ends
		 * below this value and a write to value `1` lands in a leaf the slice never crosses.
		 */
		private static final int OVERFLOW_SLICE_FROM = 300;
		/**
		 * Number of distinct single-record buckets that span several leaves yet stay well under the leaf cap. 800 buckets
		 * over a 256-value leaf capacity occupy between `⌈800/256⌉ = 4` (leaves at max fill) and `⌈800/127⌉ = 7` (leaves
		 * at the 127 min-fill floor) leaves, so the slice folds to a handful of leaf-version ids without collapsing to the
		 * coarse whole-index id.
		 */
		private static final int MULTI_LEAF_BUCKET_COUNT = 800;

		/**
		 * Builds an index with `count` distinct single-record buckets `v -> v`. With `count` well above the leaf block
		 * size the buckets span several leaf pages, so a narrow low slice and a far-away high value provably live in
		 * different leaves.
		 *
		 * @param count the number of distinct buckets to create
		 * @return the populated index
		 */
		@Nonnull
		private static InvertedIndex denseIndex(int count) {
			final InvertedIndex index = emptyIndex();
			for (int v = 1; v <= count; v++) {
				index.addRecord(v, v);
			}
			return index;
		}

		/** Returns the key of a lazy histogram slice, keeping this coverage on the deferred supplier path. */
		@Nonnull
		private static FormulaKey deferredKey(@Nonnull InvertedIndex index, int from, int to) {
			final Formula formula = index.getRecords(from, to).getFormula();
			assertInstanceOf(DeferredFormula.class, formula);
			return formula.getKey();
		}

		@Test
		@DisplayName("A wide slice's token survives a commit that mutates a leaf it does not cross")
		void wideSliceTokenSurvivesUncrossedLeafMutation() {
			final InvertedIndex index = denseIndex(800);
			final FormulaKey keyBefore = deferredKey(index, 1, 150);

			assertStateAfterCommit(
				index,
				// mutate value 800 - far above the [1,150] slice, so it lives in a leaf the slice never crosses
				tested -> tested.addRecord(800, 999_999),
				(original, committed) -> {
					// the commit genuinely changed the index identity: a whole-index-coarse token (the old #37
					// behaviour) WOULD invalidate the cached slice here
					assertNotEquals(original.getId(), committed.getId());
					// but the slice only crossed untouched leaves, so its leaf-version token - and its hash - is
					// unchanged: the cached formula over this range stays valid across the unrelated write
					assertEquals(keyBefore, deferredKey(committed, 1, 150));
				}
			);
		}

		@Test
		@DisplayName("A wide slice's token changes when a commit mutates a leaf it crosses")
		void wideSliceTokenChangesOnCrossedLeafMutation() {
			final InvertedIndex index = denseIndex(800);
			final FormulaKey keyBefore = deferredKey(index, 1, 150);

			assertStateAfterCommit(
				index,
				// mutate value 50 - inside the [1,150] slice, so its leaf is one the slice crosses
				tested -> tested.addRecord(50, 999_999),
				(original, committed) -> {
					// The crossed leaf was re-minted, so the deferred key must change.
					assertNotEquals(keyBefore, deferredKey(committed, 1, 150));
				}
			);
		}

		@Test
		@DisplayName("A slice crossing more than the leaf cap collapses to the single whole-index token")
		void overflowSliceCollapsesToWholeIndexTokenAboveLeafCap() {
			final InvertedIndex index = denseIndex(OVERFLOW_BUCKET_COUNT);

			// the slice spans far more than the 100-leaf cap, so the per-leaf accumulator overflows and the
			// leaf-version sequence collapses to the single whole-index id
			final FormulaKey overflowBefore = deferredKey(index, OVERFLOW_SLICE_FROM, OVERFLOW_BUCKET_COUNT);
			assertEquals(overflowBefore, deferredKey(index, OVERFLOW_SLICE_FROM, OVERFLOW_BUCKET_COUNT));
			// the control: a narrow slice far from the first leaf stays under the cap and keys on its own leaves
			final FormulaKey narrowBefore = deferredKey(index, 5_000, 5_150);

			assertStateAfterCommit(
				index,
				// value 1 lives in the first leaf, which neither slice crosses; adding a record to its existing bucket
				// adds no value, so no split reaches the leaves the slices read
				tested -> tested.addRecord(1, 999_999),
				(original, committed) -> {
					// per-leaf tokens would keep both keys: only the whole-index id the overflowing slice collapsed to
					// is re-minted by a commit that never touched the leaves it crosses
					assertNotEquals(
						overflowBefore, deferredKey(committed, OVERFLOW_SLICE_FROM, OVERFLOW_BUCKET_COUNT),
						"the overflowing slice must key on the whole-index id the commit re-minted"
					);
					assertEquals(
						narrowBefore, deferredKey(committed, 5_000, 5_150),
						"a slice under the cap must key on its own leaves, which the commit did not touch"
					);
				}
			);
		}

		@Test
		@DisplayName("An empty slice yields the empty formula and consumes no leaf-version token")
		void emptySliceYieldsEmptyFormulaAndConsumesNoLeafToken() {
			final InvertedIndex index = denseIndex(MULTI_LEAF_BUCKET_COUNT);
			// a value range entirely above every bucket (the maximum value is MULTI_LEAF_BUCKET_COUNT) matches nothing
			final InvertedIndexSubSet slice = index.getSortedRecords(
				MULTI_LEAF_BUCKET_COUNT + 1_000, MULTI_LEAF_BUCKET_COUNT + 2_000
			);

			assertEquals(0, slice.getBuckets().length);
			// an empty slice crosses no leaf, so it folds to the canonical empty formula (its token is never read)
			assertSame(EmptyFormula.INSTANCE, slice.getFormula());
		}

		/**
		 * The predicate-matching formula is keyed by the buckets it matches, so a committed write outside the match
		 * keeps the key and a write changing a matched bucket changes it.
		 */
		@Test
		@DisplayName("The predicate-matching formula key holds until a matched bucket changes")
		void shouldKeepMatchingFormulaKeyUntilAMatchedBucketChanges() {
			final Predicate<Serializable> lowSlice = value -> ((Integer) value) <= 150;

			// A write outside the match preserves the operands of the matched slice.
			final InvertedIndex uncrossed = denseIndex(MULTI_LEAF_BUCKET_COUNT);
			final Formula uncrossedMatch = uncrossed.getRecordsMatchingFormula(lowSlice);
			// Exercise the multi-bucket bitmap operands rather than the single-bucket shortcut.
			assertInstanceOf(OrFormula.class, uncrossedMatch);
			final FormulaKey beforeUncrossed = uncrossedMatch.getKey();
			assertStateAfterCommit(
				uncrossed,
				// value MULTI_LEAF_BUCKET_COUNT lives in a leaf the [1, 150] match never crosses
				tested -> tested.addRecord(MULTI_LEAF_BUCKET_COUNT, 999_999),
				(original, committed) -> assertEquals(
					beforeUncrossed,
					committed.getRecordsMatchingFormula(lowSlice).getKey(),
					"A write outside the match must not change the matched slice's key!"
				)
			);

			// A write into a matched bucket changes the bitmap operands of the matched slice.
			final InvertedIndex crossed = denseIndex(MULTI_LEAF_BUCKET_COUNT);
			final FormulaKey beforeCrossed = crossed.getRecordsMatchingFormula(lowSlice).getKey();
			assertStateAfterCommit(
				crossed,
				// value 50 is inside the [1, 150] match, so its leaf is one the match crosses
				tested -> tested.addRecord(50, 999_999),
				(original, committed) -> assertNotEquals(
					beforeCrossed,
					committed.getRecordsMatchingFormula(lowSlice).getKey(),
					"A write into a matched bucket must change the matched slice's key!"
				)
			);
		}

	}

	/**
	 * The single-record primitive bucket is orthogonal to how a leaf stores its KEYS, and a range-typed index is the
	 * one shape where those keys are reconstructed on every read rather than held as objects. These pin the two
	 * mechanisms working together, at both persisted shapes.
	 */
	@Nested
	@DisplayName("Primitive and overflow buckets over reconstructed range keys")
	class RangeKeyedBucketTest {

		/**
		 * Builds an ascending integer range for the given ordinal, alternating an open bound in so both encodings of
		 * a bound are exercised.
		 *
		 * @param ordinal the ordinal to derive the range from
		 * @return the range
		 */
		@Nonnull
		private static IntegerNumberRange range(int ordinal) {
			return ordinal % 7 == 0
				? IntegerNumberRange.from(ordinal * 10)
				: IntegerNumberRange.between(ordinal * 10, ordinal * 10 + 5);
		}

		/**
		 * @return an empty inverted index over integer ranges, which selects the two-array range column
		 */
		@Nonnull
		private static InvertedIndex emptyRangeIndex() {
			return new InvertedIndex(
				IntegerNumberRange.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0);
		}

		@Test
		@DisplayName("a single-leaf range index promotes and demotes buckets exactly as a boxed one does")
		void shouldPromoteAndDemoteOverRangeKeys() {
			final InvertedIndex index = emptyRangeIndex();
			index.addRecord(range(1), 10);
			assertFalse(index.isPaged(), "A small range index must stay inline (SINGLE).");
			assertArrayEquals(new int[]{10}, index.getRecordsEqualTo(range(1)).getArray());

			// a second record promotes the bucket to a bitmap; the KEY is untouched by that promotion
			index.addRecord(range(1), 20);
			assertArrayEquals(new int[]{10, 20}, index.getRecordsEqualTo(range(1)).getArray());
			assertEquals(range(1), index.getValueToRecordBitmap()[0].getValue());

			// and demoting back to a single record leaves the reconstructed key equal to what went in
			index.removeRecord(range(1), 20);
			assertArrayEquals(new int[]{10}, index.getRecordsEqualTo(range(1)).getArray());
			assertEquals(range(1), index.getValueToRecordBitmap()[0].getValue());
			assertSame(IntegerNumberRange.class, index.getValueToRecordBitmap()[0].getValue().getClass());
		}

		@Test
		@DisplayName("a paged range index keeps single and multi-record buckets aligned with their keys")
		void shouldKeepBucketsAlignedAcrossLeavesOverRangeKeys() {
			final InvertedIndex index = emptyRangeIndex();
			final int valueCount = 800;
			for (int i = 0; i < valueCount; i++) {
				index.addRecord(range(i), i);
				// every third value carries a second record, so single and multi buckets interleave across leaves
				if (i % 3 == 0) {
					index.addRecord(range(i), 100_000 + i);
				}
			}
			assertTrue(index.isPaged(), "Fixture must span more than one leaf!");

			final ValueToRecordBitmap[] buckets = index.getValueToRecordBitmap();
			assertEquals(valueCount, buckets.length);
			final IntegerNumberRange[] expected = new IntegerNumberRange[valueCount];
			for (int i = 0; i < valueCount; i++) {
				expected[i] = range(i);
			}
			Arrays.sort(expected);
			for (int i = 0; i < valueCount; i++) {
				assertEquals(expected[i], buckets[i].getValue(), "bucket value mismatch at " + i);
			}
			// the record sets still belong to the values they were inserted under, across every leaf boundary
			for (int i = 0; i < valueCount; i++) {
				final int[] records = index.getRecordsEqualTo(range(i)).getArray();
				assertArrayEquals(
					i % 3 == 0 ? new int[]{i, 100_000 + i} : new int[]{i}, records,
					"record set mismatch for value " + i
				);
			}
		}
	}

}
