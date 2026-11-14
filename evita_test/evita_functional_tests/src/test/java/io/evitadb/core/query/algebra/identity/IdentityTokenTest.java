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

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies versioned operands, copied content, and opaque admission limits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Operand identity tokens")
@Tag(ENGINE)
@Tag(CACHE)
class IdentityTokenTest {
	/** Verifies exact copies and producer version roles. */
	@Test
	@DisplayName("Uses producer versions and exact detached bitmap content")
	void shouldDeriveOperandTokens() {
		final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);
		assertEquals(new IdentityToken.ProducerId(bitmap.getId(), TokenRole.DIRECT), IdentityToken.of(bitmap));
		assertEquals(IdentityToken.of(new BaseBitmap(1, 2)), IdentityToken.ofContent(new int[]{1, 2}));
		assertNotEquals(IdentityToken.derived(bitmap.getId(), TokenRole.DIRECT),
			IdentityToken.derived(bitmap.getId(), TokenRole.FILTER_INDEX_ALL_RECORDS));
		assertEquals(1, IdentityToken.derived(0, TokenRole.HIERARCHY_ALL_NODES).weight());
		assertFalse(IdentityToken.derived(-1, TokenRole.DIRECT).isOpaque());
		assertNotEquals(IdentityToken.ofContent(new int[]{1}).hash64(),
			IdentityToken.derived(1, TokenRole.DIRECT).hash64());
	}

	/** Verifies the inclusive copy limit and the marker above it. */
	@Test
	@DisplayName("Copies bounded arrays and marks larger content opaque")
	void shouldRespectContentLimit() {
		final int[] content = new int[IdentityToken.CONTENT_LIMIT];
		Arrays.setAll(content, index -> index);
		final IdentityToken exact = IdentityToken.ofContent(content);
		assertEquals(content.length, exact.weight());
		assertFalse(exact.isOpaque());
		content[0] = -1;
		assertNotEquals(exact, IdentityToken.ofContent(content));
		assertSame(IdentityToken.OPAQUE, IdentityToken.ofContent(new int[IdentityToken.CONTENT_LIMIT + 1]));
		assertSame(IdentityToken.OPAQUE,
			IdentityToken.of(new BaseBitmap(IntStream.range(0, 1025).toArray())));
		assertTrue(IdentityToken.OPAQUE.isOpaque());
		assertEquals(1, IdentityToken.OPAQUE.weight());
	}
}
