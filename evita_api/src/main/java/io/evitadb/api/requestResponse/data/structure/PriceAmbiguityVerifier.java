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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The one check of the rule that an entity has at most one valid price at any moment per group of prices that compete
 * for its price for sale. A group is one price list, currency and inner record - a missing inner record id counting as
 * `0`, as in the price for sale computation - and one price list and currency for an entity without inner record
 * handling, where the inner record id has no meaning. Two prices are valid at a common moment when their validities
 * share at least one moment, the bounds included, and a price without validity is valid at every moment.
 *
 * Only pairs with at least one touched price - one that the change of the entity adds, revives, or moves to another
 * validity or inner record - are checked, so an entity that already holds an ambiguous pair stays updatable as long
 * as the pair is left alone. Both entity builders apply the check to the state their change set leads to and the
 * server applies it to the state an entity upsert leads to, so a change gets the same verdict whichever way it arrives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PriceAmbiguityVerifier {
	/**
	 * Orders prices by the group they compete in for the price for sale - price list, currency and inner record, a
	 * missing inner record counting as inner record `0` as in the price for sale computation.
	 */
	private static final Comparator<PriceContract> GROUP_COMPARATOR = (first, second) -> {
		final int byPriceListAndCurrency = compareByPriceListAndCurrency(first, second);
		if (byPriceListAndCurrency != 0) {
			return byPriceListAndCurrency;
		}
		return Integer.compare(innerRecordKey(first), innerRecordKey(second));
	};
	/**
	 * Orders prices by the group they compete in for the price for sale of an entity without inner record handling -
	 * price list and currency, the inner record id having no meaning there.
	 */
	private static final Comparator<PriceContract> GROUP_WITHOUT_INNER_RECORD_COMPARATOR =
		PriceAmbiguityVerifier::compareByPriceListAndCurrency;
	/**
	 * Orders prices by {@link #GROUP_COMPARATOR}, then by the first moment of their validity and then by price id, so
	 * that a price named twice ends up next to itself.
	 */
	private static final Comparator<PriceContract> SWEEP_COMPARATOR = (first, second) -> {
		final int byGroup = GROUP_COMPARATOR.compare(first, second);
		return byGroup == 0 ? compareByValidFromAndPriceId(first, second) : byGroup;
	};
	/**
	 * Orders prices by {@link #GROUP_WITHOUT_INNER_RECORD_COMPARATOR}, then by the first moment of their validity and
	 * then by price id, so that a price named twice ends up next to itself.
	 */
	private static final Comparator<PriceContract> SWEEP_WITHOUT_INNER_RECORD_COMPARATOR = (first, second) -> {
		final int byGroup = compareByPriceListAndCurrency(first, second);
		return byGroup == 0 ? compareByValidFromAndPriceId(first, second) : byGroup;
	};

	private PriceAmbiguityVerifier() {
		// utility class
	}

	/**
	 * Verifies that no touched price competes with another existing price of its group for the price for sale - see
	 * the class documentation for the rule.
	 *
	 * The touched prices are sorted by their group and by the first moment of their validity, which lets one pass over
	 * them find an overlap among them: a touched price overlaps an earlier one of its group exactly when its validity
	 * starts no later than the latest end seen in the group so far. Once no two touched prices of a group overlap,
	 * their validities follow one another, so an untouched price overlaps a touched one of its group exactly when it
	 * overlaps the last touched price that starts no later than the untouched price ends - found by one binary search.
	 * The cost is `O(k log k)` comparisons to sort `k` touched prices plus `O(n log k)` comparisons for `n` prices,
	 * whatever the number of prices in one group, and no allocation but the temporary array the sort may need.
	 *
	 * @param prices              all prices of the entity in the state the change leads to - removed prices are
	 *                            skipped - in the first `priceCount` slots; left unchanged
	 * @param priceCount          number of the prices
	 * @param touched             the touched prices that exist in that state in the first `touchedCount` slots, a
	 *                            price possibly named more than once; the slots are reordered
	 * @param touchedCount        number of the touched prices
	 * @param innerRecordHandling inner record handling of the entity in that state
	 * @throws AmbiguousPriceException when a touched price competes with another price of its group
	 */
	public static void verifyPricesNotAmbiguous(
		@Nonnull PriceContract[] prices,
		int priceCount,
		@Nonnull PriceContract[] touched,
		int touchedCount,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	) {
		if (touchedCount == 0) {
			return;
		}
		final boolean withoutInnerRecord = innerRecordHandling == PriceInnerRecordHandling.NONE;
		final Comparator<PriceContract> groupComparator = withoutInnerRecord ?
			GROUP_WITHOUT_INNER_RECORD_COMPARATOR : GROUP_COMPARATOR;
		Arrays.sort(
			touched, 0, touchedCount,
			withoutInnerRecord ? SWEEP_WITHOUT_INNER_RECORD_COMPARATOR : SWEEP_COMPARATOR
		);
		final int distinctCount = verifyTouchedPricesNotAmbiguous(touched, touchedCount, groupComparator);
		for (int i = 0; i < priceCount; i++) {
			final PriceContract price = prices[i];
			if (price.exists()) {
				verifyPriceNotAmbiguous(price, touched, distinctCount, groupComparator);
			}
		}
	}

	/**
	 * Verifies that no two touched prices of one group overlap and removes the repeated ones, keeping the order.
	 *
	 * @param touched         the touched prices sorted by group, first moment of validity and price id
	 * @param touchedCount    number of the touched prices
	 * @param groupComparator orders prices by their group and returns zero for prices of one group
	 * @return number of the distinct touched prices now in the first slots
	 * @throws AmbiguousPriceException when two touched prices of one group overlap
	 */
	private static int verifyTouchedPricesNotAmbiguous(
		@Nonnull PriceContract[] touched,
		int touchedCount,
		@Nonnull Comparator<PriceContract> groupComparator
	) {
		int distinctCount = 0;
		// the price of the group seen so far whose validity ends last
		PriceContract latestEnding = null;
		long latestEnd = Long.MIN_VALUE;
		for (int i = 0; i < touchedCount; i++) {
			final PriceContract price = touched[i];
			if (latestEnding != null && groupComparator.compare(touched[distinctCount - 1], price) == 0) {
				if (touched[distinctCount - 1].priceId() == price.priceId()) {
					// the very same price named once more
					continue;
				}
				if (validFrom(price) <= latestEnd) {
					throw new AmbiguousPriceException(latestEnding, price);
				}
			} else {
				// the first price of a group
				latestEnding = null;
			}
			final long validTo = validTo(price);
			if (latestEnding == null || validTo > latestEnd) {
				latestEnding = price;
				latestEnd = validTo;
			}
			touched[distinctCount++] = price;
		}
		return distinctCount;
	}

	/**
	 * Verifies that the price does not overlap a touched price of its group other than itself. The touched prices of
	 * one group follow one another, so the only candidate is the last one that starts no later than the price ends.
	 *
	 * @param price           an existing price of the entity
	 * @param touched         the distinct touched prices sorted by group and first moment of validity, no two of one
	 *                        group overlapping
	 * @param touchedCount    number of the distinct touched prices
	 * @param groupComparator orders prices by their group and returns zero for prices of one group
	 * @throws AmbiguousPriceException when the price overlaps a touched price of its group
	 */
	private static void verifyPriceNotAmbiguous(
		@Nonnull PriceContract price,
		@Nonnull PriceContract[] touched,
		int touchedCount,
		@Nonnull Comparator<PriceContract> groupComparator
	) {
		final long priceValidTo = validTo(price);
		// count the touched prices of earlier groups and those of the group of the price starting no later than it ends
		int low = 0;
		int high = touchedCount;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			final int byGroup = groupComparator.compare(touched[middle], price);
			if (byGroup < 0 || (byGroup == 0 && validFrom(touched[middle]) <= priceValidTo)) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		if (low > 0) {
			final PriceContract candidate = touched[low - 1];
			if (candidate.priceId() != price.priceId() &&
				groupComparator.compare(candidate, price) == 0 &&
				validTo(candidate) >= validFrom(price)) {
				throw new AmbiguousPriceException(price, candidate);
			}
		}
	}

	/**
	 * Compares two prices by their price list and then by their currency.
	 *
	 * @param first  the first price
	 * @param second the second price
	 * @return the result of the comparison
	 */
	private static int compareByPriceListAndCurrency(@Nonnull PriceContract first, @Nonnull PriceContract second) {
		final int byPriceList = first.priceList().compareTo(second.priceList());
		if (byPriceList != 0) {
			return byPriceList;
		}
		return first.currency().getCurrencyCode().compareTo(second.currency().getCurrencyCode());
	}

	/**
	 * Compares two prices of one group by the first moment of their validity and then by their price id.
	 *
	 * @param first  the first price
	 * @param second the second price
	 * @return the result of the comparison
	 */
	private static int compareByValidFromAndPriceId(@Nonnull PriceContract first, @Nonnull PriceContract second) {
		final int byValidFrom = Long.compare(validFrom(first), validFrom(second));
		return byValidFrom == 0 ? Integer.compare(first.priceId(), second.priceId()) : byValidFrom;
	}

	/**
	 * Returns the inner record id of the price, `0` for a price without one.
	 *
	 * @param price the price
	 * @return the inner record id or `0`
	 */
	private static int innerRecordKey(@Nonnull PriceContract price) {
		final Integer innerRecordId = price.innerRecordId();
		return innerRecordId == null ? 0 : innerRecordId;
	}

	/**
	 * Returns the first moment the price is valid at in the form {@link DateTimeRange} compares by, the lowest value
	 * for a price without validity.
	 *
	 * @param price the price
	 * @return the first moment of the validity
	 */
	private static long validFrom(@Nonnull PriceContract price) {
		final DateTimeRange validity = price.validity();
		return validity == null ? Long.MIN_VALUE : validity.getFrom();
	}

	/**
	 * Returns the last moment the price is valid at in the form {@link DateTimeRange} compares by, the highest value
	 * for a price without validity.
	 *
	 * @param price the price
	 * @return the last moment of the validity
	 */
	private static long validTo(@Nonnull PriceContract price) {
		final DateTimeRange validity = price.validity();
		return validity == null ? Long.MAX_VALUE : validity.getTo();
	}

}
