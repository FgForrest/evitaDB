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

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies bounded immutable integer storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Exact integer values")
@Tag(ENGINE)
@Tag(CACHE)
class IntValuesTest {
	/** Checks copying, ordering, equality and the content boundary. */
	@Test
	@DisplayName("Owns copied content and compares it by value")
	void shouldOwnIntegerContent() {
		final int[] source = {1, 2};
		final IntValues values = new IntValues(source);
		source[0] = 9;
		final IntValues equal = new IntValues(new int[]{1, 2});
		assertEquals(values, equal);
		assertEquals(values.hashCode(), equal.hashCode());
		assertEquals(values.hash64(), equal.hash64());
		assertEquals(2, values.size());
		assertEquals(0, values.compareTo(equal));
		assertTrue(values.compareTo(new IntValues(new int[]{2})) < 0);
		assertNotEquals(values, source);
		assertNotEquals(values, null);
		assertThrows(IllegalArgumentException.class, () -> new IntValues(new int[IdentityToken.CONTENT_LIMIT + 1]));
	}
}
