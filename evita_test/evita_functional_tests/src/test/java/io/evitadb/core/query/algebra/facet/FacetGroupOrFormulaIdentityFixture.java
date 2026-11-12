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

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
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
 * Real construction modes and clone operations for {@link FacetGroupOrFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FacetGroupOrFormulaIdentityFixture implements IdentityFixture<FacetGroupOrFormula> {
	/** Covers the public construction contracts and their detached inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<FacetGroupOrFormula>> modes() {
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final Bitmap opaque = new BaseBitmap(values);
		final Bitmap producer = new TransactionalBitmap(new BaseBitmap(10));
		final Bitmap[] many = new Bitmap[values.length];
		Arrays.fill(many, producer);
		return Map.of("constructor", new ConstructionMode<>(
			() -> new FacetGroupOrFormula("Aa", 1, new BaseBitmap(1, 2), new BaseBitmap(10), new BaseBitmap(20)),
			Map.of("reference", () -> new FacetGroupOrFormula("BB", 1, new BaseBitmap(1, 2), new BaseBitmap(10),
				new BaseBitmap(20)),
				"group", () -> new FacetGroupOrFormula("Aa", null, new BaseBitmap(1, 2), new BaseBitmap(10), new BaseBitmap(20)),
				"minimum group", () -> new FacetGroupOrFormula("Aa", Integer.MIN_VALUE, new BaseBitmap(1, 2),
					new BaseBitmap(10), new BaseBitmap(20)),
				"facet", () -> new FacetGroupOrFormula("Aa", 1, new BaseBitmap(1, 3), new BaseBitmap(10), new BaseBitmap(20)),
				"operand", () -> new FacetGroupOrFormula("Aa", 1, new BaseBitmap(1, 2), new BaseBitmap(11), new BaseBitmap(20)),
				"association", () -> new FacetGroupOrFormula("Aa", 1, new BaseBitmap(1, 2), new BaseBitmap(20), new BaseBitmap(10)),
				"producer", () -> new FacetGroupOrFormula("Aa", 1, new BaseBitmap(1, 2), producer, new BaseBitmap(20))),
			Map.of("large operand", () -> new FacetGroupOrFormula("Aa", 1, new BaseBitmap(1, 2), opaque, new BaseBitmap(20)),
				"many facets", () -> new FacetGroupOrFormula("Aa", 1, opaque, many)), Map.of(),
			Map.of("inner formulas", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), EQUAL),
				"merge", new ClonePath<>(formula -> formula.mergeWith(new FacetGroupOrFormula(
					formula.getReferenceName(), formula.getFacetGroupId(), new BaseBitmap(3), new BaseBitmap(30))), UNEQUAL)),
			Set.of(), Set.of(), Set.of()));
	}

	/** Builds a fresh child with exact content. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}
}
