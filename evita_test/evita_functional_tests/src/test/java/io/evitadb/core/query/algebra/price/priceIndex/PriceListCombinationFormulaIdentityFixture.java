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

package io.evitadb.core.query.algebra.price.priceIndex;

import io.evitadb.test.identity.*;
import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.core.query.algebra.price.PriceIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and clone contracts of {@link PriceListCombinationFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceListCombinationFormulaIdentityFixture implements IdentityFixture<PriceListCombinationFormula> {
	/** Covers the holder's constructor and factory modes with independently changed inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<PriceListCombinationFormula>> modes() {
		return Map.of("constructor", new ConstructionMode<>(() -> new PriceListCombinationFormula("first", "second",
			context(0), leaf(1), leaf(2)),
				Map.of("subtracted name", () -> new PriceListCombinationFormula("other", "second", context(0), leaf(1), leaf(2)),
					"name", () -> new PriceListCombinationFormula("first", "other", context(0), leaf(1), leaf(2)),
					"context", () -> new PriceListCombinationFormula("first", "second", context(1), leaf(1), leaf(2))), Map.of(),
						Map.of(),
				Map.of("equal children", new ClonePath<>(
				formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)), Set.of("subtractedBitmap",
					"supersetBitmap"), Set.of("subtractedBitmap", "supersetBitmap"), Set.of()));
	}
}
