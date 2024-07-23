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

import static io.evitadb.api.query.QueryConstraints.attributeGreaterThanEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeGreaterThanEquals} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeGreaterThanEqualsTest {

	@Test
	void shouldFailToUseInvalidDataType() {
		assertThrows(UnsupportedDataTypeException.class, () -> attributeGreaterThanEquals("abc", new MockObject()));
	}

	@Test
	void shouldAutomaticallyConvertDataType() {
		assertEquals(new BigDecimal("1.0"), attributeGreaterThanEquals("abc", 1f).getAttributeValue());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeGreaterThanEquals attributeGreaterThanEquals = attributeGreaterThanEquals("abc", "def");
		assertEquals("abc", attributeGreaterThanEquals.getAttributeName());
		assertEquals("def", attributeGreaterThanEquals.getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeGreaterThanEquals("abc", null).isApplicable());
		assertFalse(new AttributeGreaterThanEquals(null, "abc").isApplicable());
		assertFalse(new AttributeGreaterThanEquals(null, null).isApplicable());
		assertTrue(attributeGreaterThanEquals("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeGreaterThanEquals attributeGreaterThanEquals = attributeGreaterThanEquals("abc", "def");
		assertEquals("attributeGreaterThanEquals('abc','def')", attributeGreaterThanEquals.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeGreaterThanEquals("abc", "def"), attributeGreaterThanEquals("abc", "def"));
		assertEquals(attributeGreaterThanEquals("abc", "def"), attributeGreaterThanEquals("abc", "def"));
		assertNotEquals(attributeGreaterThanEquals("abc", "def"), attributeGreaterThanEquals("abc", "defe"));
		assertNotEquals(attributeGreaterThanEquals("abc", "def"), new AttributeGreaterThanEquals("abc", null));
		assertNotEquals(attributeGreaterThanEquals("abc", "def"), new AttributeGreaterThanEquals(null, "abc"));
		assertEquals(attributeGreaterThanEquals("abc", "def").hashCode(), attributeGreaterThanEquals("abc", "def").hashCode());
		assertNotEquals(attributeGreaterThanEquals("abc", "def").hashCode(), attributeGreaterThanEquals("abc", "defe").hashCode());
		assertNotEquals(attributeGreaterThanEquals("abc", "def").hashCode(), new AttributeGreaterThanEquals("abc", null).hashCode());
		assertNotEquals(attributeGreaterThanEquals("abc", "def").hashCode(), new AttributeGreaterThanEquals(null, "abc").hashCode());
	}

}
