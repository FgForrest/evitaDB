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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies detached virtual reference content independently of bitmap implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Multiple entity formula identity")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(CACHE)
class MultipleEntityFormulaTest {
	/** The identity keeps a bounded content copy when the original mutable bitmap changes. */
	@Test
	@DisplayName("Captures exact virtual reference content without retaining the bitmap")
	void shouldCaptureDetachedExactReferenceContent() {
		final BaseBitmap bitmap = new BaseBitmap(1, 2);
		final MultipleEntityFormula formula = new MultipleEntityFormula(bitmap);
		final MultipleEntityFormula.Identity identity =
			assertInstanceOf(MultipleEntityFormula.Identity.class, formula.identity());
		assertInstanceOf(IdentityToken.Exact.class, identity.directEntityReferences());
		final FormulaKey captured = FormulaKey.of(identity, formula.getClassId(), FormulaKey.NO_CHILDREN, formula);

		bitmap.add(3);

		assertEquals(captured, FormulaKey.of(identity, formula.getClassId(), FormulaKey.NO_CHILDREN, formula));
		assertEquals(captured, new MultipleEntityFormula(new BaseBitmap(1, 2)).getKey());
		assertNotEquals(captured, new MultipleEntityFormula(new BaseBitmap(1, 2, 3)).getKey());
	}

	/** Virtual references have content identity even when their bitmap happens to have a producer version. */
	@Test
	@DisplayName("Equal virtual reference content ignores producer ids")
	void shouldUseExactContentForTransactionalReferenceBitmaps() {
		final Bitmap first = new TransactionalBitmap(new BaseBitmap(1, 2));
		final Bitmap second = new TransactionalBitmap(new BaseBitmap(1, 2));
		final MultipleEntityFormula left = new MultipleEntityFormula(first);
		final MultipleEntityFormula right = new MultipleEntityFormula(second);

		assertEquals(left.getKey(), right.getKey());
		assertEquals(left.getKey().getHash64(), right.getKey().getHash64());
		assertInstanceOf(IdentityToken.Exact.class,
			assertInstanceOf(MultipleEntityFormula.Identity.class, left.identity()).directEntityReferences());
	}
}
