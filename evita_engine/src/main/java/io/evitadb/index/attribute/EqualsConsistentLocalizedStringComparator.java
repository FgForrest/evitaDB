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

package io.evitadb.index.attribute;

import io.evitadb.comparator.LocalizedStringComparator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * National collation order for a localized `String` INDEX KEY, made a total order consistent with
 * {@link String#equals} by deciding the ties the collation leaves open.
 *
 * <h3>Why the plain collation order will not do here</h3>
 *
 * A {@link Collator} at its default strength deliberately equates strings that are not `equals` — a zero-width
 * space, a zero-width joiner, a directional mark and control characters all vanish from the comparison. That is
 * correct for *ordering* text and wrong for *identifying* a bucket: a value tree ordered by it holds one bucket
 * for two such strings, while every structure keyed by `equals` keeps them apart. The
 * {@link io.evitadb.index.cardinality.AttributeCardinalityIndex} that ref-counts contributions to those very
 * buckets is such a structure, so the two disagreed about what "the same value" is and the counter could drop an
 * entry another owner still needed. {@link FilterIndex#foldOntoDistinctIndexKeys} relies on the same agreement
 * when it folds an array attribute onto the keys it actually addresses.
 *
 * <h3>Why the tie-break belongs here and NOT in {@link LocalizedStringComparator}</h3>
 *
 * {@link LocalizedStringComparator} is contractually "exactly the same total order as
 * {@link Collator#compare(String, String)} for that locale" — its whole purpose is to be a cached collator, and
 * it is tested as one: canonically equivalent NFC and NFD forms must compare equal there, and a caller that
 * supplies a `PRIMARY`-strength collator must have its deliberate equalities honoured. A tie-break would break
 * all of that. It is sound HERE because an index key space is narrower than a general string: every value
 * reaching this comparator has already been through `FilterIndex#getNormalizer`'s (or
 * `SortIndex#createNormalizerFor`'s) Unicode NFD folding, so two canonically equivalent strings are the same
 * `String` by the time they arrive and the tie-break can never separate them. What it separates is exactly what
 * the normalizer left distinct and only the collation merged.
 *
 * <h3>What it does not change</h3>
 *
 * The tie-break is reached only where the collation returned zero, so no ordering decision the collation
 * actually makes is disturbed — national ordering (`h` before `ch`, `c` before `č`) is untouched. It was
 * measured before being adopted: on a 100k-value localized tree the added comparison is indistinguishable from
 * noise on both a hit and a miss. Raising the collator's strength to {@link Collator#IDENTICAL} would fold the
 * distinction into the cached collation key instead, but costs ~32% more memory per cached key AND makes the
 * stored order depend on the JDK's collation implementation, where {@link String#compareTo}'s UTF-16 code-unit
 * order cannot shift under an upgrade.
 *
 * <h3>Both index roles must use this, not one of them</h3>
 *
 * {@link FilterIndex} and {@link SortIndex} share one value tree per attribute, so a tree built under one order
 * and searched under another would mis-place every key. Both derive their comparator from this class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EqualsConsistentLocalizedStringComparator implements Comparator<String>, Serializable {
	@Serial private static final long serialVersionUID = -6094735266958543761L;

	/**
	 * The national order being refined — a cached collator for the target locale.
	 */
	@Nonnull private final LocalizedStringComparator collating;

	/**
	 * Creates the index-key order for `locale`: its default collation rules, with ties decided by
	 * {@link String#compareTo}.
	 *
	 * @param locale locale whose collation rules define the order
	 */
	public EqualsConsistentLocalizedStringComparator(@Nonnull Locale locale) {
		this.collating = new LocalizedStringComparator(locale);
	}

	@Override
	public int compare(String o1, String o2) {
		//noinspection StringEquality
		if (o1 == o2) {
			// identity covers the frequent same-instance probe re-comparison in B+ tree descents, and short
			// circuits the tie-break that would otherwise re-scan the string against itself
			return 0;
		}
		final int collated = this.collating.compare(o1, o2);
		return collated != 0 ? collated : o1.compareTo(o2);
	}

}
