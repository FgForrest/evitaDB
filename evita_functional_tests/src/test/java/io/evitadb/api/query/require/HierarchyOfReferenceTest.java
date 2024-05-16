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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyOfSelf} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyOfReferenceTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyOfReference hierarchyStatisticsOfReference = hierarchyOfReference(
			"category", fromRoot("megaMenu")
		);
		assertArrayEquals(new String[] {"category"}, hierarchyStatisticsOfReference.getReferenceNames());
		assertFalse(hierarchyStatisticsOfReference.getOrderBy().isPresent());

		final HierarchyOfReference hierarchyStatisticsOfReferences = hierarchyOfReference(
			new String[] {"category", "brand"}, fromRoot("megaMenu")
		);
		assertArrayEquals(new String[] {"category", "brand"}, hierarchyStatisticsOfReferences.getReferenceNames());
		assertFalse(hierarchyStatisticsOfReferences.getOrderBy().isPresent());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedWithOrder() {
		final HierarchyOfReference hierarchyStatisticsOfReference = hierarchyOfReference(
			"category",
			orderBy(attributeNatural("name")),
			fromRoot("megaMenu")
		);
		assertArrayEquals(new String[] {"category"}, hierarchyStatisticsOfReference.getReferenceNames());
		assertEquals(orderBy(attributeNatural("name")), hierarchyStatisticsOfReference.getOrderBy().orElseThrow());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyOfReference(
			"category",
			EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
			new HierarchyRequireConstraint[0]).isApplicable()
		);
		assertFalse(
			new HierarchyOfReference(
				"category",
				EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
				orderBy(attributeNatural("name")),
				new HierarchyRequireConstraint[0]).isApplicable()
		);
		assertTrue(hierarchyOfReference("category", fromRoot("megaMenu", entityFetch(attributeContentAll()))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyOfReference hierarchyStatisticsOfReference = hierarchyOfReference(
			"brand", fromRoot("megaMenu")
		);
		assertEquals(
			"hierarchyOfReference('brand',REMOVE_EMPTY,fromRoot('megaMenu'))",
			hierarchyStatisticsOfReference.toString()
		);

		final HierarchyOfReference hierarchyStatisticsOfReference2 = hierarchyOfReference(
			"brand", orderBy(attributeNatural("name")), fromRoot("megaMenu")
		);
		assertEquals(
			"hierarchyOfReference('brand',REMOVE_EMPTY,orderBy(attributeNatural('name',ASC)),fromRoot('megaMenu'))",
			hierarchyStatisticsOfReference2.toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyOfReference("brand", fromRoot("megaMenu")), hierarchyOfReference("brand", fromRoot("megaMenu")));
		assertEquals(hierarchyOfReference("brand", fromRoot("megaMenu")), hierarchyOfReference("brand", fromRoot("megaMenu")));
		assertNotEquals(hierarchyOfReference("brand", fromRoot("megaMenu")), hierarchyOfReference("category", fromRoot("megaMenu")));
		assertNotEquals(hierarchyOfReference("brand", orderBy(attributeNatural("name")), fromRoot("megaMenu")), hierarchyOfReference("category", fromRoot("megaMenu")));
		assertNotEquals(hierarchyOfReference("brand", EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY, fromRoot("megaMenu")), hierarchyOfReference("category", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")));
		assertEquals(hierarchyOfReference("brand", fromRoot("megaMenu")).hashCode(), hierarchyOfReference("brand", fromRoot("megaMenu")).hashCode());
		assertNotEquals(hierarchyOfReference("brand", fromRoot("megaMenu")).hashCode(), hierarchyOfReference("category", fromRoot("megaMenu")).hashCode());
		assertNotEquals(hierarchyOfReference("brand", EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY, fromRoot("megaMenu")).hashCode(), hierarchyOfReference("category", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")).hashCode());
		assertNotEquals(hierarchyOfReference("brand", EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY, orderBy(attributeNatural("name")), fromRoot("megaMenu")).hashCode(), hierarchyOfReference("category", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")).hashCode());
	}

}
