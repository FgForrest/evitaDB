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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a mutable facet wrapper always exposes its current computation's key.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Mutable facet formula keys")
@Tag(ENGINE)
@Tag(CACHE)
class MutableFormulaKeyTest {
	/** The wrapper cannot retain a key when the summary installs the next facet. */
	@Test
	@DisplayName("Forwards the delegate key before and after a swap")
	void shouldFollowDelegateSwap() {
		final FacetGroupFormula first = group(1, 10);
		final FacetGroupFormula second = group(2, 20);
		final MutableFormula mutable = new MutableFormula(first);
		assertSame(first.getKey(), mutable.getKey());
		mutable.setDelegate(second);
		assertSame(second.getKey(), mutable.getKey());
		assertNotEquals(first.getKey(), mutable.getKey());
	}

	/** The key includes the mandatory term as soon as a pivot is installed. */
	@Test
	@DisplayName("Follows pivot installation and clearing of computed state")
	void shouldFollowPivotAndMemoryReset() {
		final FacetGroupFormula delegate = group(1, 10);
		final MutableFormula mutable = new MutableFormula(delegate);
		final FormulaKey original = mutable.getKey();
		mutable.setPivot(group(2, 20));
		final FormulaKey merged = mutable.getKey();
		assertNotEquals(original, merged);
		assertEquals(delegate.mergeWith(group(2, 20)).getKey(), merged);
		mutable.clearMemory();
		assertEquals(merged, mutable.getKey());
		mutable.setDelegate(group(3, 30));
		assertNotEquals(merged, mutable.getKey());
	}

	/** Builds a real facet term without external dependencies. */
	@Nonnull
	private static FacetGroupFormula group(int facet, int owner) {
		return new FacetGroupAndFormula("reference", 1, new BaseBitmap(facet), new BaseBitmap(owner));
	}
}
