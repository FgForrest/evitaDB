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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.dataType.Scope;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate.*;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.FormulaIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and operations for {@link OrHierarchyFilteringPredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HierarchyFilteringPredicateOrHierarchyFilteringPredicateIdentityFixture
	implements IdentityFixture<OrHierarchyFilteringPredicate> {
	/** Covers the value, opaque and oversized forms admitted by construction. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<OrHierarchyFilteringPredicate>> modes() {
		final HierarchyFilteringPredicate basePredicate = new MatchNodeIdHierarchyFilteringPredicate(1);
		final HierarchyFilteringPredicate opaque = new FilteringFormulaHierarchyEntityPredicate(
			"category", Set.of(Scope.LIVE), opaqueFormula());
		final HierarchyFilteringPredicate oversized = new FilteringFormulaHierarchyEntityPredicate(
			"category", Set.of(Scope.LIVE), oversizedFormula());
		return Map.of("operands", new ConstructionMode<>(
			() -> new OrHierarchyFilteringPredicate(basePredicate,
				basePredicate),
			Map.of("first",
				() -> new OrHierarchyFilteringPredicate(new MatchNodeIdHierarchyFilteringPredicate(2),
					basePredicate),
				"second", () -> new OrHierarchyFilteringPredicate(basePredicate,
					new MatchNodeIdHierarchyFilteringPredicate(2))),
			Map.of("first", () -> new OrHierarchyFilteringPredicate(opaque,
				basePredicate),
				"second", () -> new OrHierarchyFilteringPredicate(basePredicate,
					opaque)),
			Map.of("first", () -> new OrHierarchyFilteringPredicate(oversized,
				basePredicate),
				"second", () -> new OrHierarchyFilteringPredicate(basePredicate,
					oversized)),
			Map.of(), Set.of(), Set.of(), Set.of()));
	}
}
