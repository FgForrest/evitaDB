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

import java.util.function.Predicate;
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
 * Real construction inputs and clone contracts of {@link LowestPriceTerminationFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class LowestPriceTerminationFormulaIdentityFixture implements IdentityFixture<LowestPriceTerminationFormula> {
	/** Covers the holder's constructor and factory modes with independently changed inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<LowestPriceTerminationFormula>> modes() {
		final Predicate<PriceRecordContract> individual =
			record -> true;
		return Map.of("unrestricted individuals", new ConstructionMode<>(() -> new LowestPriceTerminationFormula(leaf(1),
			context(0), QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false),
				Map.of(
					"context", () -> new LowestPriceTerminationFormula(leaf(1), context(1), QueryPriceMode.WITH_TAX,
						PricePredicate.ALL_RECORD_FILTER, false),
					"mode", () -> new LowestPriceTerminationFormula(leaf(1), context(0), QueryPriceMode.WITHOUT_TAX,
						PricePredicate.ALL_RECORD_FILTER, false),
					"selling predicate", () -> new LowestPriceTerminationFormula(leaf(1), context(0), QueryPriceMode.WITH_TAX,
						predicate(), false),
					"histogram records", () -> new LowestPriceTerminationFormula(leaf(1), context(0), QueryPriceMode.WITH_TAX,
						PricePredicate.ALL_RECORD_FILTER, true)
				), Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"predicate factory", new ClonePath<>(
						formula -> formula.withIndividualPricePredicateFactory(() -> individual), UNEQUAL)),
				Set.of("individualPricePredicate"), Set.of("individualPricePredicate"), Set.of()),
			"predicate factory", new ConstructionMode<>(null, Map.of(),
				Map.of("restartable predicate", () -> new LowestPriceTerminationFormula(
					leaf(1), context(0), QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
				).withIndividualPricePredicateFactory(() -> individual)), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL),
					"predicate factory", new ClonePath<>(
						formula -> formula.withIndividualPricePredicateFactory(() -> individual), UNEQUAL)),
				Set.of(), Set.of(), Set.of()));
	}
}
