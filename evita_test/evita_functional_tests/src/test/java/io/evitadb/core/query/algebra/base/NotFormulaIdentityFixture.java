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

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.identity.*;

import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link NotFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class NotFormulaIdentityFixture implements IdentityFixture<NotFormula> {
	/** Covers ordered formula children and independently variable raw operands. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<NotFormula>> modes() {
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final Bitmap opaque = new BaseBitmap(values);
		return Map.of(
			"formula", new ConstructionMode<>(() -> new NotFormula(leaf(1), leaf(2)),
				Map.of("child", () -> new NotFormula(leaf(3), leaf(2))), Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of("subtractedBitmap", "supersetBitmap"), Set.of("subtractedBitmap", "supersetBitmap"), Set.of()),
			"raw bitmaps", new ConstructionMode<>(() -> new NotFormula(new BaseBitmap(1), new BaseBitmap(2)),
				Map.of("subtracted", () -> new NotFormula(new BaseBitmap(3), new BaseBitmap(2)),
					"superset", () -> new NotFormula(new BaseBitmap(1), new BaseBitmap(3))),
				Map.of("opaque subtracted", () -> new NotFormula(opaque, new BaseBitmap(2)),
					"opaque superset", () -> new NotFormula(new BaseBitmap(1), opaque)), Map.of(),
				Map.of("inner formulas", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), REJECTED)),
				Set.of(), Set.of(), Set.of())
		);
	}

	/** Builds a fresh exact child with the requested content. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}
}
