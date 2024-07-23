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

import static io.evitadb.api.query.QueryConstraints.attributeGreaterThan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeGreaterThan} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeGreaterThanTest {

	@Test
	void shouldFailToUseInvalidDataType() {
		assertThrows(UnsupportedDataTypeException.class, () -> attributeGreaterThan("abc", new MockObject()));
	}

	@Test
	void shouldAutomaticallyConvertDataType() {
		assertEquals(new BigDecimal("1.0"), attributeGreaterThan("abc", 1f).getAttributeValue());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeGreaterThan attributeGreaterThan = attributeGreaterThan("abc", "def");
		assertEquals("abc", attributeGreaterThan.getAttributeName());
		assertEquals("def", attributeGreaterThan.getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeGreaterThan("abc", null).isApplicable());
		assertFalse(new AttributeGreaterThan(null, "abc").isApplicable());
		assertFalse(new AttributeGreaterThan(null, null).isApplicable());
		assertTrue(attributeGreaterThan("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeGreaterThan attributeGreaterThan = attributeGreaterThan("abc", "def");
		assertEquals("attributeGreaterThan('abc','def')", attributeGreaterThan.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeGreaterThan("abc", "def"), attributeGreaterThan("abc", "def"));
		assertEquals(attributeGreaterThan("abc", "def"), attributeGreaterThan("abc", "def"));
		assertNotEquals(attributeGreaterThan("abc", "def"), attributeGreaterThan("abc", "defe"));
		assertNotEquals(attributeGreaterThan("abc", "def"), new AttributeGreaterThan("abc", null));
		assertNotEquals(attributeGreaterThan("abc", "def"), new AttributeGreaterThan(null, "abc"));
		assertEquals(attributeGreaterThan("abc", "def").hashCode(), attributeGreaterThan("abc", "def").hashCode());
		assertNotEquals(attributeGreaterThan("abc", "def").hashCode(), attributeGreaterThan("abc", "defe").hashCode());
		assertNotEquals(attributeGreaterThan("abc", "def").hashCode(), new AttributeGreaterThan("abc", null).hashCode());
		assertNotEquals(attributeGreaterThan("abc", "def").hashCode(), new AttributeGreaterThan(null, "abc").hashCode());
	}

}
