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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link IndexTaggedFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class IndexTaggedFormulaIdentityFixture
	implements IdentityFixture<IndexTaggedFormula> {
	/** Covers the value and locality contracts of the holder. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<IndexTaggedFormula>> modes() {
		return Map.of("tag", new ConstructionMode<>(() -> new IndexTaggedFormula(1, leaf(1)),
			Map.of("tag", () -> new IndexTaggedFormula(2, leaf(1))), Map.of(), Map.of(),
			Map.of("equal child", new ClonePath<>(formula ->
				formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
			Set.of(), Set.of(), Set.of()));
	}

	/** Builds an exact nonempty child. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

}
