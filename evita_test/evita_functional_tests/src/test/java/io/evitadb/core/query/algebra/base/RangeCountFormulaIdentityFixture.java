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
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.REJECTED;

/**
 * Real bitmap families and rejected child-clone paths for {@link RangeCountFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RangeCountFormulaIdentityFixture implements IdentityFixture<RangeCountFormula> {
	/** Covers copied content, producer versions, empty minus families and bounded admission. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<RangeCountFormula>> modes() {
		final Bitmap producer = new TransactionalBitmap(1);
		final Bitmap otherProducer = new TransactionalBitmap(1);
		final Bitmap minusProducer = new TransactionalBitmap(2);
		final Bitmap otherMinusProducer = new TransactionalBitmap(2);
		final Bitmap[] oversized = new Bitmap[TokenList.CAP + 1];
		Arrays.fill(oversized, producer);
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final Bitmap opaque = new BaseBitmap(values);
		final Map<String, ClonePath<RangeCountFormula>> paths = Map.of(
			"inner formulas", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)
		);
		return Map.of(
			"content", new ConstructionMode<>(
				() -> formula(new BaseBitmap(1), new BaseBitmap(2)),
				Map.of(
					"plus", () -> formula(new BaseBitmap(3), new BaseBitmap(2)),
					"minus", () -> formula(new BaseBitmap(1), new BaseBitmap(3)),
					"plus multiplicity", () -> new RangeCountFormula(1,
						new Bitmap[]{new BaseBitmap(1), new BaseBitmap(1)}, new Bitmap[]{new BaseBitmap(2)}),
					"minus multiplicity", () -> new RangeCountFormula(1,
						new Bitmap[]{new BaseBitmap(1)}, new Bitmap[]{new BaseBitmap(2), new BaseBitmap(2)})
				),
				Map.of("opaque plus", () -> formula(opaque, new BaseBitmap(2)),
					"opaque minus", () -> formula(new BaseBitmap(1), opaque)),
				Map.of("many plus", () -> new RangeCountFormula(1, oversized, new Bitmap[]{producer}),
					"many minus", () -> new RangeCountFormula(1, new Bitmap[]{producer}, oversized)),
				paths, Set.of(), Set.of(), Set.of()),
			"producer", new ConstructionMode<>(
				() -> formula(producer, minusProducer),
				Map.of("plus version", () -> formula(otherProducer, minusProducer),
					"minus version", () -> formula(producer, otherMinusProducer)),
				Map.of("opaque plus", () -> formula(opaque, minusProducer),
					"opaque minus", () -> formula(producer, opaque)),
				Map.of("many plus", () -> new RangeCountFormula(1, oversized, new Bitmap[]{minusProducer}),
					"many minus", () -> new RangeCountFormula(1, new Bitmap[]{producer}, oversized)),
				paths, Set.of(), Set.of(), Set.of()),
			"empty minus", new ConstructionMode<>(
				() -> new RangeCountFormula(1, new Bitmap[]{new BaseBitmap(1)}, new Bitmap[0]),
				Map.of("plus", () -> new RangeCountFormula(1, new Bitmap[]{new BaseBitmap(2)}, new Bitmap[0])),
				Map.of("opaque plus", () -> new RangeCountFormula(1, new Bitmap[]{opaque}, new Bitmap[0])),
				Map.of("many plus", () -> new RangeCountFormula(1, oversized, new Bitmap[0])),
				paths, Set.of("minus"), Set.of("minus"), Set.of("minus"))
		);
	}

	/** Builds one operand in each signed family. */
	@Nonnull
	private static RangeCountFormula formula(@Nonnull Bitmap plus, @Nonnull Bitmap minus) {
		return new RangeCountFormula(1, new Bitmap[]{plus}, new Bitmap[]{minus});
	}
}
