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

package io.evitadb.core.query.algebra.attribute;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Inclusive decimal range with optional bounds and numeric value equality.
 *
 * @param from inclusive lower bound
 * @param to inclusive upper bound
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ThresholdRange(@Nullable BigDecimal from, @Nullable BigDecimal to)
	implements RequestedThresholdPredicate {
	/** Normalizes both bounds for numeric equality. */
	public ThresholdRange {
		from = normalize(from);
		to = normalize(to);
	}

	/**
	 * Strips trailing zeros and rebuilds the value as an exact `BigDecimal`, so numerically equal values are equal
	 * records and no subclass instance is retained.
	 */
	@Nullable
	static BigDecimal normalize(@Nullable BigDecimal value) {
		if (value == null) {
			return null;
		}
		final BigDecimal normalized = value.stripTrailingZeros();
		return new BigDecimal(normalized.unscaledValue(), normalized.scale());
	}

	/** Tests both inclusive bounds. */
	@Override
	public boolean test(@Nonnull BigDecimal threshold) {
		return (this.from == null || threshold.compareTo(this.from) >= 0) &&
			(this.to == null || threshold.compareTo(this.to) <= 0);
	}
}
