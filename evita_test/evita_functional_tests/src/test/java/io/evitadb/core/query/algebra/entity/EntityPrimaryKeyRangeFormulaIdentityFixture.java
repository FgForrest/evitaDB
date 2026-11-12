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

package io.evitadb.core.query.algebra.entity;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.EQUAL;

/**
 * Inclusive bound variants and the child-clone path for {@link EntityPrimaryKeyRangeFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EntityPrimaryKeyRangeFormulaIdentityFixture implements IdentityFixture<EntityPrimaryKeyRangeFormula> {
	/** Covers each bound and the superset supplied through the constructor. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<EntityPrimaryKeyRangeFormula>> modes() {
		return Map.of("range", new ConstructionMode<>(
			() -> formula(1, 10, 1),
			Map.of("from", () -> formula(2, 10, 1), "to", () -> formula(1, 9, 1),
				"superset", () -> formula(1, 10, 2)),
			Map.of(), Map.of(),
			Map.of("equal children", new ClonePath<>(
				formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
			Set.of(), Set.of(), Set.of()
		));
	}

	/** Creates a real range formula with an exact constant child. */
	@Nonnull
	private static EntityPrimaryKeyRangeFormula formula(int from, int to, int primaryKey) {
		return new EntityPrimaryKeyRangeFormula(from, to, new ConstantFormula(new BaseBitmap(primaryKey)));
	}
}
