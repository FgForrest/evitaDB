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
import io.evitadb.core.query.algebra.identity.TokenRole;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.identity.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link ConstantFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ConstantFormulaIdentityFixture implements IdentityFixture<ConstantFormula> {
	/** Covers producer operands, copied content, unbounded opaque content and attested derived operands. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<ConstantFormula>> modes() {
		final Bitmap producer = new TransactionalBitmap(new BaseBitmap(1));
		final Bitmap otherProducer = new TransactionalBitmap(new BaseBitmap(1));
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final Bitmap opaque = new BaseBitmap(values);
		final Map<String, ClonePath<ConstantFormula>> paths = Map.of(
			"inner formulas", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)
		);
		final IdentityToken derived = IdentityToken.derived(1, TokenRole.FILTER_INDEX_ALL_RECORDS);
		final AtomicInteger content = new AtomicInteger();
		return Map.of(
			"content", new ConstructionMode<>(() -> new ConstantFormula(new BaseBitmap(1)),
				Map.of("content", () -> new ConstantFormula(new BaseBitmap(2))),
				Map.of("large content", () -> new ConstantFormula(opaque)), Map.of(), paths,
				Set.of(), Set.of(), Set.of("suppliedIdentity")),
			"producer", new ConstructionMode<>(() -> new ConstantFormula(producer),
				Map.of("version", () -> new ConstantFormula(otherProducer)),
				Map.of("large content", () -> new ConstantFormula(opaque)), Map.of(), paths,
				Set.of(), Set.of(), Set.of("suppliedIdentity")),
			"derived token", new ConstructionMode<>(
				() -> new ConstantFormula(new BaseBitmap(content.incrementAndGet()), derived),
				Map.of(
					"owner", () -> new ConstantFormula(new BaseBitmap(1),
						IdentityToken.derived(2, TokenRole.FILTER_INDEX_ALL_RECORDS)),
					"role", () -> new ConstantFormula(new BaseBitmap(1),
						IdentityToken.derived(1, TokenRole.HIERARCHY_ALL_NODES))
				), Map.of(), Map.of(), paths, Set.of(), Set.of("delegateIdentity"), Set.of("delegate"))
		);
	}
}
