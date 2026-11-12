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

package io.evitadb.core.query.algebra.locale;

import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.identity.*;

import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link LocaleFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class LocaleFormulaIdentityFixture implements IdentityFixture<LocaleFormula> {
	/** Covers producer operands, copied content and unbounded opaque content. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<LocaleFormula>> modes() {
		final Bitmap producer = new TransactionalBitmap(new BaseBitmap(1));
		final Bitmap otherProducer = new TransactionalBitmap(new BaseBitmap(1));
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final Bitmap opaque = new BaseBitmap(values);
		final Map<String, ClonePath<LocaleFormula>> paths = Map.of(
			"inner formulas", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)
		);
		return Map.of(
			"content", new ConstructionMode<>(() -> new LocaleFormula(new BaseBitmap(1)),
				Map.of("content", () -> new LocaleFormula(new BaseBitmap(2))),
				Map.of("large content", () -> new LocaleFormula(opaque)), Map.of(), paths,
				Set.of(), Set.of(), Set.of("suppliedIdentity")),
			"producer", new ConstructionMode<>(() -> new LocaleFormula(producer),
				Map.of("version", () -> new LocaleFormula(otherProducer)),
				Map.of("large content", () -> new LocaleFormula(opaque)), Map.of(), paths,
				Set.of(), Set.of(), Set.of("suppliedIdentity"))
		);
	}
}
