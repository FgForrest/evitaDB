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

package io.evitadb.api.query.filter;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityLocaleEquals} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntityLocaleEqualsTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityLocaleEquals entityLocaleEquals = entityLocaleEquals(Locale.ENGLISH);
		assertEquals(Locale.ENGLISH, entityLocaleEquals.getLocale());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityLocaleEquals(Locale.ENGLISH).isApplicable());
		assertFalse(new EntityLocaleEquals(null).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityLocaleEquals entityLocaleEquals = entityLocaleEquals(Locale.ENGLISH);
		assertEquals("entityLocaleEquals('en')", entityLocaleEquals.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH));
		assertEquals(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH));
		assertNotEquals(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.FRANCE));
		assertNotEquals(entityLocaleEquals(Locale.ENGLISH), new EntityLocaleEquals(null));
		assertEquals(entityLocaleEquals(Locale.ENGLISH).hashCode(), entityLocaleEquals(Locale.ENGLISH).hashCode());
		assertNotEquals(entityLocaleEquals(Locale.ENGLISH).hashCode(), entityLocaleEquals(Locale.FRANCE).hashCode());
		assertNotEquals(entityLocaleEquals(Locale.ENGLISH).hashCode(), new EntityLocaleEquals(null).hashCode());
	}

}
