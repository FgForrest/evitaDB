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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityHaving} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntityHavingTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<FilterConstraint> entityHaving = entityHaving(
			attributeEquals("abc", "def")
		);
		assertNotNull(entityHaving);
		assertEquals(1, entityHaving.getChildrenCount());
		assertEquals("abc", ((AttributeEquals)entityHaving.getChildren()[0]).getAttributeName());
		assertEquals("def", ((AttributeEquals)entityHaving.getChildren()[0]).getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new EntityHaving(attributeEquals("abc", "def")).isApplicable());
		final EntityHaving hackedCopyByInternalAPI = (EntityHaving) new EntityHaving(attributeEquals("abc", "def")).getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
		assertFalse(hackedCopyByInternalAPI.isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new EntityHaving(attributeEquals("abc", "def")).isNecessary());
		final EntityHaving hackedCopyByInternalAPI = (EntityHaving) new EntityHaving(attributeEquals("abc", "def")).getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
		assertFalse(hackedCopyByInternalAPI.isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<FilterConstraint> entityHaving = entityHaving(
			attributeEquals("abc", '\'')
		);
		assertNotNull(entityHaving);
		assertEquals("entityHaving(attributeEquals('abc','\\''))", entityHaving.toString());
	}

	@Test
	void shouldConformToEqualsOrHashContract() {
		assertNotSame(createNotConstraint("def"), createNotConstraint("def"));
		assertEquals(createNotConstraint("def"), createNotConstraint("def"));
		assertNotEquals(createNotConstraint("def"), createNotConstraint("defe"));
		assertNotEquals(createNotConstraint("def"), createNotConstraint(null));
		assertNotEquals(createNotConstraint("def"), createNotConstraint(null));
		assertEquals(createNotConstraint("def").hashCode(), createNotConstraint("def").hashCode());
		assertNotEquals(createNotConstraint("def").hashCode(), createNotConstraint("defe").hashCode());
		assertNotEquals(createNotConstraint("def").hashCode(), createNotConstraint(null).hashCode());
		assertNotEquals(createNotConstraint("def").hashCode(), createNotConstraint(null).hashCode());
	}

	private static EntityHaving createNotConstraint(String value) {
		return entityHaving(new AttributeEquals("abc", value));
	}

}
