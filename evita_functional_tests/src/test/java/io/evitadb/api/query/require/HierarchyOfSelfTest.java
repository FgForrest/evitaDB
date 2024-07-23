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

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link io.evitadb.api.query.require.HierarchyOfSelf} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyOfSelfTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyOfSelf hierarchyStatisticsOfSelf = hierarchyOfSelf(fromRoot("megaMenu", entityFetch(attributeContentAll())));
		assertArrayEquals(
			new HierarchyRequireConstraint[] {fromRoot("megaMenu", entityFetch(attributeContentAll()))},
			hierarchyStatisticsOfSelf.getRequirements()
		);
		assertFalse(hierarchyStatisticsOfSelf.getOrderBy().isPresent());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedWithOrdering() {
		final HierarchyOfSelf hierarchyStatisticsOfSelf = hierarchyOfSelf(
			orderBy(attributeNatural("name")),
			fromRoot("megaMenu", entityFetch(attributeContentAll()))
		);
		assertArrayEquals(
			new HierarchyRequireConstraint[] {fromRoot("megaMenu", entityFetch(attributeContentAll()))},
			hierarchyStatisticsOfSelf.getRequirements()
		);
		assertEquals(orderBy(attributeNatural("name")), hierarchyStatisticsOfSelf.getOrderBy().orElseThrow());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyOfSelf(new HierarchyRequireConstraint[0]).isApplicable());
		assertFalse(
			new HierarchyOfSelf(
				orderBy(attributeNatural("name")),
				new HierarchyRequireConstraint[0]).isApplicable()
		);
		assertTrue(hierarchyOfSelf(fromRoot("megaMenu", entityFetch(attributeContentAll()))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyOfSelf hierarchyStatisticsOfSelf = hierarchyOfSelf(fromRoot("megaMenu"));
		assertEquals("hierarchyOfSelf(fromRoot('megaMenu'))", hierarchyStatisticsOfSelf.toString());

		final HierarchyOfSelf hierarchyStatisticsOfSelf2 = hierarchyOfSelf(
			fromRoot("megaMenu", entityFetch(attributeContentAll()))
		);
		assertEquals(
			"hierarchyOfSelf(fromRoot('megaMenu',entityFetch(attributeContentAll())))",
			hierarchyStatisticsOfSelf2.toString()
		);

		final HierarchyOfSelf hierarchyStatisticsOfSelf3 = hierarchyOfSelf(
			orderBy(attributeNatural("name")),
			fromRoot("megaMenu", entityFetch(attributeContentAll()))
		);
		assertEquals(
			"hierarchyOfSelf(orderBy(attributeNatural('name',ASC)),fromRoot('megaMenu',entityFetch(attributeContentAll())))",
			hierarchyStatisticsOfSelf3.toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyOfSelf(fromRoot("megaMenu")), hierarchyOfSelf(fromRoot("megaMenu")));
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu")), hierarchyOfSelf(fromRoot("megaMenu")));
		assertNotEquals(hierarchyOfSelf(fromRoot("megaMenu", entityFetch(attributeContentAll()))), hierarchyOfSelf(fromRoot("megaMenu")));
		assertNotEquals(hierarchyOfSelf(orderBy(attributeNatural("name")), fromRoot("megaMenu", entityFetch(attributeContentAll()))), hierarchyOfSelf(fromRoot("megaMenu")));
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu")).hashCode(), hierarchyOfSelf(fromRoot("megaMenu")).hashCode());
		assertNotEquals(hierarchyOfSelf(fromRoot("megaMenu", entityFetch(attributeContentAll()))).hashCode(), hierarchyOfSelf(fromRoot("megaMenu")).hashCode());
		assertNotEquals(hierarchyOfSelf(orderBy(attributeNatural("name")), fromRoot("megaMenu", entityFetch(attributeContentAll()))).hashCode(), hierarchyOfSelf(fromRoot("megaMenu")).hashCode());
	}

}
