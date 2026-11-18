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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.index.bitmap.*;
import io.evitadb.index.price.model.priceRecord.*;
import io.evitadb.utils.MemoryMeasuringConstants;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Computes complete entry charges before publication. Shared price records are charged in full, so their charge
 * remains sufficient after the originating index version is reclaimed. Unknown retained shapes are refused.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CachedResultWeigher {
	/** Refusal returned when a retained object has no supported size model. */
	public static final int UNSIZED = -1;
	/** Refusal returned when the charge exceeds the per-entry or integer limit or is below the entry overhead. */
	public static final int OVERSIZED = -2;
	/** Entry bookkeeping allowance, separate from the result wrapper and key. */
	private static final long ENTRY_OVERHEAD_BYTES = 96;
	/** Conservative detached-key allowance per retained weight unit. */
	private static final long KEY_WEIGHT_UNIT_BYTES = 128;
	/** Layout of the running VM, shared by all charge calculations. */
	private static final VMLayout LAYOUT = VMLayout.current();

	/** Prevents instantiation of this stateless calculator. */
	private CachedResultWeigher() {
	}

	/**
	 * Returns the complete bitmap entry charge using long arithmetic, or {@link #UNSIZED} for unsupported storage.
	 *
	 * @param key the detached formula key whose weight determines the key allowance
	 * @param bitmap the result whose concrete representation determines the retained storage charge
	 * @return the complete entry charge in bytes, or {@link #UNSIZED} for unsupported bitmap storage
	 */
	public static long measure(@Nonnull FormulaKey key, @Nonnull Bitmap bitmap) {
		final long bitmapBytes = measureBitmap(bitmap);
		if (bitmapBytes == UNSIZED) {
			return UNSIZED;
		}
		return baseCharge(key) + LAYOUT.sizeOfObject(LAYOUT.referenceSize() + Integer.BYTES) + bitmapBytes;
	}

	/**
	 * Orders and charges both price arrays, their holders, all records and SUM component-map capacities.
	 * Returns {@link #UNSIZED} for unsupported bitmap storage, non-resolved arrays, unknown records or maps.
	 *
	 * @param key the detached formula key whose weight determines the key allowance
	 * @param bitmap the result whose concrete representation determines the retained storage charge
	 * @param priceRecords the resolved price holder to charge in full; accessing its array fixes its order
	 * @param perInnerRecordPriceRecords the optional resolved per-inner-record holder, ordered and charged in full;
	 *     `null` adds no holder charge
	 * @return the complete entry charge in bytes, or {@link #UNSIZED} if any retained representation is unsupported
	 */
	public static long measure(
		@Nonnull FormulaKey key, @Nonnull Bitmap bitmap, @Nonnull FilteredPriceRecords priceRecords,
		@Nullable FilteredPriceRecords perInnerRecordPriceRecords
	) {
		final long bitmapBytes = measureBitmap(bitmap);
		final long prices = measureRecords(priceRecords);
		final long innerPrices = perInnerRecordPriceRecords == null ? 0 : measureRecords(perInnerRecordPriceRecords);
		if (bitmapBytes == UNSIZED || prices == UNSIZED || innerPrices == UNSIZED) {
			return UNSIZED;
		}
		return baseCharge(key) + LAYOUT.sizeOfObject(3L * LAYOUT.referenceSize() + Integer.BYTES)
			+ bitmapBytes + prices + innerPrices;
	}

	/**
	 * Returns the complete histogram entry charge, or {@link #UNSIZED} for a histogram class other than
	 * {@link CacheableHistogram} and the shared empty histogram, or for an unknown retained value type.
	 *
	 * @param key the detached formula key whose weight determines the key allowance
	 * @param histogram the retained histogram whose buckets and numeric values are included in the charge
	 * @return the complete entry charge in bytes, or {@link #UNSIZED} for an unsupported histogram or value type
	 */
	public static long measure(@Nonnull FormulaKey key, @Nonnull CacheableHistogramContract histogram) {
		long bytes = baseCharge(key) + LAYOUT.sizeOfObject(LAYOUT.referenceSize() + Integer.BYTES);
		if (histogram == CacheableHistogramContract.EMPTY) {
			return bytes + LAYOUT.sizeOfObject(0);
		}
		if (histogram.getClass() != CacheableHistogram.class) {
			return UNSIZED;
		}
		final long max = measureValue(histogram.getMax());
		final long rawMin = measureValue(histogram.getRawMin());
		final long rawMax = measureValue(histogram.getRawMax());
		if (max == UNSIZED || rawMin == UNSIZED || rawMax == UNSIZED) {
			return UNSIZED;
		}
		bytes += LAYOUT.sizeOfObject(4L * LAYOUT.referenceSize() + Integer.BYTES)
			+ max + rawMin + rawMax;
		final CacheableBucket[] buckets = histogram.getBuckets();
		bytes += LAYOUT.sizeOfArray(buckets.length, LAYOUT.referenceSize());
		for (final CacheableBucket bucket : buckets) {
			final long threshold = measureValue(bucket.threshold());
			final long relativeFrequency = measureValue(bucket.relativeFrequency());
			if (threshold == UNSIZED || relativeFrequency == UNSIZED) {
				return UNSIZED;
			}
			bytes += LAYOUT.sizeOfObject(2L * LAYOUT.referenceSize() + Integer.BYTES)
				+ threshold + relativeFrequency;
		}
		return bytes;
	}

	/**
	 * Converts a complete charge to a cache weight, refusing entries above one percent of the budget or the integer
	 * range, and a charge below the entry overhead every complete charge includes. Refusals are values, never
	 * saturated positive weights. An unsized payload keeps its refusal reason.
	 *
	 * @param charge the complete entry estimate in bytes, or {@link #UNSIZED} when measurement was unsupported
	 * @param budget the cache budget in bytes against which the one-percent per-entry cap is checked
	 * @return the accepted byte charge as a positive weight, {@link #UNSIZED} for an unsupported payload, or
	 *     {@link #OVERSIZED} when the charge is below the entry overhead or exceeds either limit
	 */
	public static int admittedWeight(long charge, long budget) {
		if (charge == UNSIZED) {
			return UNSIZED;
		}
		if (charge < ENTRY_OVERHEAD_BYTES || charge > budget / 100 || charge > Integer.MAX_VALUE) {
			return OVERSIZED;
		}
		return (int) charge;
	}

	/**
	 * Charges only concrete bitmap representations whose allocated storage is measured entirely in long arithmetic.
	 * ArrayBitmap is refused because its composite-array helper narrows the backing footprint to int; subclasses
	 * and foreign implementations are refused because their additional retained storage has no known size model.
	 * Roaring-backed representations charge shared containers in full so the charge survives predecessor reclamation.
	 *
	 * @param bitmap the retained bitmap whose exact runtime class must have a supported size model
	 * @return the bitmap storage charge in bytes, or {@link #UNSIZED} for an unsupported class or negative estimate
	 */
	private static long measureBitmap(@Nonnull Bitmap bitmap) {
		final Class<?> bitmapType = bitmap.getClass();
		if (bitmapType != BaseBitmap.class && bitmapType != TransactionalBitmap.class
			&& bitmapType != SortedArrayBitmap.class && bitmapType != SingleRecordBitmap.class
			&& bitmapType != EmptyBitmap.class) {
			return UNSIZED;
		}
		final long bytes = bitmap.getHeapSizeInBytes();
		return bytes < 0 ? UNSIZED : bytes;
	}

	/**
	 * Charges the detached key and entry bookkeeping without narrowing the multiplication.
	 *
	 * @param key the detached key whose weight units are converted to a conservative byte allowance
	 * @return the entry bookkeeping and key allowance in bytes, excluding the result wrapper and payload
	 */
	private static long baseCharge(@Nonnull FormulaKey key) {
		return ENTRY_OVERHEAD_BYTES + (long) key.getWeight() * KEY_WEIGHT_UNIT_BYTES;
	}

	/**
	 * Orders an array and charges its full retained contents, refusing lazy holders.
	 *
	 * @param records the resolved holder whose array is ordered in place on access and whose records are charged
	 * @return the holder, array and record charge in bytes, or {@link #UNSIZED} for a lazy holder or unknown record
	 */
	private static long measureRecords(@Nonnull FilteredPriceRecords records) {
		if (records.getClass() != ResolvedFilteredPriceRecords.class) {
			return UNSIZED;
		}
		final PriceRecordContract[] array = ((ResolvedFilteredPriceRecords) records).getPriceRecords();
		long bytes = LAYOUT.sizeOfObject(2L * LAYOUT.referenceSize())
			+ LAYOUT.sizeOfArray(array.length, LAYOUT.referenceSize());
		for (final PriceRecordContract record : array) {
			final long recordBytes = measureRecord(record);
			if (recordBytes == UNSIZED) {
				return UNSIZED;
			}
			bytes += recordBytes;
		}
		return bytes;
	}

	/**
	 * Charges a known record and, for SUM, the allocated map capacity and every component record.
	 *
	 * @param record the retained price record to charge, including SUM components; `null` is unsupported
	 * @return the record and component-storage charge in bytes, or {@link #UNSIZED} for null or unknown shapes
	 */
	private static long measureRecord(@Nullable PriceRecordContract record) {
		if (record == null) {
			return UNSIZED;
		} else if (record.getClass() == PriceRecord.class) {
			return LAYOUT.sizeOfObject(5L * Integer.BYTES);
		} else if (record.getClass() == PriceRecordInnerRecordSpecific.class) {
			return LAYOUT.sizeOfObject(6L * Integer.BYTES);
		} else if (record.getClass() == CumulatedVirtualPriceRecord.class) {
			final CumulatedVirtualPriceRecord sum = (CumulatedVirtualPriceRecord) record;
			if (sum.innerRecordPrices().getClass() != IntObjectHashMap.class) {
				return UNSIZED;
			}
			final IntObjectHashMap<PriceRecordContract> map = (IntObjectHashMap<PriceRecordContract>)
				sum.innerRecordPrices();
			long bytes = LAYOUT.sizeOfObject(2L * Integer.BYTES + 2L * LAYOUT.referenceSize())
				+ LAYOUT.sizeOfObject(2L * LAYOUT.referenceSize() + 5L * Integer.BYTES + Double.BYTES)
				+ LAYOUT.sizeOfArray(map.keys.length, Integer.BYTES)
				+ LAYOUT.sizeOfArray(map.values.length, LAYOUT.referenceSize());
			for (final IntObjectCursor<PriceRecordContract> component : map) {
				final long componentBytes = measureRecord(component.value);
				if (componentBytes == UNSIZED) {
					return UNSIZED;
				}
				bytes += componentBytes;
			}
			return bytes;
		}
		return UNSIZED;
	}

	/**
	 * Charges immutable numeric histogram values, including decimal magnitudes and their rendering memo.
	 *
	 * @param value the retained numeric value to charge; `null` represents no retained value
	 * @return the numeric storage charge in bytes, `0` for null, or {@link #UNSIZED} for an unsupported value type
	 */
	private static long measureValue(@Nullable Object value) {
		if (value == null) {
			return 0;
		} else if (value.getClass() == BigDecimal.class) {
			final BigDecimal decimal = (BigDecimal) value;
			return LAYOUT.sizeOfObject(MemoryMeasuringConstants.BIG_DECIMAL_SIZE)
				+ LAYOUT.sizeOfObject(MemoryMeasuringConstants.BIG_INTEGER)
				+ LAYOUT.sizeOfArray((int) ((decimal.unscaledValue().bitLength() + 31L) / 32L), Integer.BYTES)
				// String sizing stays in long arithmetic, including the UTF-16 backing-array allowance.
				+ LAYOUT.sizeOfObject(LAYOUT.referenceSize() + Integer.BYTES + 2L * Byte.BYTES)
				+ LAYOUT.sizeOfArray(decimal.toString().length(), Character.BYTES);
		} else if (value instanceof Byte || value instanceof Short || value instanceof Integer
			|| value instanceof Long || value instanceof Float || value instanceof Double) {
			return LAYOUT.sizeOfObject(Long.BYTES);
		}
		return UNSIZED;
	}
}
