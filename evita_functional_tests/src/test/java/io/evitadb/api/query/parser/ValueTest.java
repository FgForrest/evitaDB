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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.parser;

import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link Value}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ValueTest {

	@Test
	void shouldAcceptAndReturnString() {
		final Value value = new Value("name");
		assertEquals(String.class, value.getType());
		assertEquals("name", value.getActualValue());
		assertEquals("name", value.asString());
		assertEquals("name", value.asComparable());
		assertEquals("name", value.asSerializableAndComparable());
	}

	@Test
	void shouldNotCastToDifferentType() {
		assertThrows(EvitaInternalError.class, () -> new Value("name").asLong());
	}

	@Test
	void shouldReturnLocale() {
		final Value locale = new Value(new Locale("cs", "CZ"));
		assertEquals(new Locale("cs", "CZ"), locale.asLocale());

		final Value stringLocale = new Value("cs-CZ");
		assertEquals(new Locale("cs", "CZ"), stringLocale.asLocale());
	}

	@Test
	void shouldNotReturnLocale() {
		assertThrows(EvitaInternalError.class, () -> new Value(1).asLocale());
		assertThrows(InconvertibleDataTypeException.class, () -> new Value("").asLocale());
		assertThrows(InconvertibleDataTypeException.class, () -> new Value("zz").asLocale());
	}

	@Test
	void shouldReturnIntegerArray() {
		final Value value = new Value(List.of(4, 5L));
		assertArrayEquals(new Integer[] { 4, 5 }, value.asIntegerArray());
	}

	@Test
	void shouldReturnLocaleArray() {
		final Value stringLocales = new Value(List.of("en", "fr"));
		assertArrayEquals(new Locale[] { Locale.ENGLISH, Locale.FRENCH }, stringLocales.asLocaleArray());

		final Value locales = new Value(List.of(Locale.ENGLISH, Locale.FRENCH));
		assertArrayEquals(new Locale[] { Locale.ENGLISH, Locale.FRENCH }, locales.asLocaleArray());
	}

	@Test
	void shouldNotReturnIntegerArray() {
		assertThrows(EvitaInternalError.class, () -> new Value(List.of(1, "b")).asIntegerArray());
	}
}