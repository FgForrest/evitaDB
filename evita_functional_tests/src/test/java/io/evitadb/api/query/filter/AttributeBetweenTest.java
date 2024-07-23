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

import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeBetween} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeBetweenTest {

	@Test
	void shouldFailToUseInvalidDataType() {
		assertThrows(UnsupportedDataTypeException.class, () -> attributeBetween("abc", new MockObject(), new MockObject()));
	}

	@Test
	void shouldAutomaticallyConvertDataType() {
		assertEquals(new BigDecimal("1.0"), attributeBetween("abc", 1f, 2f).getFrom());
		assertEquals(new BigDecimal("2.0"), attributeBetween("abc", 1f, 2f).getTo());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeBetween attributeBetween = attributeBetween("abc", 1, 2);
		assertEquals("abc", attributeBetween.getAttributeName());
		assertEquals(Integer.valueOf(1), attributeBetween.getFrom());
		assertEquals(Integer.valueOf(2), attributeBetween.getTo());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertNull(attributeBetween("abc", null, null));
		assertFalse(new AttributeBetween("abc", null, null).isApplicable());
		assertFalse(new AttributeBetween(null, "abc", "abc").isApplicable());
		assertFalse(new AttributeBetween(null, null, null).isApplicable());
		assertTrue(attributeBetween("abc", "def", "def").isApplicable());
		assertTrue(attributeBetween("abc", "def", null).isApplicable());
		assertTrue(attributeBetween("abc", null, "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("attributeBetween('abc','def','def')", attributeBetween("abc", "def", "def").toString());
		assertEquals("attributeBetween('abc',<NULL>,'def')", attributeBetween("abc", null, "def").toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeBetween("abc", "def", "def"), attributeBetween("abc", "def", "def"));
		assertEquals(attributeBetween("abc", "def", "def"), attributeBetween("abc", "def", "def"));
		assertNotEquals(attributeBetween("abc", "def", "def"), attributeBetween("abc", "defe", "defe"));
		assertNotEquals(attributeBetween("abc", "def", "def"), attributeBetween("abc", null, null));
		assertNotEquals(attributeBetween("abc", "def", "def"), attributeBetween(null, "abc", "abc"));
		assertEquals(attributeBetween("abc", null, "def"), attributeBetween("abc", null, "def"));
		assertEquals(attributeBetween("abc", "def", "def").hashCode(), attributeBetween("abc", "def", "def").hashCode());
		assertNotEquals(attributeBetween("abc", "def", "def").hashCode(), attributeBetween("abc", "defe", "defe").hashCode());
		assertNotEquals(attributeBetween("abc", "def", "def").hashCode(), new AttributeBetween("abc", null, null).hashCode());
		assertNotEquals(attributeBetween("abc", "def", "def").hashCode(), new AttributeBetween(null, "abc", "abc").hashCode());
		assertEquals(attributeBetween("abc", null, "def").hashCode(), attributeBetween("abc", null, "def").hashCode());
	}

}
