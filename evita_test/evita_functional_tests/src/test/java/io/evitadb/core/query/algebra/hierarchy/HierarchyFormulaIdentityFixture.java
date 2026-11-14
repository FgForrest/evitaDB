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

package io.evitadb.core.query.algebra.hierarchy;

import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.FormulaIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and operations for {@link HierarchyFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HierarchyFormulaIdentityFixture implements IdentityFixture<HierarchyFormula> {
	/** Covers the value, opaque and oversized forms admitted by construction. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<HierarchyFormula>> modes() {
		return Map.of(
			"reference", new ConstructionMode<>(
				() -> new HierarchyFormula("category", leaf(1)),
				Map.of("name", () -> new HierarchyFormula("tree", leaf(1)),
					"self", () -> new HierarchyFormula(null, leaf(1))), Map.of(), Map.of(),
				Map.of("inner formulas", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of(), Set.of(), Set.of()),
			"self", new ConstructionMode<>(
				() -> new HierarchyFormula(null, leaf(1)),
				Map.of("reference", () -> new HierarchyFormula("category", leaf(1))), Map.of(), Map.of(),
				Map.of("inner formulas", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of(), Set.of(), Set.of())
		);
	}
}
