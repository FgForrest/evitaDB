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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.identity.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link ScopeContainerFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ScopeContainerFormulaIdentityFixture implements IdentityFixture<ScopeContainerFormula> {
	/** Covers the public construction contracts and their detached inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<ScopeContainerFormula>> modes() {
		final Bitmap producer = new TransactionalBitmap(new BaseBitmap(1));
		final Bitmap[] many = new Bitmap[TokenList.CAP + 1];
		Arrays.fill(many, producer);
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final ConstantFormula opaque = new ConstantFormula(new BaseBitmap(values));
		final ConstantFormula universe = leaf(1);
		final ConstantFormula otherUniverse = leaf(3);
		return Map.of(
			"plain", new ConstructionMode<>(
				() -> new ScopeContainerFormula(Scope.LIVE, leaf(1), leaf(2)),
				Map.of("scope", () -> new ScopeContainerFormula(Scope.ARCHIVED, leaf(1), leaf(2))),
				Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), UNEQUAL)),
				Set.of("emptyScopeFormula"), Set.of("emptyScopeFormula"), Set.of()),
			"restricting scope", new ConstructionMode<>(
				() -> ScopeContainerFormula.restrictingScope(Scope.LIVE, universe, leaf(2)),
				Map.of("scope", () -> ScopeContainerFormula.restrictingScope(Scope.ARCHIVED, universe, leaf(2)),
					"universe", () -> ScopeContainerFormula.restrictingScope(Scope.LIVE, otherUniverse, leaf(2))),
				Map.of(), Map.of("large universe", () -> ScopeContainerFormula.restrictingScope(
					Scope.LIVE, new AndFormula(new long[]{1}, many), leaf(2))),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), UNEQUAL)),
				Set.of(), Set.of(), Set.of()),
			"opaque scope universe", new ConstructionMode<>(null, Map.of(),
				Map.of("opaque universe", () -> ScopeContainerFormula.restrictingScope(Scope.LIVE, opaque, leaf(2))),
				Map.of(), Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), UNEQUAL)),
				Set.of("scope"), Set.of(), Set.of())
		);

	}

	/** Builds a fresh child with exact content. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}
}
