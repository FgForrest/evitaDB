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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ComparableLocale implements Comparable<ComparableLocale>, Serializable {
	@Serial private static final long serialVersionUID = -3500017809507870169L;
	@Getter private final Locale locale;

	@Override
	public int compareTo(@Nonnull ComparableLocale o) {
		return this.locale.toLanguageTag().compareTo(o.locale.toLanguageTag());
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
