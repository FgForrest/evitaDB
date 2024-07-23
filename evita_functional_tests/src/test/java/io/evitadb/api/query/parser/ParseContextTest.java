/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.parser;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ParseContext}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ParseContextTest {

	@Test
	void shouldReturnNextPositionalArgument() {
		final ParseContext context1 = new ParseContext("name", 1);
		assertEquals("name", context1.getNextPositionalArgument());
		assertEquals(1, (Integer) context1.getNextPositionalArgument());

		final ParseContext context2 = new ParseContext(Map.of("name", "code"), "name", 1);
		assertEquals("name", context2.getNextPositionalArgument());
		assertEquals(1, (Integer) context2.getNextPositionalArgument());
	}

	@Test
	void shouldNotReturnNextPositionalArgument() {
		final ParseContext context1 = new ParseContext();
		assertThrows(EvitaInvalidUsageException.class, context1::getNextPositionalArgument);

		final ParseContext context2 = new ParseContext("code");
		context2.getNextPositionalArgument();
		assertThrows(EvitaInvalidUsageException.class, context2::getNextPositionalArgument);

		final ParseContext context3 = new ParseContext(new UnsupportedType());
		assertThrows(EvitaInvalidUsageException.class, context3::getNextPositionalArgument);
	}

	@Test
	void shouldReturnNamedArgument() {
		final ParseContext context1 = new ParseContext(Map.of("name", "code", "otherName", "validity"));
		assertEquals("validity", context1.getNamedArgument("otherName"));
		assertEquals("code", context1.getNamedArgument("name"));

		final ParseContext context2 = new ParseContext(Map.of("name", "code", "otherName", "validity"), "name", 1);
		assertEquals("validity", context2.getNamedArgument("otherName"));
		assertEquals("code", context2.getNamedArgument("name"));
	}

	@Test
	void shouldNotReturnNamedArgument() {
		final ParseContext context1 = new ParseContext();
		assertThrows(EvitaInvalidUsageException.class, () -> context1.getNamedArgument("name"));

		final ParseContext context2 = new ParseContext(Map.of("otherName", "validity"));
		assertThrows(EvitaInvalidUsageException.class, () -> context2.getNamedArgument("name"));

		final ParseContext context3 = new ParseContext(Map.of("name", new UnsupportedType()));
		assertThrows(EvitaInvalidUsageException.class, () -> context3.getNamedArgument("name"));
	}


	private static class UnsupportedType {}
}
