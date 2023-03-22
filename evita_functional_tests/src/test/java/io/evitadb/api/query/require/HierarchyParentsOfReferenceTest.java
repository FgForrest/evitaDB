/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.hierarchyParentsOfReference;
import static io.evitadb.api.query.QueryConstraints.priceContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyParentsOfReference} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyParentsOfReferenceTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final String[] classifier = {"brand"};
		final String[] classifiers = {"brand", "category"};

		final HierarchyParentsOfReference parents1 = hierarchyParentsOfReference("brand");
		assertArrayEquals(classifier, parents1.getReferenceNames());

		final HierarchyParentsOfReference parents2 = hierarchyParentsOfReference("brand", "category");
		assertArrayEquals(classifiers, parents2.getReferenceNames());

		final HierarchyParentsOfReference parents3 = hierarchyParentsOfReference("brand", entityFetch(attributeContent()));
		assertArrayEquals(classifier, parents3.getReferenceNames());
		assertEquals(entityFetch(attributeContent()), parents3.getEntityRequirement());

		final HierarchyParentsOfReference parents4 = hierarchyParentsOfReference(classifiers, entityFetch(attributeContent()));
		assertArrayEquals(classifiers, parents4.getReferenceNames());
		assertEquals(entityFetch(attributeContent()), parents4.getEntityRequirement());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(hierarchyParentsOfReference("brand").isApplicable());

		final String[] classifiers = {"brand", "category"};
		assertTrue(hierarchyParentsOfReference(classifiers).isApplicable());
		assertTrue(hierarchyParentsOfReference(classifiers, entityFetch()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final String[] classifiers = {"brand", "category"};
		final HierarchyParentsOfReference parents = hierarchyParentsOfReference(classifiers);
		assertEquals("hierarchyParentsOfReference('brand','category')", parents.toString());

		final HierarchyParentsOfReference parentsWithRequirements = hierarchyParentsOfReference(classifiers, entityFetch(priceContent()));
		assertEquals("hierarchyParentsOfReference('brand','category',entityFetch(priceContent(RESPECTING_FILTER)))", parentsWithRequirements.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		final String[] classifiers = {"brand", "category"};
		final String[] differentClassifiers = {"priceList", "category"};

		assertNotSame(hierarchyParentsOfReference("brand"), hierarchyParentsOfReference("brand"));
		assertEquals(hierarchyParentsOfReference(classifiers), hierarchyParentsOfReference(classifiers));
		assertNotEquals(hierarchyParentsOfReference("brand"), hierarchyParentsOfReference("category"));
		assertNotEquals(hierarchyParentsOfReference("brand"), hierarchyParentsOfReference(differentClassifiers));
		assertEquals(hierarchyParentsOfReference("brand").hashCode(), hierarchyParentsOfReference("brand").hashCode());
		assertNotEquals(hierarchyParentsOfReference("brand").hashCode(), hierarchyParentsOfReference("category").hashCode());
		assertNotEquals(hierarchyParentsOfReference("brand").hashCode(), hierarchyParentsOfReference(classifiers).hashCode());
	}

}
