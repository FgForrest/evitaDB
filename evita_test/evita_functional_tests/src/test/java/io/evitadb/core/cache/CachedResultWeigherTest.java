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

package io.evitadb.core.cache;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.*;
import io.evitadb.core.query.extraResult.translator.histogram.cache.*;
import io.evitadb.index.bitmap.*;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.*;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.utils.JolHeapSize;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;

import static io.evitadb.core.cache.CachedResultTestSupport.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks complete long charges and calibrates retained graphs with JOL on the running VM.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Cached result weigher")
@Tag(ENGINE)
@Tag(CACHE)
class CachedResultWeigherTest {
	/** Verifies the inclusive cap, integer boundary and refusal reason without allocating oversized arrays. */
	@Test
	@DisplayName("Refuses oversized charges without saturation")
	void shouldRefuseOversizedCharges() {
		assertEquals(100, CachedResultWeigher.admittedWeight(100, 10_000));
		assertEquals(CachedResultWeigher.OVERSIZED, CachedResultWeigher.admittedWeight(101, 10_099));
		assertEquals(Integer.MAX_VALUE,
			CachedResultWeigher.admittedWeight(Integer.MAX_VALUE, 100L * Integer.MAX_VALUE));
		assertEquals(CachedResultWeigher.OVERSIZED,
			CachedResultWeigher.admittedWeight(1L + Integer.MAX_VALUE, Long.MAX_VALUE));
		assertEquals(CachedResultWeigher.UNSIZED,
			CachedResultWeigher.admittedWeight(CachedResultWeigher.UNSIZED, Long.MAX_VALUE));
		assertEquals(CachedResultWeigher.OVERSIZED, CachedResultWeigher.admittedWeight(96, 0));
	}

	/** Reproduces a four-GiB-to-zero narrowing through a real ArrayBitmap without allocating its backing store. */
	@Test
	@DisplayName("Refuses bitmap storage whose size model narrows to an integer")
	void shouldRefuseNarrowedBitmapSizing() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final CompositeIntArray backing = new NarrowedCompositeIntArray(1L << 32);
		final Bitmap large = new ArrayBitmap(backing);
		assertEquals(0, backing.getSizeInBytes());
		assertEquals(VMLayout.current().sizeOfObject(VMLayout.current().referenceSize()),
			large.getHeapSizeInBytes());
		for (final Bitmap bitmap : new Bitmap[]{large, new ArrayBitmap(10)}) {
			assertAll(
				() -> assertEquals(CachedResultWeigher.UNSIZED, CachedResultWeigher.measure(key, bitmap)),
				() -> assertEquals(CachedResultWeigher.UNSIZED,
					CachedResultWeigher.measure(key, bitmap, records(), null)),
				() -> assertEquals(CachedResultWeigher.UNSIZED,
					CachedResultWeigher.measure(key, bitmap, records(), records()))
			);
		}
	}

	/** Verifies all supported concrete representations against their retained graphs in both bitmap overloads. */
	@Test
	@DisplayName("Sizes each supported concrete bitmap representation")
	void shouldCoverSupportedBitmapRepresentations() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		for (final Bitmap bitmap : new Bitmap[]{EmptyBitmap.INSTANCE, new SingleRecordBitmap(10),
			new SortedArrayBitmap(10, 20), new BaseBitmap(10, 20), new TransactionalBitmap(10, 20)}) {
			assertCovered(key, new CachedBitmapResult(bitmap, 1), CachedResultWeigher.measure(key, bitmap));
			final ResolvedFilteredPriceRecords prices = records();
			assertCovered(key, new CachedPriceRecordsResult(bitmap, prices, null, 1),
				CachedResultWeigher.measure(key, bitmap, prices, null));
		}
	}

	/** Extra state on a subclass has no known size model even if its inherited size method returns a number. */
	@Test
	@DisplayName("Refuses unknown bitmap implementations before adding their size")
	void shouldRefuseUnknownBitmapSizing() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final Bitmap unknown = new SingleRecordBitmap(10) {
			/** Models a foreign implementation reporting the unsupported-size sentinel. */
			@Override
			public long getHeapSizeInBytes() {
				return CachedResultWeigher.UNSIZED;
			}
		};
		assertAll(
			() -> assertEquals(CachedResultWeigher.UNSIZED, CachedResultWeigher.measure(key, unknown)),
			() -> assertEquals(CachedResultWeigher.UNSIZED,
				CachedResultWeigher.measure(key, unknown, records(), null))
		);
	}

	/** Moves an unsupported decimal subclass through every retained numeric position independently. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"max", "threshold", "relativeFrequency", "rawMin", "rawMax"})
	@DisplayName("Refuses histogram payloads with an unsupported nested decimal")
	void shouldRefuseUnsupportedHistogramComponent(@Nonnull String position) {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final BigDecimal unknown = new RetainingDecimal("1");
		final CacheableHistogramContract.CacheableBucket bucket = new CacheableHistogramContract.CacheableBucket(
			position.equals("threshold") ? unknown : BigDecimal.ONE, 1,
			position.equals("relativeFrequency") ? unknown : BigDecimal.ONE);
		final CacheableHistogram histogram = new CacheableHistogram(
			new CacheableHistogramContract.CacheableBucket[]{bucket},
			position.equals("max") ? unknown : BigDecimal.TEN,
			position.equals("rawMin") ? unknown : BigDecimal.ONE,
			position.equals("rawMax") ? unknown : BigDecimal.TEN);
		final long charge = CachedResultWeigher.measure(key, histogram);
		assertEquals(CachedResultWeigher.UNSIZED, charge);
		assertEquals(CachedResultWeigher.UNSIZED, CachedResultWeigher.admittedWeight(charge, Long.MAX_VALUE));
	}

	/** Builds a compact doubling graph whose saturated key weight would overflow integer multiplication. */
	@Test
	@DisplayName("Multiplies saturated key weights in long arithmetic")
	void shouldChargeLargeKeysInLongArithmetic() {
		FormulaKey key = new ConstantFormula(new BaseBitmap(1)).getKey();
		for (int i = 0; i < 32; i++) {
			key = FormulaKey.of(new Shape(null, null, null, null), 1, new FormulaKey[]{key, key}, this);
		}
		assertEquals(Integer.MAX_VALUE, key.getWeight());
		final long charge = CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE);
		assertTrue(charge > Integer.MAX_VALUE);
		assertEquals(CachedResultWeigher.OVERSIZED, CachedResultWeigher.admittedWeight(charge, Long.MAX_VALUE));
	}

	/** Checks all payload shapes including both price arrays and a large, sparse SUM map against retained heap. */
	@Test
	@DisplayName("Overcharges every retained payload graph measured by JOL")
	void shouldCoverRetainedPayloadGraphs() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final BaseBitmap bitmap = new BaseBitmap(10, 20);
		assertCovered(key, new CachedBitmapResult(bitmap, 1), CachedResultWeigher.measure(key, bitmap));
		for (final PriceRecordContract record : new PriceRecordContract[]{
			new PriceRecord(1, 1, 10, 120, 100), new PriceRecordInnerRecordSpecific(1, 1, 10, 1, 120, 100),
			sum(1, 1), sum(2_000, 2_000), sum(10, 20_000)
		}) {
			final ResolvedFilteredPriceRecords prices = records(record);
			final ResolvedFilteredPriceRecords inner = records(new PriceRecord(2, 2, 20, 240, 200));
			final long charge = CachedResultWeigher.measure(key, bitmap, prices, inner);
			assertCovered(key, new CachedPriceRecordsResult(bitmap, prices, inner, 1), charge);
		}
		final CacheableHistogramContract histogram = histogram();
		assertCovered(key, new CachedHistogramResult(histogram, 1), CachedResultWeigher.measure(key, histogram));
		assertCovered(key, new CachedHistogramResult(CacheableHistogramContract.EMPTY, 1),
			CachedResultWeigher.measure(key, CacheableHistogramContract.EMPTY));
	}

	/** Checks that one entity's many component records alone exceed the per-entry cap. */
	@Test
	@DisplayName("Refuses a one-entity SUM payload whose retained heap exceeds the cap")
	void shouldRefuseLargeSumComponents() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final CachedPriceRecordsResult payload = prices(sum(2_000, 2_000));
		final long retained = JolHeapSize.ownedSize(payload,
			FilteredPriceRecords.SortingForm.ENTITY_PK, QueryPriceMode.WITH_TAX);
		assertTrue(retained > 10_000);
		final long charge = CachedResultWeigher.measure(key, payload.result(), payload.priceRecords(), null);
		assertTrue(charge >= retained);
		assertEquals(CachedResultWeigher.OVERSIZED, CachedResultWeigher.admittedWeight(charge, 1_000_000));
	}

	/** Verifies that allocated backing capacity counts even when the number of component entries stays fixed. */
	@Test
	@DisplayName("Charges component map capacity rather than entry count")
	void shouldChargeMapCapacity() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final long dense = CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, records(sum(10, 10)), null);
		final long sparse = CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, records(sum(10, 20_000)), null);
		assertTrue(sparse > dense + 100_000);
	}

	/** Ensures sizing finishes all array ordering before any value can be built and published. */
	@Test
	@DisplayName("Freezes both price arrays while measuring their retained graph")
	void shouldFreezeArraysDuringMeasurement() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final ResolvedFilteredPriceRecords first = records(
			new PriceRecord(2, 2, 20, 240, 200), new PriceRecord(1, 1, 10, 120, 100));
		final ResolvedFilteredPriceRecords second = records(
			new PriceRecord(4, 4, 20, 360, 300), new PriceRecord(3, 3, 10, 180, 150));
		assertTrue(CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, first, second) > 0);
		assertEquals(FilteredPriceRecords.SortingForm.ENTITY_PK, first.getSortingForm());
		assertEquals(FilteredPriceRecords.SortingForm.ENTITY_PK, second.getSortingForm());
	}

	/** Checks refusal of index-retaining lazy arrays and foreign records in either output. */
	@Test
	@DisplayName("Refuses non-resolved and unknown price contents")
	void shouldRefuseUnknownRetainedShapes() {
		final FormulaKey key = new ConstantFormula(new BaseBitmap(10)).getKey();
		final NonResolvedFilteredPriceRecords lazy = new NonResolvedFilteredPriceRecords(
			new PriceRecordContract[0], EmptyBitmap.INSTANCE, new PriceListAndCurrencyPriceIndex<?>[0]);
		assertEquals(CachedResultWeigher.UNSIZED,
			CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, lazy, null));
		assertEquals(CachedResultWeigher.UNSIZED,
			CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, records(), lazy));
		assertEquals(CachedResultWeigher.UNSIZED,
			CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, records(new UnknownRecord()), null));
		assertEquals(CachedResultWeigher.UNSIZED,
			CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE, records(), records(new UnknownRecord())));
		final CacheableHistogram invalid = new CacheableHistogram(histogram().getBuckets(),
			histogram().getMax(), "unsupported", null);
		assertEquals(CachedResultWeigher.UNSIZED, CachedResultWeigher.measure(key, invalid));
	}

	/** Calibrates key units against producer lists, exact content, value lists, Unicode strings and side-key graphs. */
	@Test
	@DisplayName("Overcharges detached keys of different retained shapes")
	void shouldCoverRetainedKeyGraphs() {
		final List<FormulaKey> keys = new ArrayList<>();
		for (final int count : new int[]{2, 16, 256}) {
			final Bitmap[] operands = new Bitmap[count];
			for (int i = 0; i < count; i++) {
				operands[i] = new TransactionalBitmap(new BaseBitmap(i));
			}
			keys.add(new OrFormula(operands).getKey());
		}
		final int[] exact = new int[IdentityToken.CONTENT_LIMIT];
		for (int i = 0; i < exact.length; i++) {
			exact[i] = i;
		}
		keys.add(shape(null, IdentityToken.ofContent(exact), null, null));
		keys.add(shape(null, null, new ValueList("amount", "long-value".repeat(500)), null));
		keys.add(shape("\u0100".repeat(16_000), null, null, null));
		keys.add(shape("", null, null, keys.get(0)));
		keys.add(shape("typed argument", null, new ValueList(
			new java.math.BigDecimal("123456789012345678901234567890.0123456789")), keys.get(4)));
		FormulaKey sideGraph = keys.get(0);
		for (int i = 0; i < 100; i++) {
			sideGraph = shape(null, null, null, sideGraph);
		}
		keys.add(sideGraph);
		for (final FormulaKey key : keys) {
			assertCovered(key, new CachedBitmapResult(EmptyBitmap.INSTANCE, 1),
				CachedResultWeigher.measure(key, EmptyBitmap.INSTANCE));
		}
	}

	/** Measures the combined key/value graph, excluding only enum singletons, and reports the calibration ratio. */
	private static void assertCovered(@Nonnull FormulaKey key, @Nonnull CachedResult value, long charge) {
		final long measured = JolHeapSize.ownedSize(new Object[]{key, value},
			FilteredPriceRecords.SortingForm.ENTITY_PK, QueryPriceMode.WITH_TAX, TokenRole.DIRECT);
		System.out.println("Cache charge: weight=" + key.getWeight() + ", measured=" + measured
			+ ", charged=" + charge + ", ratio=" + (double) charge / measured);
		assertTrue(charge >= measured, "Retained graph needs " + measured + " bytes, charge is " + charge);
	}

	/** Builds a detached synthetic shape without adding a formula implementation. */
	@Nonnull
	private static FormulaKey shape(
		@Nullable String text, @Nullable IdentityToken token, @Nullable ValueList values, @Nullable FormulaKey side
	) {
		return FormulaKey.of(new Shape(text, token, values, side), 1, FormulaKey.NO_CHILDREN, new Object());
	}

	/**
	 * Detached key inputs for independent retained-shape calibration.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record Shape(@Nullable String text, @Nullable IdentityToken token,
		@Nullable ValueList values, @Nullable FormulaKey side) implements IdentityRecord {
		/** Returns the synthetic holder discriminator. */
		@Override public long classId() { return 1; }
		/** Includes every retained component. */
		@Override public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.text);
			accumulator.add(this.token);
			accumulator.add(this.values);
			accumulator.add(this.side);
		}
	}

	/**
	 * Models the existing integer-only backing-store size boundary while retaining only a small empty array.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class NarrowedCompositeIntArray extends CompositeIntArray {
		@Serial private static final long serialVersionUID = 1L;
		private final long backingBytes;

		/** Supplies a hypothetical retained backing-store footprint without allocating it. */
		private NarrowedCompositeIntArray(long backingBytes) {
			this.backingBytes = backingBytes;
		}

		/** Reproduces the narrowing performed by the existing composite-array size helper. */
		@Override
		public int getSizeInBytes() {
			return (int) this.backingBytes;
		}
	}

	/**
	 * Decimal subclass with additional retained storage unsupported by the exact-class size model.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class RetainingDecimal extends BigDecimal {
		@Serial private static final long serialVersionUID = 1L;
		private final byte[] retained = new byte[1024];

		/** Builds a valid numeric value carrying additional retained bytes. */
		private RetainingDecimal(@Nonnull String value) {
			super(value);
		}
	}

	/**
	 * Foreign price implementation that has no admitted size model.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record UnknownRecord() implements PriceRecordContract {
		/** Returns a synthetic internal id. */
		@Override public int internalPriceId() { return 1; }
		/** Returns a synthetic price id. */
		@Override public int priceId() { return 1; }
		/** Returns a synthetic entity id. */
		@Override public int entityPrimaryKey() { return 10; }
		/** Returns a synthetic taxed amount. */
		@Override public int priceWithTax() { return 120; }
		/** Returns a synthetic untaxed amount. */
		@Override public int priceWithoutTax() { return 100; }
		/** Returns no inner record id. */
		@Override public int innerRecordId() { return 0; }
		/** Reports no inner record specificity. */
		@Override public boolean isInnerRecordSpecific() { return false; }
		/** Rejects an operation the refusal test never needs. */
		@Override public boolean relatesTo(@Nonnull PriceRecordContract other) {
			throw new UnsupportedOperationException();
		}
		/** Orders by internal id. */
		@Override public int compareTo(@Nonnull PriceRecordContract other) {
			return Integer.compare(internalPriceId(), other.internalPriceId());
		}
	}
}
