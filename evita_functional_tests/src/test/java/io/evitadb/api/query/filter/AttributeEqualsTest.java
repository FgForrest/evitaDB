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

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeEquals} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeEqualsTest {

	@Test
	void shouldFailToUseInvalidDataType() {
		assertThrows(UnsupportedDataTypeException.class, () -> attributeEquals("abc", new MockObject()));
	}

	@Test
	void shouldAutomaticallyConvertDataType() {
		assertEquals(new BigDecimal("1.0"), attributeEquals("abc", 1f).getAttributeValue());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeEquals eq = attributeEquals("abc", "def");
		assertEquals("abc", eq.getAttributeName());
		assertEquals("def", eq.getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeEquals("abc", null).isApplicable());
		assertFalse(new AttributeEquals(null, "abc").isApplicable());
		assertFalse(new AttributeEquals(null, null).isApplicable());
		assertTrue(attributeEquals("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeEquals eq = attributeEquals("abc", "def");
		assertEquals("attributeEquals('abc','def')", eq.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeEquals("abc", "def"), attributeEquals("abc", "def"));
		assertEquals(attributeEquals("abc", "def"), attributeEquals("abc", "def"));
		assertNotEquals(attributeEquals("abc", "def"), attributeEquals("abc", "defe"));
		assertNotEquals(attributeEquals("abc", "def"), new AttributeEquals("abc", null));
		assertNotEquals(attributeEquals("abc", "def"), new AttributeEquals(null, "abc"));
		assertEquals(attributeEquals("abc", "def").hashCode(), attributeEquals("abc", "def").hashCode());
		assertNotEquals(attributeEquals("abc", "def").hashCode(), attributeEquals("abc", "defe").hashCode());
		assertNotEquals(attributeEquals("abc", "def").hashCode(), new AttributeEquals("abc", null).hashCode());
		assertNotEquals(attributeEquals("abc", "def").hashCode(), new AttributeEquals(null, "abc").hashCode());
	}

}
