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

package io.evitadb.core.query.algebra.price.termination;

import com.carrotsearch.hppc.IntCollection;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.test.identity.*;
import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.core.query.algebra.price.PriceIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and clone contracts of {@link SumPriceTerminationFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SumPriceTerminationFormulaIdentityFixture implements IdentityFixture<SumPriceTerminationFormula> {
	/** Covers the holder's constructor and factory modes with independently changed inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<SumPriceTerminationFormula>> modes() {
		final SumPriceTerminationFormula.SumPredicate<PriceRecordContract>
			individual = new SumPriceTerminationFormula.SumPredicate<>() {
				/** No component price is omitted from this constant predicate. */
				@Override
				public int getMissingComponentsPrice(@Nonnull IntCollection ids) {
					return 0;
				}

				/** Accepts every individual price record. */
				@Override
				public boolean test(PriceRecordContract record) {
					return true;
				}
			};
		return Map.of("unrestricted individuals", new ConstructionMode<>(() -> new SumPriceTerminationFormula(leaf(1),
			context(0), QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER),
				Map.of(
					"context", () -> new SumPriceTerminationFormula(leaf(1), context(1), QueryPriceMode.WITH_TAX,
						PricePredicate.ALL_RECORD_FILTER),
					"mode", () -> new SumPriceTerminationFormula(leaf(1), context(0), QueryPriceMode.WITHOUT_TAX,
						PricePredicate.ALL_RECORD_FILTER),
					"selling predicate", () -> new SumPriceTerminationFormula(leaf(1), context(0), QueryPriceMode.WITH_TAX,
						predicate())
				), Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"individual predicate", new ClonePath<>(
						formula -> formula.withIndividualPricePredicate(individual), UNEQUAL),
					"predicate factory", new ClonePath<>(
						formula -> formula.withIndividualPricePredicateFactory(() -> individual), UNEQUAL)),
				Set.of("individualPricePredicate"), Set.of("individualPricePredicate"), Set.of()),
			"individual predicate", new ConstructionMode<>(null,
				Map.of(), Map.of("individual predicate", () -> new SumPriceTerminationFormula(leaf(1), context(0),
					QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER).withIndividualPricePredicate(individual)), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL),
					"same predicate", new ClonePath<>(
						formula -> formula.withIndividualPricePredicate(individual), UNEQUAL),
					"predicate factory", new ClonePath<>(
						formula -> formula.withIndividualPricePredicateFactory(() -> individual), UNEQUAL)), Set.of(), Set.of(),
				Set.of()),
			"predicate factory", new ConstructionMode<>(null, Map.of(),
				Map.of("restartable predicate", () -> new SumPriceTerminationFormula(
					leaf(1), context(0), QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
				).withIndividualPricePredicateFactory(() -> individual)), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL),
					"same predicate", new ClonePath<>(
						formula -> formula.withIndividualPricePredicate(individual), UNEQUAL),
					"predicate factory", new ClonePath<>(
						formula -> formula.withIndividualPricePredicateFactory(() -> individual), UNEQUAL)),
				Set.of(), Set.of(), Set.of()));
	}
}
