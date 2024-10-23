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
class EntityFetchTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityFetch entityFetch = entityFetch();
		assertNotNull(entityFetch);

		final EntityFetch entityFetch2 = entityFetch(attributeContentAll(), associatedDataContentAll());
		assertArrayEquals(new EntityContentRequire[]{attributeContentAll(), associatedDataContentAll()}, entityFetch2.getRequirements());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityFetch().isApplicable());
		assertTrue(entityFetch(attributeContentAll()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityFetch entityFetch = entityFetch();
		assertEquals("entityFetch()", entityFetch.toString());

		final EntityFetch entityFetch2 = entityFetch(attributeContentAll());
		assertEquals("entityFetch(attributeContentAll())", entityFetch2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityFetch(), entityFetch());
		assertEquals(entityFetch(), entityFetch());
		assertEquals(entityFetch().hashCode(), entityFetch().hashCode());
		assertEquals(entityFetch(attributeContentAll()), entityFetch(attributeContentAll()));
		assertEquals(entityFetch(attributeContentAll()).hashCode(), entityFetch(attributeContentAll()).hashCode());
		assertNotEquals(entityFetch(), entityFetch(attributeContentAll()));
		assertNotEquals(entityFetch().hashCode(), entityFetch(attributeContentAll()).hashCode());
	}

	@Test
	void shouldCombineWithNullReturnSelf() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		assertEquals(entityFetch, entityFetch.combineWith(null));
	}

	@Test
	void shouldCombineWithDifferentTypeThrowException() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		assertThrows(GenericEvitaInternalError.class, () -> entityFetch.combineWith(entityGroupFetchAll()));
	}

	@Test
	void shouldCloneWithArgumentsReturnNewInstance() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		EntityFetch clonedEntityFetch = (EntityFetch) entityFetch.cloneWithArguments(new Serializable[]{});
		assertNotSame(entityFetch, clonedEntityFetch);
		assertEquals(entityFetch, clonedEntityFetch);
	}

	@Test
	void shouldGetCopyWithNewChildrenReturnNewInstance() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		EntityFetch newEntityFetch = (EntityFetch) entityFetch.getCopyWithNewChildren(new RequireConstraint[]{attributeContentAll()}, new Constraint<?>[]{});
		assertNotSame(entityFetch, newEntityFetch);
		assertEquals(entityFetch, newEntityFetch);
	}

	@Test
	void shouldIsFullyContainedWithinReturnTrueForSameRequirements() {
		EntityFetch entityFetch = entityFetch(attributeContent("a"), hierarchyContent());
		assertTrue(entityFetch.isFullyContainedWithin(entityFetchAll()));
	}

	@Test
	void shouldIsFullyContainedWithinReturnFalseForDifferentRequirements() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		assertFalse(entityFetch.isFullyContainedWithin(entityFetch(associatedDataContentAll())));
	}

	@Test
	void shouldCorrectlyCombineWithAnotherRequirement() {
		assertEquals(
			entityFetch(),
			entityFetch().combineWith(entityFetch())
		);

		assertEquals(
			entityFetch(attributeContent("code", "name"), associatedDataContentAll()),
			entityFetch(attributeContent("code"), associatedDataContentAll()).combineWith(entityFetch(attributeContent("name")))
		);
	}

	@Test
	void shouldCombineWithEmptyRequirementsReturnSelf() {
		EntityFetch entityFetch = entityFetch();
		assertEquals(entityFetch, entityFetch.combineWith(entityFetch()));
	}

	@Test
	void shouldCombineWithNonEmptyRequirementsReturnCombinedInstance() {
		EntityFetch entityFetch1 = entityFetch(attributeContent("code"));
		EntityFetch entityFetch2 = entityFetch(attributeContent("name"));
		EntityFetch combinedEntityFetch = entityFetch1.combineWith(entityFetch2);
		assertNotSame(entityFetch1, combinedEntityFetch);
		assertNotSame(entityFetch2, combinedEntityFetch);
		assertEquals(entityFetch(attributeContent("code", "name")), combinedEntityFetch);
	}

	@Test
	void shouldCombineWithDifferentRequirementTypesReturnCombinedInstance() {
		EntityFetch entityFetch1 = entityFetch(attributeContent("code"));
		EntityFetch entityFetch2 = entityFetch(associatedDataContent("name"));
		EntityFetch combinedEntityFetch = entityFetch1.combineWith(entityFetch2);
		assertNotSame(entityFetch1, combinedEntityFetch);
		assertNotSame(entityFetch2, combinedEntityFetch);
		assertEquals(entityFetch(attributeContent("code"), associatedDataContent("name")), combinedEntityFetch);
	}

	@Test
	void shouldCloneWithArgumentsReturnNewInstanceWithSameRequirements() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		EntityFetch clonedEntityFetch = (EntityFetch) entityFetch.cloneWithArguments(new Serializable[]{});
		assertNotSame(entityFetch, clonedEntityFetch);
		assertArrayEquals(entityFetch.getRequirements(), clonedEntityFetch.getRequirements());
	}

	@Test
	void shouldGetCopyWithNewChildrenReturnNewInstanceWithNewChildren() {
		EntityFetch entityFetch = entityFetch(attributeContentAll());
		EntityFetch newEntityFetch = (EntityFetch) entityFetch.getCopyWithNewChildren(new RequireConstraint[]{associatedDataContentAll()}, new Constraint<?>[]{});
		assertNotSame(entityFetch, newEntityFetch);
		assertArrayEquals(new RequireConstraint[]{associatedDataContentAll()}, newEntityFetch.getRequirements());
	}

	@Test
	void shouldIsFullyContainedWithinReturnTrueForSubsetRequirements() {
		EntityFetch entityFetch1 = entityFetch(attributeContent("code", "name"));
		EntityFetch entityFetch2 = entityFetch(attributeContent("code"));
		assertTrue(entityFetch2.isFullyContainedWithin(entityFetch1));
	}

	@Test
	void shouldIsFullyContainedWithinReturnFalseForNonSubsetRequirements() {
		EntityFetch entityFetch1 = entityFetch(attributeContent("code"));
		EntityFetch entityFetch2 = entityFetch(attributeContent("name"));
		assertFalse(entityFetch1.isFullyContainedWithin(entityFetch2));
	}

}
