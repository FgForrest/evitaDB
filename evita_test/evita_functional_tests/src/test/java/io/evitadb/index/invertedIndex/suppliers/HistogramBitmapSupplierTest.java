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

package io.evitadb.index.invertedIndex.suppliers;

import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier.ValueHashStrategy;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies the formula-cache identity of {@link HistogramBitmapSupplier}: the typed value-hash dispatch used to
 * build the cache LOOKUP key (the package-private {@link ValueHashStrategy} resolved once per supplier) and the
 * supplier-level identity contract — the value-content lookup hash (shared across ranges of the same field) versus
 * the field-coarse staleness id hash.
 *
 * The assertions mirror the production hash function exactly: {@link HistogramBitmapSupplier} hashes via the shared
 * `HASH_FUNCTION = CacheSupervisor.createHashFunction()`, which is {@link LongHashFunction#xx3()}. This test
 * reproduces that exact function so the expected hashes are computed identically rather than merely sampled.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(CACHE)
@DisplayName("HistogramBitmapSupplier formula-cache identity")
class HistogramBitmapSupplierTest {

	/**
	 * The exact 64-bit hash function used by {@link HistogramBitmapSupplier} (and the whole formula-cache
	 * machinery): `CacheSupervisor.createHashFunction()` returns {@link LongHashFunction#xx3()}. Reproduced here so
	 * the test asserts exact equality against the production hashing rather than approximate behaviour.
	 */
	private static final LongHashFunction HASH_FUNCTION = LongHashFunction.xx3();

	@Nested
	@DisplayName("Value-hash strategy")
	class ValueHashStrategyTest {

		@Test
		@DisplayName("A String value resolves to the chars strategy and hashes through it")
		void shouldResolveAndHashStringViaCharsStrategy() {
			assertEquals(ValueHashStrategy.STRING, ValueHashStrategy.forType(String.class));
			assertEquals(
				HASH_FUNCTION.hashChars("hello"),
				ValueHashStrategy.STRING.hash(HASH_FUNCTION, "hello")
			);
		}

		@Test
		@DisplayName("A known 32-bit String collision pair yields distinct 64-bit content hashes")
		void shouldGiveDistinct64BitHashesForThirtyTwoBitCollisionPair() {
			// "Aa" and "BB" share the same 32-bit String.hashCode(); the 64-bit chars hash must separate them
			// so two different ranges of the same unmutated field never produce a wrong cache HIT
			assertEquals("Aa".hashCode(), "BB".hashCode());

			final long aa = ValueHashStrategy.STRING.hash(HASH_FUNCTION, "Aa");
			final long bb = ValueHashStrategy.STRING.hash(HASH_FUNCTION, "BB");

			assertNotEquals(aa, bb);
		}

		@Test
		@DisplayName("Each integral type resolves to its own specialized primitive hash function")
		void shouldHashEachIntegralTypeViaItsSpecializedFunction() {
			assertEquals(ValueHashStrategy.INTEGER, ValueHashStrategy.forType(Integer.class));
			assertEquals(
				HASH_FUNCTION.hashInt(5),
				ValueHashStrategy.INTEGER.hash(HASH_FUNCTION, 5)
			);

			assertEquals(ValueHashStrategy.LONG, ValueHashStrategy.forType(Long.class));
			assertEquals(
				HASH_FUNCTION.hashLong(5L),
				ValueHashStrategy.LONG.hash(HASH_FUNCTION, 5L)
			);

			assertEquals(ValueHashStrategy.SHORT, ValueHashStrategy.forType(Short.class));
			assertEquals(
				HASH_FUNCTION.hashShort((short) 5),
				ValueHashStrategy.SHORT.hash(HASH_FUNCTION, (short) 5)
			);

			assertEquals(ValueHashStrategy.BYTE, ValueHashStrategy.forType(Byte.class));
			assertEquals(
				HASH_FUNCTION.hashByte((byte) 5),
				ValueHashStrategy.BYTE.hash(HASH_FUNCTION, (byte) 5)
			);
		}

		@Test
		@DisplayName("Boolean and Character resolve to their specialized primitive hash functions")
		void shouldHashBooleanAndCharacterViaSpecializedFunctions() {
			assertEquals(ValueHashStrategy.BOOLEAN, ValueHashStrategy.forType(Boolean.class));
			assertEquals(
				HASH_FUNCTION.hashBoolean(true),
				ValueHashStrategy.BOOLEAN.hash(HASH_FUNCTION, Boolean.TRUE)
			);

			assertEquals(ValueHashStrategy.CHARACTER, ValueHashStrategy.forType(Character.class));
			assertEquals(
				HASH_FUNCTION.hashChar('x'),
				ValueHashStrategy.CHARACTER.hash(HASH_FUNCTION, 'x')
			);
		}

		@Test
		@DisplayName("Exotic comparable values fall back to a stable toString chars hash")
		void shouldHashExoticComparableValuesViaToStringFallback() {
			final BigDecimal decimal = new BigDecimal("12.50");
			final LocalDate date = LocalDate.of(2026, 6, 15);

			assertEquals(ValueHashStrategy.TO_STRING, ValueHashStrategy.forType(BigDecimal.class));
			assertEquals(
				HASH_FUNCTION.hashChars(decimal.toString()),
				ValueHashStrategy.TO_STRING.hash(HASH_FUNCTION, decimal)
			);

			assertEquals(ValueHashStrategy.TO_STRING, ValueHashStrategy.forType(LocalDate.class));
			assertEquals(
				HASH_FUNCTION.hashChars(date.toString()),
				ValueHashStrategy.TO_STRING.hash(HASH_FUNCTION, date)
			);
		}

		@Test
		@DisplayName("Distinct strategies separate values that share the same textual form")
		void shouldDistinguishStrategiesForSameTextualForm() {
			// the integral value 5 (int strategy) hashes differently from the textual "5" (chars strategy),
			// so an integral bucket never collides with a string bucket sharing the same printed form
			final long integral = ValueHashStrategy.INTEGER.hash(HASH_FUNCTION, 5);
			final long text = ValueHashStrategy.STRING.hash(HASH_FUNCTION, "5");
			assertNotEquals(integral, text);

			// the fallback strategy hashes purely by toString form: a BigDecimal whose toString is "5" shares the
			// textual hash with the String "5", confirming both reach the same chars-based formula
			assertEquals(
				ValueHashStrategy.TO_STRING.hash(HASH_FUNCTION, new BigDecimal("5")),
				text
			);
		}
	}

	@Nested
	@DisplayName("Supplier identity and staleness")
	class SupplierIdentityTest {

		@Test
		@DisplayName("Different ranges of the same field share the field-coarse staleness id hash")
		void shouldShareTransactionalIdHashAcrossDifferentRangesOfSameField() {
			final long fieldId = 42L;
			final HistogramBitmapSupplier lowerRange = new HistogramBitmapSupplier(
				fieldId, new ValueToRecord[]{new ValueToRecordPrimitive(5, 1)}
			);
			final HistogramBitmapSupplier higherRange = new HistogramBitmapSupplier(
				fieldId, new ValueToRecord[]{new ValueToRecordPrimitive(50, 2)}
			);

			// the staleness id is field-level, so both ranges of the field share it even though they cover
			// different values (and hence carry different lookup hashes)
			assertEquals(lowerRange.getTransactionalIdHash(), higherRange.getTransactionalIdHash());
			assertNotEquals(lowerRange.getHash(), higherRange.getHash());
		}

		@Test
		@DisplayName("Same buckets under a new field id keep the lookup hash but refresh the staleness hash")
		void shouldChangeTransactionalIdHashWhenFieldIdChanges() {
			final ValueToRecord[] buckets = {
				new ValueToRecordPrimitive(5, 1),
				new ValueToRecordBitmap(10, 2, 3)
			};

			final HistogramBitmapSupplier before = new HistogramBitmapSupplier(1L, buckets);
			final HistogramBitmapSupplier after = new HistogramBitmapSupplier(2L, buckets);

			// the value-content lookup hash is independent of the field id
			assertEquals(before.getHash(), after.getHash());
			// a commit that mutated the field minted a fresh id, so the staleness hash differs and old cache
			// entries of the field are invalidated at once
			assertNotEquals(before.getTransactionalIdHash(), after.getTransactionalIdHash());
		}

		@Test
		@DisplayName("Bucket order is part of the lookup hash identity")
		void shouldKeepBucketOrderInLookupHash() {
			final HistogramBitmapSupplier ascending = new HistogramBitmapSupplier(
				1L,
				new ValueToRecord[]{new ValueToRecordPrimitive(5, 1), new ValueToRecordPrimitive(10, 2)}
			);
			final HistogramBitmapSupplier reversed = new HistogramBitmapSupplier(
				1L,
				new ValueToRecord[]{new ValueToRecordPrimitive(10, 2), new ValueToRecordPrimitive(5, 1)}
			);

			// the lookup hash folds the bucket values in order, so swapping the order changes the identity
			assertNotEquals(ascending.getHash(), reversed.getHash());
		}

		@Test
		@DisplayName("The gathered transactional ids are the single owning field id")
		void shouldReturnSingleElementTransactionalIdArray() {
			final HistogramBitmapSupplier supplier = new HistogramBitmapSupplier(
				77L, new ValueToRecord[]{new ValueToRecordPrimitive(5, 1)}
			);

			assertArrayEquals(new long[]{77L}, supplier.gatherTransactionalIds());
			assertEquals(HASH_FUNCTION.hashLong(77L), supplier.getTransactionalIdHash());
		}

		@Test
		@DisplayName("The estimated cardinality is the sum of the bucket sizes")
		void shouldComputeEstimatedCardinalityAsSumOfBucketSizes() {
			final HistogramBitmapSupplier supplier = new HistogramBitmapSupplier(
				1L,
				new ValueToRecord[]{
					new ValueToRecordPrimitive(5, 1),       // size 1
					new ValueToRecordBitmap(10, 2, 3, 4)    // size 3
				}
			);

			assertEquals(4, supplier.getEstimatedCardinality());
		}
	}
}
