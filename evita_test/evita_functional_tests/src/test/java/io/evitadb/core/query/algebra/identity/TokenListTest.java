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

package io.evitadb.core.query.algebra.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;

/**
 * Verifies ordered and canonical operand sequences and admission limits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Operand token sequences")
@Tag(ENGINE)
@Tag(CACHE)
class TokenListTest {
	/** Checks canonical order, positional order, and retained duplicates. */
	@Test
	@DisplayName("Canonicalizes operands while retaining multiplicity")
	void shouldCanonicalizeOperands() {
		final Bitmap first = new TransactionalBitmap(1);
		final Bitmap second = new BaseBitmap(2);
		final TokenList canonical = TokenList.canonical(new Bitmap[]{first, second, first});
		final TokenList reordered = TokenList.canonical(new Bitmap[]{first, first, second});
		assertEquals(canonical, reordered);
		assertEquals(canonical.hashCode(), reordered.hashCode());
		assertEquals(canonical.hash64(), reordered.hash64());
		assertEquals(3, canonical.size());
		assertNotEquals(canonical, TokenList.canonical(new Bitmap[]{first, second}));
		assertNotEquals(TokenList.ordered(new Bitmap[]{first, second}),
			TokenList.ordered(new Bitmap[]{second, first}));
		assertFalse(canonical.isOpaque());
		assertFalse(canonical.isQueryLocal());
	}
	/** Checks primitive storage and exact oversized sequences. */
	@Test
	@DisplayName("Copies producer ids and keeps oversized lists exact")
	void shouldKeepOversizedSequencesExact() {
		final long[] ids = {2, 1, 1};
		final TokenList canonical = TokenList.ofProducerIds(ids, true);
		ids[0] = 9;
		assertEquals(TokenList.ofProducerIds(new long[]{1, 1, 2}, false), canonical);
		assertEquals(3, canonical.weight());
		final TokenList oversized = TokenList.ofProducerIds(new long[TokenList.CAP + 1], false);
		assertFalse(oversized.isOpaque());
		assertTrue(oversized.isQueryLocal());
		assertEquals(oversized, TokenList.ofProducerIds(new long[TokenList.CAP + 1], false));
		final TransactionalBitmap bitmap = new TransactionalBitmap(1);
		assertEquals(TokenList.ofProducerIds(new long[]{bitmap.getId()}, false),
			TokenList.ordered(new Bitmap[]{bitmap}));
	}
	/** Checks that large unowned operands make the sequence opaque. */
	@Test
	@DisplayName("Propagates opaque bitmap operands")
	void shouldPropagateOpaqueOperands() {
		final int[] content = new int[IdentityToken.CONTENT_LIMIT + 1];
		java.util.Arrays.setAll(content, index -> index);
		final TokenList tokens = TokenList.ordered(new Bitmap[]{new BaseBitmap(content)});
		assertTrue(tokens.isOpaque());
		assertTrue(tokens.isQueryLocal());
	}
}
