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

package io.evitadb.core.cache.payload;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.store.cache.serializer.FlattenedFormulaWithFilteredPricesForHistogramSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Currency;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.utils.MemoryMeasuringConstants.REFERENCE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FlattenedFormulaWithFilteredPricesForHistogram} verifying that:
 *
 * - the per-inner-record records are exposed via `getFilteredPriceRecordsForHistogram(...)` while the parent's
 *   `getFilteredPriceRecords(...)` keeps returning the per-entity records (sibling-payload regression guard);
 * - the size estimate accounts for the per-inner-record contribution on top of the parent estimate (memory
 *   accounting must reflect the additional payload);
 * - `prepareForFlattening()` is invoked on the per-inner-record records during construction (verified
 *   indirectly by passing a `ResolvedFilteredPriceRecords` whose underlying array becomes accessible).
 *
 * The cache payload sibling exists so that:
 *
 * - cached entries produced by earlier releases remain deserializable (the parent payload signature is
 *   unchanged);
 * - non-histogram queries flatten into the pre-existing sibling and pay no extra memory cost — the
 *   selection is driven by the planner-set `collectPerInnerRecordPrices` flag on
 *   {@link io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FlattenedFormulaWithFilteredPricesForHistogram")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(PRICE)
@Tag(HISTOGRAM)
class FlattenedFormulaWithFilteredPricesForHistogramTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final long FORMULA_HASH = 0xABCDEFL;
	private static final long TRANSACTIONAL_ID_HASH = 0x123456L;
	private static final int INDEXED_PRICE_PLACES = 2;

	@Nested
	@DisplayName("Sibling payload exposes both per-entity and per-inner-record records")
	class RecordExposureTest {

		@Test
		@DisplayName("should expose the per-inner-record records via getFilteredPriceRecordsForHistogram")
		void shouldExposePerInnerRecordRecordsViaHistogramAccessor() {
			final PriceRecordContract[] perEntity = {
				priceRecord(1, 100, 200),
				priceRecord(2, 110, 220)
			};
			final PriceRecordContract[] perInnerRecord = {
				priceRecord(1, 100, 200),
				priceRecord(2, 110, 220),
				priceRecord(2, 130, 260)
			};
			final FilteredPriceRecords perEntityRecords = new ResolvedFilteredPriceRecords(
				perEntity, SortingForm.ENTITY_PK
			);
			final FilteredPriceRecords perInnerRecordRecords = new ResolvedFilteredPriceRecords(
				perInnerRecord, SortingForm.NOT_SORTED
			);

			final FlattenedFormulaWithFilteredPricesForHistogram payload = createPayload(
				perEntityRecords, perInnerRecordRecords
			);

			// the histogram accessor must return the per-inner-record collection — this is the whole reason
			// the sibling payload exists. Reached via the context-free accessor used by the cache serializer
			// path so the call does not have to fabricate a sentinel null QueryExecutionContext.
			assertSame(perInnerRecordRecords, payload.getPerInnerRecordPriceRecords());
		}

		@Test
		@DisplayName("should expose the per-entity records via the parent's getFilteredPriceRecords")
		void shouldExposePerEntityRecordsViaParentAccessor() {
			final PriceRecordContract[] perEntity = {priceRecord(1, 100, 200)};
			final PriceRecordContract[] perInnerRecord = {
				priceRecord(1, 100, 200),
				priceRecord(1, 150, 300)
			};
			final FilteredPriceRecords perEntityRecords = new ResolvedFilteredPriceRecords(
				perEntity, SortingForm.ENTITY_PK
			);
			final FilteredPriceRecords perInnerRecordRecords = new ResolvedFilteredPriceRecords(
				perInnerRecord, SortingForm.NOT_SORTED
			);

			final FlattenedFormulaWithFilteredPricesForHistogram payload = createPayload(
				perEntityRecords, perInnerRecordRecords
			);

			// regression guard — the parent's per-entity behaviour must be unchanged so non-histogram
			// consumers (entity result fetch) keep observing the lowest-price-per-entity view
			assertSame(perEntityRecords, payload.getFilteredPriceRecords(null));
		}

		@Test
		@DisplayName("should keep per-entity and per-inner-record records as independent references")
		void shouldKeepPerEntityAndPerInnerRecordRecordsIndependent() {
			final FilteredPriceRecords perEntityRecords = new ResolvedFilteredPriceRecords(
				new PriceRecordContract[]{priceRecord(1, 100, 200)}, SortingForm.ENTITY_PK
			);
			final FilteredPriceRecords perInnerRecordRecords = new ResolvedFilteredPriceRecords(
				new PriceRecordContract[]{priceRecord(1, 100, 200), priceRecord(1, 150, 300)},
				SortingForm.NOT_SORTED
			);

			final FlattenedFormulaWithFilteredPricesForHistogram payload = createPayload(
				perEntityRecords, perInnerRecordRecords
			);

			// the two accessors must NOT alias — the histogram consumer must read a different array than
			// the entity consumer
			assertNotSame(
				payload.getFilteredPriceRecords(null),
				payload.getPerInnerRecordPriceRecords()
			);
		}
	}

	@Nested
	@DisplayName("Size estimation accounts for the per-inner-record contribution")
	class SizeEstimateTest {

		@Test
		@DisplayName("should estimate a size strictly greater than the parent for the same inputs")
		void shouldEstimateSizeStrictlyGreaterThanParentForSameInputs() {
			final long[] transactionalIds = {1L, 2L, 3L};
			final Bitmap computationalResult = new BaseBitmap(1, 2, 3);
			final Bitmap recordsFilteredOutByPredicate = new BaseBitmap(4, 5);
			final PriceEvaluationContext context = createContext();
			final int perInnerRecordPriceCount = 5;

			final int parentEstimate = FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.estimateSize(
				transactionalIds, computationalResult, recordsFilteredOutByPredicate, context
			);
			final int histogramEstimate = FlattenedFormulaWithFilteredPricesForHistogram.estimateSize(
				transactionalIds, computationalResult, perInnerRecordPriceCount,
				recordsFilteredOutByPredicate, context
			);

			// memory accounting must reflect the additional per-inner-record payload — otherwise the cache
			// would under-charge the histogram-enabled entries and start evicting the wrong entries first
			assertTrue(
				histogramEstimate > parentEstimate,
				"Histogram payload estimate (" + histogramEstimate + ") must be strictly greater than parent (" +
					parentEstimate + ")"
			);
		}

		@Test
		@DisplayName("should grow proportionally with the per-inner-record price count")
		void shouldGrowProportionallyWithPerInnerRecordPriceCount() {
			final long[] transactionalIds = {1L};
			final Bitmap computationalResult = new BaseBitmap(1);
			final Bitmap recordsFilteredOutByPredicate = EmptyBitmap.INSTANCE;
			final PriceEvaluationContext context = createContext();

			final int small = FlattenedFormulaWithFilteredPricesForHistogram.estimateSize(
				transactionalIds, computationalResult, 1, recordsFilteredOutByPredicate, context
			);
			final int large = FlattenedFormulaWithFilteredPricesForHistogram.estimateSize(
				transactionalIds, computationalResult, 100, recordsFilteredOutByPredicate, context
			);

			// the per-inner-record contribution dominates as the count grows; this asserts the accounting
			// actually walks the count parameter rather than rounding it to a constant
			assertTrue(
				large > small,
				"Estimate with 100 per-inner-record prices (" + large + ") must exceed estimate with 1 (" + small + ")"
			);
		}

		@Test
		@DisplayName("should match the parent estimate's delta for the per-inner-record contribution")
		void shouldMatchParentEstimateDeltaForPerInnerRecordContribution() {
			final long[] transactionalIds = {1L, 2L};
			final Bitmap computationalResult = new BaseBitmap(1, 2);
			final Bitmap recordsFilteredOutByPredicate = EmptyBitmap.INSTANCE;
			final PriceEvaluationContext context = createContext();
			final int perInnerRecordPriceCount = 7;

			final int parentEstimate = FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.estimateSize(
				transactionalIds, computationalResult, recordsFilteredOutByPredicate, context
			);
			final int histogramEstimate = FlattenedFormulaWithFilteredPricesForHistogram.estimateSize(
				transactionalIds, computationalResult, perInnerRecordPriceCount,
				recordsFilteredOutByPredicate, context
			);

			// the difference equals the per-inner-record contribution: count * PRICE_RECORD_SIZE + REFERENCE_SIZE
			// (one extra reference slot for the perInnerRecordPriceRecords field on the sibling payload)
			final int expectedDelta = perInnerRecordPriceCount * FlattenedFormulaWithFilteredPrices.PRICE_RECORD_SIZE
				+ REFERENCE_SIZE;
			assertEquals(expectedDelta, histogramEstimate - parentEstimate);
		}
	}

	@Nested
	@DisplayName("Histogram capability probe")
	class CapabilityProbeTest {

		/**
		 * When `perInnerRecordPriceCount` is zero the size delta is exactly `REFERENCE_SIZE` — the one extra
		 * reference slot for the `perInnerRecordPriceRecords` field on the sibling payload. This documents
		 * the lower bound of the memory accounting so future refactors cannot accidentally drop the slot.
		 */
		@Test
		@DisplayName("should estimate size with zero per-inner-record count as parent estimate plus reference size")
		void shouldEstimateSizeWithZeroPerInnerRecordCount() {
			final long[] transactionalIds = {1L};
			final Bitmap computationalResult = new BaseBitmap(1);
			final Bitmap recordsFilteredOutByPredicate = EmptyBitmap.INSTANCE;
			final PriceEvaluationContext context = createContext();

			final int parentEstimate = FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.estimateSize(
				transactionalIds, computationalResult, recordsFilteredOutByPredicate, context
			);
			final int histogramEstimate = FlattenedFormulaWithFilteredPricesForHistogram.estimateSize(
				transactionalIds, computationalResult, 0, recordsFilteredOutByPredicate, context
			);

			// with no per-inner-record contribution the delta collapses to exactly the extra reference slot
			assertEquals(
				parentEstimate + REFERENCE_SIZE,
				histogramEstimate
			);
		}
	}

	@Nested
	@DisplayName("Construction invokes prepareForFlattening on the per-inner-record records")
	class PrepareForFlatteningTest {

		@Test
		@DisplayName("should materialise the per-inner-record array via prepareForFlattening on construction")
		void shouldMaterialisePerInnerRecordArrayViaPrepareForFlattening() {
			final PriceRecordContract[] perInnerRecordsArray = {
				// intentionally unsorted by entity primary key — prepareForFlattening() must trigger the
				// internal sort so the payload is "frozen" before any iterator runs in another thread
				priceRecord(5, 500, 1000),
				priceRecord(1, 100, 200),
				priceRecord(3, 300, 600)
			};
			final ResolvedFilteredPriceRecords perInnerRecordRecords = new ResolvedFilteredPriceRecords(
				perInnerRecordsArray, SortingForm.NOT_SORTED
			);
			final ResolvedFilteredPriceRecords perEntityRecords = new ResolvedFilteredPriceRecords(
				new PriceRecordContract[]{priceRecord(1, 100, 200)}, SortingForm.ENTITY_PK
			);

			// construct the payload — this must invoke prepareForFlattening() on the per-inner-record records,
			// which in turn forces ENTITY_PK sorting
			final FlattenedFormulaWithFilteredPricesForHistogram payload = createPayload(
				perEntityRecords, perInnerRecordRecords
			);

			// indirect assertion — after construction, the sorting form must have been advanced to ENTITY_PK
			// by prepareForFlattening(); if the constructor skipped the call, this would still be NOT_SORTED
			assertEquals(SortingForm.ENTITY_PK, perInnerRecordRecords.getSortingForm());

			// sanity — payload exposes the same (now materialised) instance
			assertSame(perInnerRecordRecords, payload.getPerInnerRecordPriceRecords());

			// the array is accessible and sorted by entity primary key
			final PriceRecordContract[] sorted = perInnerRecordRecords.getPriceRecords();
			assertNotNull(sorted);
			assertEquals(3, sorted.length);
			assertTrue(
				sorted[0].entityPrimaryKey() <= sorted[1].entityPrimaryKey()
					&& sorted[1].entityPrimaryKey() <= sorted[2].entityPrimaryKey(),
				"Per-inner-record array must be sorted by entity primary key after prepareForFlattening()"
			);
		}
	}

	@Nested
	@DisplayName("Kryo serializer is registered for the sibling payload")
	class KryoRegistrationTest {

		/**
		 * Regression guard for the Kryo registration of the sibling payload — when the sibling payload class
		 * is not registered, the cache writer throws `Class is not registered` at the first attempt to cache
		 * a histogram-enabled query. The dedicated serializer's `write` method is exercised against a fresh
		 * `Kryo` instance to confirm the write path is intact end-to-end: the serializer can read each field
		 * of the payload via its public accessors without tripping any null/registration guard, and the
		 * resulting buffer actually carries bytes (the per-entity AND per-inner-record records).
		 *
		 * The read-side round-trip is intentionally not exercised here because deserialization requires a
		 * `GlobalEntityIndex` fixture far heavier than the bug — the missing registration — warrants. The
		 * read path mirrors the parent's `FlattenedFormulaWithFilteredPricesAndFilteredOutRecordsSerializer`
		 * (which has no dedicated unit test for the same reason) and is covered by the engine's functional
		 * cache tests.
		 */
		@Test
		@DisplayName("should write payload via dedicated serializer without Kryo registration miss")
		void shouldWritePayloadViaDedicatedSerializerWithoutKryoRegistrationMiss() {
			final FilteredPriceRecords perEntityRecords = new ResolvedFilteredPriceRecords(
				new PriceRecordContract[]{priceRecord(1, 100, 200)}, SortingForm.ENTITY_PK
			);
			final FilteredPriceRecords perInnerRecordRecords = new ResolvedFilteredPriceRecords(
				new PriceRecordContract[]{priceRecord(1, 100, 200), priceRecord(1, 150, 300)},
				SortingForm.NOT_SORTED
			);
			final FlattenedFormulaWithFilteredPricesForHistogram payload = createPayload(
				perEntityRecords, perInnerRecordRecords
			);

			// the supplier is unused on the write path — only the read path consults the global entity index
			final FlattenedFormulaWithFilteredPricesForHistogramSerializer serializer =
				new FlattenedFormulaWithFilteredPricesForHistogramSerializer(() -> null);
			final Kryo kryo = new Kryo();
			// register the auxiliary types the serializer writes via `kryo.writeObjectOrNull`/`kryo.writeObject`;
			// in production the PriceIndexKey serializer plus its `Currency` field are registered by the
			// surrounding Kryo configurer — here we mirror just enough to drive the write path
			kryo.register(QueryPriceMode.class);
			kryo.register(BigDecimal.class);
			kryo.register(PriceIndexKey.class);
			kryo.register(Currency.class);
			kryo.register(PriceInnerRecordHandling.class);

			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (final Output output = new Output(buffer)) {
				serializer.write(kryo, output, payload);
			}

			// the serializer must produce a non-trivial payload — the per-entity AND per-inner-record records
			// both carry price record ids, so the buffer cannot be empty or trivially short
			assertTrue(
				buffer.size() > 32,
				"Serialized payload should contain both record sets; got " + buffer.size() + " bytes"
			);
		}
	}

	/**
	 * Builds a {@link FlattenedFormulaWithFilteredPricesForHistogram} with predetermined identity hashes and
	 * the supplied per-entity / per-inner-record record collections. All other fields receive innocuous
	 * defaults sufficient for the assertions in this test class.
	 *
	 * @param perEntityRecords     records exposed via the parent `getFilteredPriceRecords(...)`
	 * @param perInnerRecordRecords records exposed via `getFilteredPriceRecordsForHistogram(...)`
	 * @return a freshly constructed payload instance
	 */
	@Nonnull
	private static FlattenedFormulaWithFilteredPricesForHistogram createPayload(
		@Nonnull FilteredPriceRecords perEntityRecords,
		@Nonnull FilteredPriceRecords perInnerRecordRecords
	) {
		return new FlattenedFormulaWithFilteredPricesForHistogram(
			FORMULA_HASH,
			TRANSACTIONAL_ID_HASH,
			new long[]{1L, 2L},
			new BaseBitmap(1, 2),
			perEntityRecords,
			perInnerRecordRecords,
			EmptyBitmap.INSTANCE,
			createContext(),
			QueryPriceMode.WITH_TAX,
			BigDecimal.ZERO,
			BigDecimal.TEN,
			INDEXED_PRICE_PLACES
		);
	}

	/**
	 * Creates a synthetic {@link PriceRecord} with deterministic ids derived from the entity primary key.
	 * Inner record id is left at 0 since the sibling payload tests do not require multi-inner-record entities
	 * (the per-inner-record vs per-entity distinction is captured by the two array shapes, not the inner ids).
	 *
	 * @param entityPrimaryKey entity primary key of the synthetic record
	 * @param priceWithTax     price amount with tax (as the integer representation used by the engine)
	 * @param priceWithoutTax  price amount without tax (as the integer representation used by the engine)
	 * @return a fresh price record instance
	 */
	@Nonnull
	private static PriceRecordContract priceRecord(int entityPrimaryKey, int priceWithTax, int priceWithoutTax) {
		// internalPriceId and priceId are derived from entityPrimaryKey to keep test fixtures terse while
		// still producing distinct identities for equals/hashCode (PriceRecord.equals uses internalPriceId)
		return new PriceRecord(
			entityPrimaryKey * 1000 + priceWithTax,
			entityPrimaryKey * 10,
			entityPrimaryKey,
			priceWithTax,
			priceWithoutTax
		);
	}

	/**
	 * Creates a {@link PriceEvaluationContext} with a single CZK price index key, mirroring the helper used
	 * in `LowestPriceTerminationFormulaTest`.
	 *
	 * @return configured price evaluation context for the basic CZK price list
	 */
	@Nonnull
	private static PriceEvaluationContext createContext() {
		return new PriceEvaluationContext(
			null,
			new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.NONE)
		);
	}
}
