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

import static io.evitadb.api.query.QueryConstraints.attributeIs;
import static io.evitadb.api.query.QueryConstraints.attributeIsNotNull;
import static io.evitadb.api.query.QueryConstraints.attributeIsNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeIs} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeIsTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeIs attributeIs = attributeIs("married", AttributeSpecialValue.NOT_NULL);
		assertEquals("married", attributeIs.getAttributeName());
		assertEquals(AttributeSpecialValue.NOT_NULL, attributeIs.getAttributeSpecialValue());

		final AttributeIs attributeIsNull = attributeIsNull("married");
		assertEquals("married", attributeIsNull.getAttributeName());
		assertEquals(AttributeSpecialValue.NULL, attributeIsNull.getAttributeSpecialValue());

		final AttributeIs attributeIsNotNull = attributeIsNotNull("married");
		assertEquals("married", attributeIsNotNull.getAttributeName());
		assertEquals(AttributeSpecialValue.NOT_NULL, attributeIsNotNull.getAttributeSpecialValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(attributeIsNull("married").isApplicable());
		assertFalse(new AttributeIs(null, AttributeSpecialValue.NULL).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeIs attributeIsNull = attributeIsNull("married");
		assertEquals("attributeIs('married',NULL)", attributeIsNull.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeIsNull("married"), attributeIsNull("married"));
		assertEquals(attributeIsNull("married"), attributeIsNull("married"));
		assertEquals(attributeIs("married", AttributeSpecialValue.NULL), attributeIsNull("married"));
		assertNotEquals(attributeIsNull("married"), attributeIsNull("single"));
		assertNotEquals(attributeIsNull("married"), attributeIsNull(null));
		assertNotEquals(attributeIsNull("married"), attributeIsNotNull("married"));
		assertEquals(attributeIsNull("married").hashCode(), attributeIsNull("married").hashCode());
		assertEquals(attributeIs("married", AttributeSpecialValue.NULL).hashCode(), attributeIsNull("married").hashCode());
		assertNotEquals(attributeIsNull("married").hashCode(), attributeIsNull("single").hashCode());
		assertNotEquals(attributeIsNull("married").hashCode(), new AttributeIs(null, AttributeSpecialValue.NULL).hashCode());
		assertNotEquals(attributeIsNull("married").hashCode(), attributeIsNotNull("married").hashCode());
	}

}
