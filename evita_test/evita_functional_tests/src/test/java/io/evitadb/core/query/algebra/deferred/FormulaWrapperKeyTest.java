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

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached computation identity on real holders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula wrapper identity")
@Tag(ENGINE)
@Tag(CACHE)
class FormulaWrapperKeyTest {
	/** Wrappers carry their delegate identity. */
	@Test
	@DisplayName("Includes the wrapped formula")
	void shouldDistinguishDelegates() {
		final FormulaWrapper first = new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(1)), () -> "nested query");
		final FormulaWrapper second = new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(2)), () -> "nested query");
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
	}

	/** Telemetry descriptions have no effect on the computed bitmap. */
	@Test
	@DisplayName("Shares transparent wrappers despite different telemetry descriptions")
	void shouldIgnoreTelemetryDescription() {
		final FormulaWrapper first = new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(1)), () -> "first");
		final FormulaWrapper second = new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(1)), () -> "second");
		assertEquals(first.getKey(), second.getKey());
	}

	/** Arbitrary computation over the same delegate cannot be equated by that delegate. */
	@Test
	@DisplayName("Makes custom computations instance unique")
	void shouldBindCustomComputationToHolder() {
		final ConstantFormula delegate = new ConstantFormula(new BaseBitmap(1));
		final java.util.function.BiFunction<io.evitadb.core.query.QueryExecutionContext,
			io.evitadb.core.query.algebra.Formula, io.evitadb.index.bitmap.Bitmap> computation =
			(context, formula) -> formula.compute();
		final FormulaWrapper first = FormulaWrapper.custom(delegate, computation);
		final FormulaWrapper second = FormulaWrapper.custom(delegate, computation);
		assertTrue(first.getKey().isOpaque());
		assertTrue(first.getKey().isQueryLocal());
		assertSame(first.getKey(), first.getKey());
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		assertNotEquals(first.getKey(), new FormulaWrapper(delegate, () -> "transparent").getKey());
	}
}
