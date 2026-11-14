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

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.FormulaIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and operations for {@link DeferredFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DeferredFormulaIdentityFixture implements IdentityFixture<DeferredFormula> {
	/** Covers the value, opaque and oversized forms admitted by construction. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<DeferredFormula>> modes() {
		final Formula opaque = opaqueFormula();
		final Formula oversized = oversizedFormula();
		return Map.of("supplier", new ConstructionMode<>(
			() -> new DeferredFormula(new FormulaWrapper(leaf(1), () -> "first")),
			Map.of("delegate", () -> new DeferredFormula(new FormulaWrapper(leaf(2), () -> "second"))),
			Map.of("opaque delegate", () -> new DeferredFormula(new FormulaWrapper(opaque, () -> "opaque")),
				"custom", () -> new DeferredFormula(FormulaWrapper.custom(leaf(1), (ctx,
					formula) -> formula.compute()))),
			Map.of("oversized delegate", () -> new DeferredFormula(new FormulaWrapper(oversized, () -> "large"))),
			Map.of("inner formulas", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)),
			Set.of(), Set.of(), Set.of()));
	}
}
