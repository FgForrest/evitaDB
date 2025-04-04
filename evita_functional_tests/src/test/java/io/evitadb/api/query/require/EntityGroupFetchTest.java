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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityFetch} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntityGroupFetchTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityGroupFetch entityGroupFetch = entityGroupFetch();
		assertNotNull(entityGroupFetch);

		final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContentAll(), associatedDataContentAll());
		assertArrayEquals(new EntityContentRequire[] {attributeContentAll(), associatedDataContentAll()}, entityGroupFetch2.getRequirements());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityGroupFetch().isApplicable());
		assertTrue(entityGroupFetch(attributeContentAll()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityGroupFetch entityGroupFetch = entityGroupFetch();
		assertEquals("entityGroupFetch()", entityGroupFetch.toString());

		final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContentAll());
		assertEquals("entityGroupFetch(attributeContentAll())", entityGroupFetch2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityGroupFetch(), entityGroupFetch());
		assertEquals(entityGroupFetch(), entityGroupFetch());
		assertEquals(entityGroupFetch().hashCode(), entityGroupFetch().hashCode());
		assertEquals(entityGroupFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()));
		assertEquals(entityGroupFetch(attributeContentAll()).hashCode(), entityGroupFetch(attributeContent()).hashCode());
		assertNotEquals(entityGroupFetch(), entityGroupFetch(attributeContent()));
		assertNotEquals(entityGroupFetch().hashCode(), entityGroupFetch(attributeContent()).hashCode());
	}

	@Test
	void shouldCombineWithNullReturnSelf() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		assertEquals(entityGroupFetch, entityGroupFetch.combineWith(null));
	}

	@Test
	void shouldCombineWithDifferentTypeThrowException() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		assertThrows(GenericEvitaInternalError.class, () -> entityGroupFetch.combineWith(entityFetchAll()));
	}

	@Test
	void shouldCloneWithArgumentsReturnNewInstance() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		EntityGroupFetch clonedEntityGroupFetch = (EntityGroupFetch) entityGroupFetch.cloneWithArguments(new Serializable[]{});
		assertNotSame(entityGroupFetch, clonedEntityGroupFetch);
		assertEquals(entityGroupFetch, clonedEntityGroupFetch);
	}

	@Test
	void shouldGetCopyWithNewChildrenReturnNewInstance() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		EntityGroupFetch newEntityGroupFetch = (EntityGroupFetch) entityGroupFetch.getCopyWithNewChildren(new RequireConstraint[]{attributeContentAll()}, new Constraint<?>[]{});
		assertNotSame(entityGroupFetch, newEntityGroupFetch);
		assertEquals(entityGroupFetch, newEntityGroupFetch);
	}

	@Test
	void shouldIsFullyContainedWithinReturnTrueForSameRequirements() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContent("a"), hierarchyContent());
		assertTrue(entityGroupFetch.isFullyContainedWithin(entityGroupFetchAll()));
	}

	@Test
	void shouldIsFullyContainedWithinReturnFalseForDifferentRequirements() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		assertFalse(entityGroupFetch.isFullyContainedWithin(entityGroupFetch(associatedDataContentAll())));
	}

	@Test
	void shouldCorrectlyCombineWithAnotherRequirement() {
		assertEquals(
			entityGroupFetch(),
			entityGroupFetch().combineWith(entityGroupFetch())
		);

		assertEquals(
			entityGroupFetch(attributeContent("code", "name"), associatedDataContentAll()),
			entityGroupFetch(attributeContent("code"), associatedDataContentAll()).combineWith(entityGroupFetch(attributeContent("name")))
		);
	}

	@Test
	void shouldCombineWithEmptyRequirementsReturnSelf() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch();
		assertEquals(entityGroupFetch, entityGroupFetch.combineWith(entityGroupFetch()));
	}

	@Test
	void shouldCombineWithNonEmptyRequirementsReturnCombinedInstance() {
		EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"));
		EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("name"));
		EntityGroupFetch combinedEntityGroupFetch = entityGroupFetch1.combineWith(entityGroupFetch2);
		assertNotSame(entityGroupFetch1, combinedEntityGroupFetch);
		assertNotSame(entityGroupFetch2, combinedEntityGroupFetch);
		assertEquals(entityGroupFetch(attributeContent("code", "name")), combinedEntityGroupFetch);
	}

	@Test
	void shouldCombineWithDifferentRequirementTypesReturnCombinedInstance() {
		EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"));
		EntityGroupFetch entityGroupFetch2 = entityGroupFetch(associatedDataContent("name"));
		EntityGroupFetch combinedEntityGroupFetch = entityGroupFetch1.combineWith(entityGroupFetch2);
		assertNotSame(entityGroupFetch1, combinedEntityGroupFetch);
		assertNotSame(entityGroupFetch2, combinedEntityGroupFetch);
		assertEquals(entityGroupFetch(attributeContent("code"), associatedDataContent("name")), combinedEntityGroupFetch);
	}

	@Test
	void shouldCloneWithArgumentsReturnNewInstanceWithSameRequirements() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		EntityGroupFetch clonedEntityGroupFetch = (EntityGroupFetch) entityGroupFetch.cloneWithArguments(new Serializable[]{});
		assertNotSame(entityGroupFetch, clonedEntityGroupFetch);
		assertArrayEquals(entityGroupFetch.getRequirements(), clonedEntityGroupFetch.getRequirements());
	}

	@Test
	void shouldGetCopyWithNewChildrenReturnNewInstanceWithNewChildren() {
		EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
		EntityGroupFetch newEntityGroupFetch = (EntityGroupFetch) entityGroupFetch.getCopyWithNewChildren(new RequireConstraint[]{associatedDataContentAll()}, new Constraint<?>[]{});
		assertNotSame(entityGroupFetch, newEntityGroupFetch);
		assertArrayEquals(new RequireConstraint[]{associatedDataContentAll()}, newEntityGroupFetch.getRequirements());
	}

	@Test
	void shouldIsFullyContainedWithinReturnTrueForSubsetRequirements() {
		EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code", "name"));
		EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("code"));
		assertTrue(entityGroupFetch2.isFullyContainedWithin(entityGroupFetch1));
	}

	@Test
	void shouldIsFullyContainedWithinReturnFalseForNonSubsetRequirements() {
		EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"));
		EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("name"));
		assertFalse(entityGroupFetch1.isFullyContainedWithin(entityGroupFetch2));
	}
}
