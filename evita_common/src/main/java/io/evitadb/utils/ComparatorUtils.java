/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.utils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Comparator utils contains utility method that can be used in {@link java.util.Comparator} logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ComparatorUtils {

	private ComparatorUtils() {
	}

	/**
	 * Compares two optional locales and when result is a tie, uses additional logic to break the tie.
	 */
	public static int compareLocale(@Nullable Locale locale, @Nullable Locale otherLocale, @Nonnull IntSupplier tieBreakingResult) {
		final int localeResult;
		if (locale == null && otherLocale == null) {
			localeResult = 0;
		} else if (locale != null && otherLocale == null) {
			localeResult = -1;
		} else if (locale == null) {
			localeResult = 1;
		} else {
			localeResult = Objects.compare(locale, otherLocale, Comparator.comparing(Locale::toString));
		}
		if (localeResult == 0) {
			return tieBreakingResult.getAsInt();
		} else {
			return localeResult;
		}
	}

	public static Comparator<Locale> localeComparator() {
		return (l1, l2) -> compareLocale(l1, l2, () -> 0);
	}

	/**
	 * Compares two optional currencies and when result is a tie, uses additional logic to break the tie.
	 */
	public static int compareCurrency(@Nullable Currency currency, @Nullable Currency otherCurrency, @Nonnull IntSupplier tieBreakingResult) {
		final int currencyResult;
		if (currency == null && otherCurrency == null) {
			currencyResult = 0;
		} else if (currency != null && otherCurrency == null) {
			currencyResult = -1;
		} else if (currency == null) {
			currencyResult = 1;
		} else {
			currencyResult = Objects.compare(currency, otherCurrency, Comparator.comparing(Currency::toString));
		}
		if (currencyResult == 0) {
			return tieBreakingResult.getAsInt();
		} else {
			return currencyResult;
		}
	}

	public static Comparator<Currency> currencyComparator() {
		return (l1, l2) -> compareCurrency(l1, l2, () -> 0);
	}

}
