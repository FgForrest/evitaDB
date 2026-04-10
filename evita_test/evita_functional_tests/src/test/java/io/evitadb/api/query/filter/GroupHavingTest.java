/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link GroupHaving} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class GroupHavingTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<FilterConstraint> groupHaving = groupHaving(
			attributeEquals("abc", "def")
		);
		assertNotNull(groupHaving);
		assertEquals(1, groupHaving.getChildrenCount());
		assertEquals("abc", ((AttributeEquals) groupHaving.getChildren()[0]).getAttributeName());
		assertEquals("def", ((AttributeEquals) groupHaving.getChildren()[0]).getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new GroupHaving(attributeEquals("abc", "def")).isApplicable());
		final GroupHaving hackedCopyByInternalAPI = (GroupHaving) new GroupHaving(attributeEquals("abc", "def")).getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
		assertFalse(hackedCopyByInternalAPI.isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new GroupHaving(attributeEquals("abc", "def")).isNecessary());
		final GroupHaving hackedCopyByInternalAPI = (GroupHaving) new GroupHaving(attributeEquals("abc", "def")).getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
		assertFalse(hackedCopyByInternalAPI.isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<FilterConstraint> groupHaving = groupHaving(
			attributeEquals("abc", '\'')
		);
		assertNotNull(groupHaving);
		assertEquals("groupHaving(attributeEquals('abc','\\''))", groupHaving.toString());
	}

	@Test
	void shouldConformToEqualsOrHashContract() {
		assertNotSame(createGroupHavingConstraint("def"), createGroupHavingConstraint("def"));
		assertEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint("def"));
		assertNotEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint("defe"));
		assertNotEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint(null));
		assertNotEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint(null));
		assertEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint("def").hashCode());
		assertNotEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint("defe").hashCode());
		assertNotEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint(null).hashCode());
		assertNotEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint(null).hashCode());
	}

	private static GroupHaving createGroupHavingConstraint(String value) {
		return groupHaving(new AttributeEquals("abc", value));
	}

}
