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
import java.util.Comparator;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
			multi.addRecord(5, new int[]{7, 8});
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

			assertThrows(EvitaInvalidUsageException.class, () -> index.removeRecord(5, new int[0]));
		}

		@Test
		@DisplayName("Adding the held id alongside a new id promotes and dedupes to the bitmap form")
		void shouldPromotePrimitiveWhenVarargContainsHeldIdPlusNew() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.addRecord(5, new int[]{1, 20});

			assertFalse(index.isPrimitiveBucket(5));
			assertArrayEquals(new int[]{1, 20}, index.getRecordsEqualTo(5).getArray());
		}

		@Test
		@DisplayName("Removing several ids of which one matches deletes the primitive bucket")
		void shouldDeletePrimitiveWhenRemovingMultipleIdsOneMatching() {
			final InvertedIndex index = emptyIndex();
			index.addRecord(5, 1);
			index.removeRecord(5, new int[]{99, 1});

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
		@DisplayName("A primitive reports a stable record-set id for its lifetime, unique across instances")
		void shouldMintStablePerInstanceId() {
			final ValueToRecordPrimitive primitive = new ValueToRecordPrimitive(5, 1);
			// stable across repeated reads of the same instance
			assertEquals(primitive.getRecordSetId(), primitive.getRecordSetId());
			// a distinct instance with identical content gets a distinct id (per-instance identity, like a bitmap)
			final ValueToRecordPrimitive sameContent = new ValueToRecordPrimitive(5, 1);
			assertNotEquals(primitive.getRecordSetId(), sameContent.getRecordSetId());
		}

		@Test
		@DisplayName("The supplier hash is stable while the bucket instances are unchanged")
		void shouldKeepSupplierHashStableForUnchangedBuckets() {
			final ValueToRecord[] buckets = {
				new ValueToRecordPrimitive(5, 1),
				new ValueToRecordBitmap(10, 2, 3)
			};

			final HistogramBitmapSupplier first = new HistogramBitmapSupplier(buckets);
			final HistogramBitmapSupplier second = new HistogramBitmapSupplier(buckets);

			// same bucket instances -> identical hash and transactional id hash (warm cache across recompute)
			assertEquals(first.getHash(), second.getHash());
			assertEquals(first.getTransactionalIdHash(), second.getTransactionalIdHash());
		}

		@Test
		@DisplayName("A structurally different bucket set yields a different supplier hash")
		void shouldChangeSupplierHashWhenBucketsDiffer() {
			final HistogramBitmapSupplier original = new HistogramBitmapSupplier(
				new ValueToRecord[]{new ValueToRecordPrimitive(5, 1)}
			);
			final HistogramBitmapSupplier promoted = new HistogramBitmapSupplier(
				new ValueToRecord[]{new ValueToRecordBitmap(5, 1, 20)}
			);

			assertNotEquals(original.getHash(), promoted.getHash());
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
}
