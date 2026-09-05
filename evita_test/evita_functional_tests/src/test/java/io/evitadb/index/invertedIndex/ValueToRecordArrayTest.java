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

package io.evitadb.index.invertedIndex;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ValueToRecordArray}, the array-tier {@link ValueToRecord} flyweight that sits between
 * {@link ValueToRecordPrimitive} and {@link ValueToRecordBitmap}.
 *
 * The contract that matters most here is **parity with its two siblings**: the promotion and demotion thresholds of
 * the underlying bucket tier differ, so the very same record set really can be found in either tier, and whole-index
 * equality and hashing must not be able to tell which one holds it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("ValueToRecordArray")
class ValueToRecordArrayTest {
	/**
	 * The value every bucket in this suite carries, unless the test is about the value itself.
	 */
	private static final String VALUE = "alpha";

	/**
	 * @param count how many ids to produce
	 * @return the ids `1..count`, unsigned-sorted and distinct
	 */
	@Nonnull
	private static int[] idsUpTo(int count) {
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = i + 1;
		}
		return ids;
	}

	@Nested
	@DisplayName("Construction and accessors")
	class ConstructionAndAccessorsTest {

		@Test
		@DisplayName("The bucket answers its value, cardinality and record ids")
		void shouldExposeValueAndRecords() {
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, 1, 2, 3);

			assertEquals(VALUE, bucket.getValue());
			assertEquals(3, bucket.size());
			assertFalse(bucket.isEmpty());
			assertArrayEquals(new int[]{1, 2, 3}, bucket.getRecordIds().getArray());
		}

		@Test
		@DisplayName("A zero-length record set reports empty")
		void shouldReportAnEmptyRecordSet() {
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE);

			assertEquals(0, bucket.size());
			assertTrue(bucket.isEmpty());
			assertTrue(bucket.getRecordIds().isEmpty());
		}

		@Test
		@DisplayName("The record ids come back as a read-only array view, not as a roaring bitmap")
		void shouldHandOutASortedArrayView() {
			// the whole reason the type exists: a read of a small bucket must not build a throwaway roaring bitmap
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, 1, 2, 3);

			assertInstanceOf(SortedArrayBitmap.class, bucket.getRecordIds());
			assertEquals(new SortedArrayBitmap(1, 2, 3), bucket.getRecordIds());
		}

		@Test
		@DisplayName("toString names the value and the record set")
		void shouldDescribeItself() {
			assertEquals(
				"ValueToRecordArray{value=alpha, recordIds=[1, 2, 3]}",
				new ValueToRecordArray(VALUE, 1, 2, 3).toString()
			);
		}
	}

	@Nested
	@DisplayName("Record-set hash parity across the tiers")
	class RecordSetHashParityTest {

		@Test
		@DisplayName("A one-record bucket hashes identically in all three tiers")
		void shouldHashLikeBothSiblingsAtCardinalityOne() {
			final int recordId = 7;

			final int arrayHash = new ValueToRecordArray(VALUE, recordId).recordSetHashCode();

			assertEquals(new ValueToRecordBitmap(VALUE, recordId).recordSetHashCode(), arrayHash);
			assertEquals(new ValueToRecordPrimitive(VALUE, recordId).recordSetHashCode(), arrayHash);
		}

		@Test
		@DisplayName("A two-record bucket hashes identically in the array and bitmap tiers")
		void shouldHashLikeTheBitmapTierAtCardinalityTwo() {
			assertEquals(
				new ValueToRecordBitmap(VALUE, 3, 9).recordSetHashCode(),
				new ValueToRecordArray(VALUE, 3, 9).recordSetHashCode()
			);
		}

		@Test
		@DisplayName("A record set inside the band both tiers can hold hashes identically in either")
		void shouldHashIdenticallyInsideTheOverlappingBand() {
			// the promote threshold (128) and the demote threshold (64) differ, so a bucket of 65..128 records is
			// genuinely found in either tier depending on how it got there - and the two must hash the same
			final int[] ids = idsUpTo(100);

			assertEquals(
				new ValueToRecordBitmap(VALUE, ids).recordSetHashCode(),
				new ValueToRecordArray(VALUE, ids).recordSetHashCode()
			);
		}

		@Test
		@DisplayName("A contiguous run hashes identically however the bitmap tier compressed its containers")
		void shouldHashIdenticallyAcrossRunCompression() {
			// THE case the array tier can actually reach: a contiguous run is exactly the shape runOptimize()
			// rewrites into a RunContainer, and PersistentRoaringBitmap#hashCode is documented as guaranteeing equal
			// hashes only for bitmaps that agree on hasRunCompression(). The bitmap tier is read through its
			// transactional layer, whose getMergedBitmap() run-optimises; the array tier has no containers at all.
			// Delegating to the bitmap's own hashCode therefore split one record set into two keys.
			final int[] contiguousRun = idsUpTo(128);
			final TransactionalBitmap bitmapTier = new TransactionalBitmap(contiguousRun);
			final ValueToRecordBitmap asBitmap = new ValueToRecordBitmap(VALUE, bitmapTier);
			final ValueToRecordArray asArray = new ValueToRecordArray(VALUE, contiguousRun);

			// outside a transaction the committed bitmap is not run-compressed and the two already agree
			assertEquals(asBitmap.recordSetHashCode(), asArray.recordSetHashCode());

			// inside one, the merged view IS run-compressed - and the hash must not move
			assertStateAfterCommit(
				bitmapTier,
				tested -> {
					tested.add(129);
					assertEquals(
						new ValueToRecordArray(VALUE, idsUpTo(129)).recordSetHashCode(),
						asBitmap.recordSetHashCode(),
						"a run-compressed bitmap tier must hash its record set exactly as the array tier does"
					);
					assertTrue(
						asBitmap.recordSetEquals(new ValueToRecordArray(VALUE, idsUpTo(129))),
						"and must still compare equal to it"
					);
				},
				(original, committed) -> {
					// nothing to assert on the committed copy - the parity being tested is observed inside the
					// transaction, which is the only place the merged bitmap is run-optimised
				}
			);
		}

		@Test
		@DisplayName("A record set holding negative ids hashes identically in either tier")
		void shouldHashIdenticallyWithNegativeIds() {
			// the array is kept in unsigned order and the roaring bitmap enumerates in the same one, which is what
			// makes the hash agree without a rotation here
			final int[] unsignedSorted = {0, 5, Integer.MAX_VALUE, Integer.MIN_VALUE, -1};

			assertEquals(
				new ValueToRecordBitmap(VALUE, unsignedSorted).recordSetHashCode(),
				new ValueToRecordArray(VALUE, unsignedSorted).recordSetHashCode()
			);
		}
	}

	@Nested
	@DisplayName("Record-set equality across the tiers")
	class RecordSetEqualsTest {

		@Test
		@DisplayName("The same record set compares equal against every sibling, in both directions")
		void shouldCompareEqualAgainstEverySibling() {
			final ValueToRecordArray twoRecords = new ValueToRecordArray(VALUE, 3, 9);
			final ValueToRecordBitmap bitmapTwin = new ValueToRecordBitmap(VALUE, 3, 9);

			assertTrue(twoRecords.recordSetEquals(bitmapTwin));
			assertTrue(bitmapTwin.recordSetEquals(twoRecords));
			assertTrue(twoRecords.recordSetEquals(new ValueToRecordArray(VALUE, 3, 9)));

			final ValueToRecordArray oneRecord = new ValueToRecordArray(VALUE, 7);
			final ValueToRecordPrimitive primitiveTwin = new ValueToRecordPrimitive(VALUE, 7);
			assertTrue(oneRecord.recordSetEquals(primitiveTwin));
			assertTrue(primitiveTwin.recordSetEquals(oneRecord));
		}

		@Test
		@DisplayName("A record set holding negative ids compares equal against the bitmap tier")
		void shouldCompareEqualWithNegativeIds() {
			// recordSetEquals walks this bucket's array positionally against the other side's iterator, which is only
			// sound because both enumerate unsigned - a signed array here would mis-compare the moment an id is negative
			final int[] unsignedSorted = {0, 5, Integer.MAX_VALUE, Integer.MIN_VALUE, -1};
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, unsignedSorted);
			final ValueToRecordBitmap twin = new ValueToRecordBitmap(VALUE, unsignedSorted);

			assertTrue(bucket.recordSetEquals(twin));
			assertTrue(twin.recordSetEquals(bucket));
		}

		@Test
		@DisplayName("Different content, different size and null all compare unequal")
		void shouldRejectAnythingButTheSameRecordSet() {
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, 3, 9);

			assertFalse(bucket.recordSetEquals(new ValueToRecordArray(VALUE, 3, 10)));
			assertFalse(bucket.recordSetEquals(new ValueToRecordBitmap(VALUE, 3, 10)));
			assertFalse(bucket.recordSetEquals(new ValueToRecordArray(VALUE, 3, 9, 10)));
			assertFalse(bucket.recordSetEquals(new ValueToRecordPrimitive(VALUE, 3)));
			assertFalse(bucket.recordSetEquals(null));
		}
	}

	@Nested
	@DisplayName("Bucket identity")
	class IdentityTest {

		@Test
		@DisplayName("equals and hashCode identify a bucket by its value alone")
		void shouldIdentifyByValue() {
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, 1, 2, 3);

			assertEquals(bucket, new ValueToRecordArray(VALUE, 7, 8));
			assertEquals(bucket.hashCode(), new ValueToRecordArray(VALUE, 7, 8).hashCode());
			assertEquals(VALUE.hashCode(), bucket.hashCode());
			assertNotEquals(bucket, new ValueToRecordArray("beta", 1, 2, 3));
			assertNotEquals(bucket, new ValueToRecordBitmap(VALUE, 1, 2, 3));
			assertNotEquals(null, bucket);
		}

		@Test
		@DisplayName("compareTo orders by value across all three tiers")
		void shouldOrderByValueAcrossTiers() {
			final ValueToRecordArray middle = new ValueToRecordArray("m", 1, 2);

			assertTrue(middle.compareTo(new ValueToRecordArray("z", 1, 2)) < 0);
			assertTrue(middle.compareTo(new ValueToRecordBitmap("a", 1, 2)) > 0);
			assertEquals(0, middle.compareTo(new ValueToRecordPrimitive("m", 1)));
		}
	}

	@Nested
	@DisplayName("Transactional no-op")
	class TransactionalNoOpTest {

		@Test
		@DisplayName("An array-tier bucket survives a commit as the very same instance and opens no layer")
		void shouldCommitToItselfWithoutOpeningALayer() {
			// the type is an immutable projection of a leaf slot the tree owns, so a commit has nothing to merge -
			// asserted through the real machinery, whose handler also calls verifyLayerWasFullySwept, proving no
			// layer was left behind
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, 1, 2, 3);

			assertStateAfterCommit(
				bucket,
				original -> {
					// nothing is written: the flyweight has no mutator, and a change would be applied to the leaf
				},
				(original, committed) -> {
					assertSame(original, committed, "an immutable, layer-less bucket commits to itself");
					assertArrayEquals(new int[]{1, 2, 3}, committed.getRecordIds().getArray());
				}
			);
		}

		@Test
		@DisplayName("The record set the array tier presents is the one an equivalent bitmap would")
		void shouldPresentTheSameRecordSetAsABitmap() {
			final ValueToRecordArray bucket = new ValueToRecordArray(VALUE, 1, 2, 3);

			assertArrayEquals(new BaseBitmap(1, 2, 3).getArray(), bucket.getRecordIds().getArray());
		}
	}

}
