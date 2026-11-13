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

import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.*;
import io.evitadb.exception.GenericEvitaInternalError;
import java.util.PrimitiveIterator.OfInt;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compares cached tuples by their semantic contents, independently of bitmap representation and object identity.
 * Selling-price arrays retain their encounter order within each entity because termination consumers keep the
 * first price for an inner record. Only the histogram-only side-output ignores within-entity order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CachedResultEquivalence {
	/** Prevents instantiation of this stateless comparator. */
	private CachedResultEquivalence() {
	}

	/** Compares complete result tuples, ignoring their bookkeeping weights. */
	public static boolean equivalent(@Nonnull CachedResult left, @Nonnull CachedResult right) {
		if (left instanceof CachedBitmapResult first) {
			return right instanceof CachedBitmapResult second && equivalentBitmaps(first.result(), second.result());
		} else if (left instanceof CachedPriceRecordsResult first) {
			return right instanceof CachedPriceRecordsResult second
				&& equivalentBitmaps(first.result(), second.result())
				&& equivalentOrderedPrices(first.priceRecords(), second.priceRecords())
				&& equivalentHistogramPrices(first.perInnerRecordPriceRecords(), second.perInnerRecordPriceRecords());
		} else if (left instanceof CachedHistogramResult first) {
			return right instanceof CachedHistogramResult second
				&& (first.histogram() == second.histogram()
				|| first.histogram().getClass() == CacheableHistogram.class
				&& second.histogram().getClass() == CacheableHistogram.class
				&& first.histogram().equals(second.histogram()));
		}
		throw new GenericEvitaInternalError("Unsupported cached result type.");
	}

	/** Compares size and membership in both directions, including representations that retain duplicate ids. */
	private static boolean equivalentBitmaps(@Nonnull Bitmap left, @Nonnull Bitmap right) {
		if (left.size() != right.size()) {
			return false;
		}
		final OfInt iterator = left.iterator();
		while (iterator.hasNext()) {
			if (!right.contains(iterator.nextInt())) {
				return false;
			}
		}
		// Equal reported sizes do not imply equal distinct counts for duplicate-bearing representations.
		final OfInt reverseIterator = right.iterator();
		while (reverseIterator.hasNext()) {
			if (!left.contains(reverseIterator.nextInt())) {
				return false;
			}
		}
		return true;
	}

	/** Compares deterministic selling-price sequences, preserving the order observed by first-wins consumers. */
	private static boolean equivalentOrderedPrices(
		@Nonnull ResolvedFilteredPriceRecords left, @Nonnull ResolvedFilteredPriceRecords right
	) {
		final PriceRecordContract[] first = left.getPriceRecords();
		final PriceRecordContract[] second = right.getPriceRecords();
		if (first.length != second.length) {
			return false;
		}
		for (int i = 0; i < first.length; i++) {
			if (!equivalentRecord(first[i], second[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Compares the optional histogram-only side-output as a multiset within each entity, preserving duplicates.
	 * LowestPriceTerminationFormula drains its inner-record hash map in unspecified order. The only consumers are
	 * the histogram merge (including SelectionFormula's forwarding path) and PriceHistogramComputer, which copies
	 * all contributions and sorts them by amount before bucketing; none selects a first record from this array.
	 */
	private static boolean equivalentHistogramPrices(
		@Nullable ResolvedFilteredPriceRecords left, @Nullable ResolvedFilteredPriceRecords right
	) {
		if (left == null || right == null) {
			return left == right;
		}
		final PriceRecordContract[] first = left.getPriceRecords();
		final PriceRecordContract[] second = right.getPriceRecords();
		if (first.length != second.length) {
			return false;
		}
		int start = 0;
		while (start < first.length) {
			final int entity = first[start].entityPrimaryKey();
			if (second[start].entityPrimaryKey() != entity) {
				return false;
			}
			int end = start + 1;
			while (end < first.length && first[end].entityPrimaryKey() == entity) {
				end++;
			}
			if (second[end - 1].entityPrimaryKey() != entity
				|| end < second.length && second[end].entityPrimaryKey() == entity) {
				return false;
			}
			final boolean[] matched = new boolean[end - start];
			for (int i = start; i < end; i++) {
				boolean found = false;
				for (int j = start; j < end; j++) {
					if (!matched[j - start] && equivalentRecord(first[i], second[j])) {
						matched[j - start] = true;
						found = true;
						break;
					}
				}
				if (!found) {
					return false;
				}
			}
			start = end;
		}
		return true;
	}

	/** Compares each supported concrete price tuple, including all SUM components by their map keys. */
	static boolean equivalentRecord(@Nonnull PriceRecordContract left, @Nonnull PriceRecordContract right) {
		if (left.getClass() != right.getClass()) {
			return false;
		} else if (left.getClass() == CumulatedVirtualPriceRecord.class) {
			final CumulatedVirtualPriceRecord first = (CumulatedVirtualPriceRecord) left;
			final CumulatedVirtualPriceRecord second = (CumulatedVirtualPriceRecord) right;
			if (first.entityPrimaryKey() != second.entityPrimaryKey() || first.price() != second.price()
				|| first.priceMode() != second.priceMode()
				|| first.innerRecordPrices().size() != second.innerRecordPrices().size()) {
				return false;
			}
			for (final IntObjectCursor<PriceRecordContract> component : first.innerRecordPrices()) {
				if (!second.innerRecordPrices().containsKey(component.key)
					|| !equivalentRecord(component.value, second.innerRecordPrices().get(component.key))) {
					return false;
				}
			}
			return true;
		} else if (left.getClass() == PriceRecord.class || left.getClass() == PriceRecordInnerRecordSpecific.class) {
			return left.internalPriceId() == right.internalPriceId() && left.priceId() == right.priceId()
				&& left.entityPrimaryKey() == right.entityPrimaryKey() && left.priceWithTax() == right.priceWithTax()
				&& left.priceWithoutTax() == right.priceWithoutTax() && left.innerRecordId() == right.innerRecordId();
		}
		throw new GenericEvitaInternalError("Unsupported cached price record type.");
	}
}
