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
 * Verifies the detached keys of {@link DeferredFormula}: they follow the supplier it defers to, and an opaque
 * supplier computation makes them instance-unique.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Deferred formula identity")
@Tag(ENGINE)
@Tag(CACHE)
class DeferredFormulaKeyTest {
	/** Supplier identity distinguishes deferred computations. */
	@Test
	@DisplayName("Includes supplier identity")
	void shouldDistinguishSuppliers() {
		final DeferredFormula first = new DeferredFormula(new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(1)), () -> "nested query"));
		final DeferredFormula second = new DeferredFormula(new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(2)), () -> "nested query"));
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
	}

	/** Opaque supplier inputs bind each deferred holder separately. */
	@Test
	@DisplayName("Propagates opaque supplier identity to the holder")
	void shouldBindOpaqueSupplierToDeferredHolder() {
		final FormulaWrapper wrapper = FormulaWrapper.custom(
			new ConstantFormula(new BaseBitmap(1)), (context, formula) -> formula.compute());
		final DeferredFormula first = new DeferredFormula(wrapper);
		final DeferredFormula second = new DeferredFormula(wrapper);
		assertTrue(first.getKey().isOpaque());
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		assertThrows(UnsupportedOperationException.class, first::getCloneWithInnerFormulas);
	}
}
