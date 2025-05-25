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
import java.util.Currency;

/**
 * Comparable wrapper for {@link Currency} object. This class is used to provide {@link Comparable} interface for
 * {@link Currency} object, which is not {@link Comparable} by default.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ComparableCurrency implements Comparable<ComparableCurrency>, Serializable {
	@Serial private static final long serialVersionUID = -8356388816362776092L;
	@Getter private final Currency currency;

	@Override
	public int compareTo(@Nonnull ComparableCurrency o) {
		return this.currency.toString().compareTo(o.currency.toString());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ComparableCurrency that = (ComparableCurrency) o;
		return this.currency.equals(that.currency);
	}

	@Override
	public int hashCode() {
		return this.currency.hashCode();
	}

	@Override
	public String toString() {
		return this.currency.getCurrencyCode();
	}
}
