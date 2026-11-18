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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.identity.*;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link AttributeFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AttributeFormulaIdentityFixture implements IdentityFixture<AttributeFormula> {
	/** Covers the public construction contracts and their detached inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<AttributeFormula>> modes() {
		return Map.of(
			"without predicate", new ConstructionMode<>(
				() -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1)),
				Map.of("global index", () -> new AttributeFormula(true, new AttributeKey("Aa", Locale.ENGLISH), leaf(1)),
					"attribute name", () -> new AttributeFormula(false, new AttributeKey("BB", Locale.ENGLISH), leaf(1)),
					"attribute locale", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.FRENCH), leaf(1))),
				Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED),
					"two children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(leaf(1), leaf(2)), REJECTED)),
				Set.of("localeImplied", "requestedPredicate"), Set.of(), Set.of()),
			"with predicate", new ConstructionMode<>(
				() -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
					new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN)),
				Map.of("global index", () -> new AttributeFormula(true, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
					new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN)),
					"attribute name", () -> new AttributeFormula(false, new AttributeKey("BB", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN)),
					"attribute locale", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.FRENCH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN)),
					"requested predicate", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ZERO, BigDecimal.TEN)),
					"upper bound", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, new BigDecimal("20"))),
					"operator", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdComparison(ThresholdOperator.GREATER_THAN, BigDecimal.ONE)),
					"absent predicate", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1), null)),
				Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED),
					"two children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(leaf(1), leaf(2)), REJECTED)),
				Set.of("localeImplied"), Set.of(), Set.of()),
			"locale implication", new ConstructionMode<>(
				() -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
					new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN), true),
				Map.of("global index", () -> new AttributeFormula(true, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
					new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN), true),
					"attribute name", () -> new AttributeFormula(false, new AttributeKey("BB", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN), true),
					"attribute locale", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.FRENCH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN), true),
					"requested predicate", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ZERO, BigDecimal.TEN), true),
					"upper bound", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, new BigDecimal("20")), true),
					"operator", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdComparison(ThresholdOperator.GREATER_THAN, BigDecimal.ONE), true),
					"absent predicate", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1), null, true),
					"locale implication", () -> new AttributeFormula(false, new AttributeKey("Aa", Locale.ENGLISH), leaf(1),
						new ThresholdRange(BigDecimal.ONE, BigDecimal.TEN), false)),
				Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED),
					"two children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(leaf(1), leaf(2)), REJECTED)),
				Set.of(), Set.of(), Set.of())
		);
	}

	/** Builds a fresh child with exact content. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}
}
