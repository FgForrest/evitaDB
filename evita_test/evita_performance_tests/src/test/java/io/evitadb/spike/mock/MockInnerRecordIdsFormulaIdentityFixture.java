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

package io.evitadb.spike.mock;

import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.identity.*;
import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and clone contracts of {@link MockInnerRecordIdsFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class MockInnerRecordIdsFormulaIdentityFixture implements IdentityFixture<MockInnerRecordIdsFormula> {
	/** Covers the holder's constructor and factory modes with independently changed inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<MockInnerRecordIdsFormula>> modes() {
		final io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords records =
			new io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords();
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		final io.evitadb.index.bitmap.Bitmap opaque = new BaseBitmap(values);
		return Map.of("benchmark output", new ConstructionMode<>(null, Map.of(),
			Map.of("price records", () -> new MockInnerRecordIdsFormula(new BaseBitmap(1), records),
				"large bitmap", () -> new MockInnerRecordIdsFormula(opaque, records)),
			Map.of(), Map.of("inner formulas", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)),
			Set.of(), Set.of(), Set.of()));
	}
}
