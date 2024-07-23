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

import static io.evitadb.api.query.QueryConstraints.attributeLessThanEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeLessThanEquals} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeLessThanEqualsTest {

	@Test
	void shouldFailToUseInvalidDataType() {
		assertThrows(UnsupportedDataTypeException.class, () -> attributeLessThanEquals("abc", new MockObject()));
	}

	@Test
	void shouldAutomaticallyConvertDataType() {
		assertEquals(new BigDecimal("1.0"), attributeLessThanEquals("abc", 1f).getAttributeValue());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeLessThanEquals attributeLessThanEquals = attributeLessThanEquals("abc", "def");
		assertEquals("abc", attributeLessThanEquals.getAttributeName());
		assertEquals("def", attributeLessThanEquals.getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeLessThanEquals("abc", null).isApplicable());
		assertFalse(new AttributeLessThanEquals(null, "abc").isApplicable());
		assertFalse(new AttributeLessThanEquals(null, null).isApplicable());
		assertTrue(attributeLessThanEquals("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeLessThanEquals attributeLessThanEquals = attributeLessThanEquals("abc", "def");
		assertEquals("attributeLessThanEquals('abc','def')", attributeLessThanEquals.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeLessThanEquals("abc", "def"), attributeLessThanEquals("abc", "def"));
		assertEquals(attributeLessThanEquals("abc", "def"), attributeLessThanEquals("abc", "def"));
		assertNotEquals(attributeLessThanEquals("abc", "def"), attributeLessThanEquals("abc", "defe"));
		assertNotEquals(attributeLessThanEquals("abc", "def"), new AttributeLessThanEquals("abc", null));
		assertNotEquals(attributeLessThanEquals("abc", "def"), new AttributeLessThanEquals(null, "abc"));
		assertEquals(attributeLessThanEquals("abc", "def").hashCode(), attributeLessThanEquals("abc", "def").hashCode());
		assertNotEquals(attributeLessThanEquals("abc", "def").hashCode(), attributeLessThanEquals("abc", "defe").hashCode());
		assertNotEquals(attributeLessThanEquals("abc", "def").hashCode(), new AttributeLessThanEquals("abc", null).hashCode());
		assertNotEquals(attributeLessThanEquals("abc", "def").hashCode(), new AttributeLessThanEquals(null, "abc").hashCode());
	}

}
