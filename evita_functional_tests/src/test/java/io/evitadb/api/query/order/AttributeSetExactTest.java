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

package io.evitadb.api.query.order;

import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static io.evitadb.api.query.QueryConstraints.attributeSetExact;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link AttributeSetExact} ordering constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class AttributeSetExactTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeSetExact attributeSetExact = attributeSetExact("age", 18, 45, 13);
		assertEquals("age", attributeSetExact.getAttributeName());
		assertArrayEquals(new Serializable[] { 18, 45, 13 }, attributeSetExact.getAttributeValues());
		assertNull(attributeSetExact("age"));
		assertNull(attributeSetExact(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(attributeSetExact("age", 18, 45, 13).isApplicable());
		assertFalse(new AttributeSetExact("married").isApplicable());
		assertFalse(new AttributeSetExact(null).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeSetExact attributeSetExact = attributeSetExact("age", 18, 45, 13);
		assertEquals("attributeSetExact('age',18,45,13)", attributeSetExact.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeSetExact("age", 18, 45, 13), attributeSetExact("age", 18, 45, 13));
		assertEquals(attributeSetExact("age", 18, 45, 13), attributeSetExact("age", 18, 45, 13));
		assertNotEquals(attributeSetExact("age", 18, 45, 13), attributeSetExact("single", 18, 45, 13));
		assertNotEquals(attributeSetExact("age", 18, 45, 13), attributeSetExact("single", 18, 45));
		assertNotEquals(attributeSetExact("age", 18, 45, 13), attributeSetExact(null));
		assertEquals(attributeSetExact("age", 18, 45, 13).hashCode(), attributeSetExact("age", 18, 45, 13).hashCode());
		assertNotEquals(attributeSetExact("age", 18, 45, 13).hashCode(), attributeSetExact("single", 18, 45, 13).hashCode());
		assertNotEquals(attributeSetExact("age", 18, 45, 13).hashCode(), attributeSetExact("age", 18, 45).hashCode());
		assertNotEquals(attributeSetExact("age", 18, 45, 13).hashCode(), new AttributeSetExact(null).hashCode());
	}

}
