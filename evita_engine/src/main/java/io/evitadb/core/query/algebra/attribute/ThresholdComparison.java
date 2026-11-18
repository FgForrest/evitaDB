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
import java.util.Objects;

/**
 * Comparison with an optional normalized decimal value; an absent value accepts every threshold.
 *
 * @param operator comparison to apply
 * @param value requested value, or null for an unconstrained threshold
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ThresholdComparison(@Nonnull ThresholdOperator operator, @Nullable BigDecimal value)
	implements RequestedThresholdPredicate {
	/** Normalizes the requested value for numeric equality. */
	public ThresholdComparison {
		Objects.requireNonNull(operator);
		value = ThresholdRange.normalize(value);
	}

	/** Evaluates the comparison against the normalized value. */
	@Override
	public boolean test(@Nonnull BigDecimal threshold) {
		return this.value == null || this.operator.accepts(threshold.compareTo(this.value));
	}
}
