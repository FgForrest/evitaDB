/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.comparator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * This comparator compares two strings with respect to national characters.
 *
 * When constructed with a {@link Locale}, ordering decisions are served by the shared
 * {@link CollationKeyCache}: both operands resolve to their {@link CollationKey} byte
 * form (computed at most once per distinct hot value, then reused) and are compared as unsigned
 * bytes, which yields exactly the same total order as {@link Collator#compare(String, String)} for
 * that locale at a fraction of the CPU and allocation cost. This path dominates localized-attribute
 * index maintenance, because both hot B+ tree implementations (the front-coded bucket columns and
 * the sort index's order-statistic tree) funnel every ordering decision through this class.
 *
 * When constructed with an explicit {@link Collator} (custom strength, decomposition or rules), the
 * comparator delegates directly to {@link Collator#compare(String, String)} - the shared cache
 * holds keys computed under the locale's default configuration and must not serve an order defined
 * by a different one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class LocalizedStringComparator implements Comparator<String>, Serializable {
	@Serial private static final long serialVersionUID = 8102561596057708303L;
	/**
	 * Collator defining the target order; used directly whenever {@link #cache} is null.
	 */
	@Nonnull private final Collator collator;
	/**
	 * Shared per-locale collation-key cache; null when the comparator was created with a custom
	 * {@link Collator} or when the cache is disabled by configuration (see
	 * {@link CollationKeyCache#forLocale(Locale)}). Resolved from a static registry and therefore
	 * deliberately not serialized.
	 */
	@Nullable private final CollationKeyCache cache;

	/**
	 * Creates a comparator ordering strings by the default collation rules of `locale`, serving
	 * comparisons from the shared collation-key cache when it is enabled.
	 *
	 * @param locale locale whose collation rules define the order
	 */
	public LocalizedStringComparator(@Nonnull Locale locale) {
		this.collator = Collator.getInstance(locale);
		this.cache = CollationKeyCache.forLocale(locale);
	}

	/**
	 * Creates a comparator delegating every comparison to the given (possibly customized)
	 * `collator`; the shared collation-key cache is bypassed on this path.
	 *
	 * @param collator collator defining the order
	 */
	public LocalizedStringComparator(@Nonnull Collator collator) {
		this.collator = collator;
		this.cache = null;
	}

	@Override
	public int compare(String o1, String o2) {
		//noinspection StringEquality
		if (o1 == o2) {
			// identity covers the frequent same-instance probe re-comparison in B+ tree descents
			return 0;
		}
		final CollationKeyCache cache = this.cache;
		if (cache == null) {
			return this.collator.compare(o1, o2);
		}
		return Arrays.compareUnsigned(cache.keyFor(o1), cache.keyFor(o2));
	}

}
