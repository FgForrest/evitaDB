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

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyParents} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyParentsTest {

	@Test
	void shouldCreateWithStopAtViaFactoryClassWorkAsExpected() {
		final HierarchyParents hierarchyParents = parents("megaMenu");
		assertEquals("megaMenu", hierarchyParents.getOutputName());
		assertFalse(hierarchyParents.getEntityFetch().isPresent());
		assertFalse(hierarchyParents.getStopAt().isPresent());
		assertFalse(hierarchyParents.getSiblings().isPresent());
		assertFalse(hierarchyParents.getStatistics().isPresent());
	}

	@Test
	void shouldCreateWithEntityFetchViaFactoryClassWorkAsExpected() {
		final HierarchyParents hierarchyParents = parents("megaMenu", entityFetchAll());
		assertEquals("megaMenu", hierarchyParents.getOutputName());
		assertFalse(hierarchyParents.getStopAt().isPresent());
		assertFalse(hierarchyParents.getSiblings().isPresent());
		assertEquals(entityFetchAll(), hierarchyParents.getEntityFetch().orElse(null));
	}

	@Test
	void shouldCreateWithStatisticsViaFactoryClassWorkAsExpected() {
		final HierarchyParents hierarchyParents = parents("megaMenu", statistics());
		assertEquals("megaMenu", hierarchyParents.getOutputName());
		assertFalse(hierarchyParents.getStopAt().isPresent());
		assertFalse(hierarchyParents.getSiblings().isPresent());
		assertFalse(hierarchyParents.getEntityFetch().isPresent());
		assertEquals(statistics(), hierarchyParents.getStatistics().orElse(null));
	}
	
	@Test
	void shouldCreateWithFilterByViaFactoryClassWorkAsExpected() {
		final HierarchyParents hierarchyParents = parents("megaMenu", stopAt(level(2)));
		assertEquals("megaMenu", hierarchyParents.getOutputName());
		assertEquals(stopAt(level(2)), hierarchyParents.getStopAt().orElse(null));
		assertFalse(hierarchyParents.getSiblings().isPresent());
		assertFalse(hierarchyParents.getEntityFetch().isPresent());
		assertFalse(hierarchyParents.getStatistics().isPresent());
	}

	@Test
	void shouldCreateWithSiblingsByViaFactoryClassWorkAsExpected() {
		final HierarchyParents hierarchyParents = parents("megaMenu", siblings());
		assertEquals("megaMenu", hierarchyParents.getOutputName());
		assertFalse(hierarchyParents.getStopAt().isPresent());
		assertEquals(siblings(), hierarchyParents.getSiblings().orElse(null));
		assertFalse(hierarchyParents.getEntityFetch().isPresent());
		assertFalse(hierarchyParents.getStatistics().isPresent());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyParents(null).isApplicable());
		assertTrue(parents("megaMenu", stopAt(level(2))).isApplicable());
		assertTrue(parents("megaMenu", entityFetchAll()).isApplicable());
		assertTrue(parents("megaMenu", siblings()).isApplicable());
		assertTrue(parents("megaMenu", statistics()).isApplicable());
		assertTrue(parents("megaMenu").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyParents hierarchyParents = parents("megaMenu", stopAt(level(2)));
		assertEquals("parents('megaMenu',stopAt(level(2)))", hierarchyParents.toString());

		final HierarchyParents hierarchyParents2 = parents("megaMenu", entityFetchAll());
		assertEquals("parents('megaMenu',entityFetch(attributeContent(),associatedDataContent(),priceContent(ALL),referenceContent(),dataInLocales()))", hierarchyParents2.toString());

		final HierarchyParents hierarchyParents3 = parents("megaMenu", statistics());
		assertEquals("parents('megaMenu',statistics(WITHOUT_USER_FILTER))", hierarchyParents3.toString());

		final HierarchyParents hierarchyParents4 = parents("megaMenu", siblings());
		assertEquals("parents('megaMenu',siblings())", hierarchyParents4.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(parents("megaMenu"), parents("megaMenu"));
		assertEquals(parents("megaMenu"), parents("megaMenu"));
		assertEquals(parents("megaMenu", statistics()), parents("megaMenu", statistics()));
		assertNotEquals(parents("megaMenu"), parents("megaMenu", entityFetchAll()));
		assertNotEquals(parents("megaMenu"), parents("megaMenu", stopAt(level(2))));
		assertNotEquals(parents("megaMenu"), parents("megaMenu", statistics()));
		assertEquals(parents("megaMenu").hashCode(), parents("megaMenu").hashCode());
		assertNotEquals(parents("megaMenu").hashCode(), parents("megaMenu", stopAt(level(2))).hashCode());
		assertNotEquals(parents("megaMenu").hashCode(), parents("megaMenu", entityFetchAll()).hashCode());
		assertNotEquals(parents("megaMenu").hashCode(), parents("megaMenu", statistics()).hashCode());
	}

}
