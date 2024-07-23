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

import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeLessThan} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeLessThanTest {

	@Test
	void shouldFailToUseInvalidDataType() {
		assertThrows(UnsupportedDataTypeException.class, () -> attributeLessThan("abc", new MockObject()));
	}

	@Test
	void shouldAutomaticallyConvertDataType() {
		assertEquals(new BigDecimal("1.0"), attributeLessThan("abc", 1f).getAttributeValue());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeLessThan attributeLessThan = attributeLessThan("abc", "def");
		assertEquals("abc", attributeLessThan.getAttributeName());
		assertEquals("def", attributeLessThan.getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeLessThan("abc", null).isApplicable());
		assertFalse(new AttributeLessThan(null, "abc").isApplicable());
		assertFalse(new AttributeLessThan(null, null).isApplicable());
		assertTrue(attributeLessThan("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeLessThan attributeLessThan = attributeLessThan("abc", "def");
		assertEquals("attributeLessThan('abc','def')", attributeLessThan.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeLessThan("abc", "def"), attributeLessThan("abc", "def"));
		assertEquals(attributeLessThan("abc", "def"), attributeLessThan("abc", "def"));
		assertNotEquals(attributeLessThan("abc", "def"), attributeLessThan("abc", "defe"));
		assertNotEquals(attributeLessThan("abc", "def"), new AttributeLessThan("abc", null));
		assertNotEquals(attributeLessThan("abc", "def"), new AttributeLessThan(null, "abc"));
		assertEquals(attributeLessThan("abc", "def").hashCode(), attributeLessThan("abc", "def").hashCode());
		assertNotEquals(attributeLessThan("abc", "def").hashCode(), attributeLessThan("abc", "defe").hashCode());
		assertNotEquals(attributeLessThan("abc", "def").hashCode(), new AttributeLessThan("abc", null).hashCode());
		assertNotEquals(attributeLessThan("abc", "def").hashCode(), new AttributeLessThan(null, "abc").hashCode());
	}

}
