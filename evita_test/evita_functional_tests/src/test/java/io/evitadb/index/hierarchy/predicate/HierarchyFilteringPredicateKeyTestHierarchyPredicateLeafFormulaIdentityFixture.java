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

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicateKeyTest.HierarchyPredicateLeafFormula;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.FormulaIdentityFixtureSupport.opaqueFormula;
import static io.evitadb.test.identity.CloneOutcome.REJECTED;

/**
 * Real bitmap inputs of the comparison-counting executable leaf.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HierarchyFilteringPredicateKeyTestHierarchyPredicateLeafFormulaIdentityFixture
	implements IdentityFixture<HierarchyPredicateLeafFormula> {
	/** Exercises bounded and opaque bitmap content and the rejected terminal clone. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<HierarchyPredicateLeafFormula>> modes() {
		final Bitmap opaque = opaqueFormula().getDelegate();
		return Map.of("content", new ConstructionMode<>(
			() -> new HierarchyPredicateLeafFormula(new BaseBitmap(1)),
			Map.of("content", () -> new HierarchyPredicateLeafFormula(new BaseBitmap(2))),
			Map.of("opaque content", () -> new HierarchyPredicateLeafFormula(opaque)), Map.of(),
			Map.of("terminal clone", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)),
			Set.of(), Set.of(), Set.of("suppliedIdentity")));
	}
}
