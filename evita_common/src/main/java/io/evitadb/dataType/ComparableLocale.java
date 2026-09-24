/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Comparable wrapper for {@link Locale} object. This class is used to provide {@link Comparable} interface for
 * {@link Locale} object, which is not {@link Comparable} by default.
 *
 * <h3>The order is consistent with equals, and must stay that way</h3>
 *
 * Instances of this class are used as INDEX KEYS — an attribute declared as {@link Locale} is normalized to this
 * type before it reaches the filter and sort value trees, which identify one entry per key by this very
 * comparator. An order that equates two instances the index's other bookkeeping considers distinct therefore
 * merges two entries into one, and the removal of the second fails an internal premise (see
 * `documentation/adr/2026-09-21-cardinality-counter-normalized-keys.md`).
 *
 * Ordering by {@link Locale#toLanguageTag()} alone is NOT consistent with equals: an **ill-formed** variant is
 * dropped from the tag, so {@code new Locale("en","US","ill!formed")} and {@code new Locale("en","US")} both tag
 * as {@code en-US} while {@link Locale#equals} keeps them apart. (A well-formed variant survives as
 * {@code x-lvariant-…} and does not collide.) Ties are therefore broken on {@link Locale#toString()}, which
 * renders every component {@link Locale#equals} compares.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ComparableLocale implements Comparable<ComparableLocale>, Serializable {
	@Serial private static final long serialVersionUID = -3500017809507870169L;
	@Nonnull @Getter private final Locale locale;

	@Override
	public int compareTo(@Nonnull ComparableLocale o) {
		final int tagOrder = this.locale.toLanguageTag().compareTo(o.locale.toLanguageTag());
		// the tie-break is what makes this order consistent with equals - see the class javadoc for why an index
		// key demands that. It is reached only where the tags already match, so it changes no ordering decision
		// the language tag actually makes
		return tagOrder != 0 ? tagOrder : this.locale.toString().compareTo(o.locale.toString());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ComparableLocale that = (ComparableLocale) o;
		return this.locale.equals(that.locale);
	}

	@Override
	public int hashCode() {
		return this.locale.hashCode();
	}

	@Override
	public String toString() {
		return this.locale.toLanguageTag();
	}
}
