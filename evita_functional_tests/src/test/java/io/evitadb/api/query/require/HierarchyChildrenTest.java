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
 * This tests verifies basic properties of {@link HierarchyChildren} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyChildrenTest {

	@Test
	void shouldCreateWithStopAtViaFactoryClassWorkAsExpected() {
		final HierarchyChildren hierarchyChildren = children("megaMenu");
		assertEquals("megaMenu", hierarchyChildren.getOutputName());
		assertFalse(hierarchyChildren.getEntityFetch().isPresent());
		assertFalse(hierarchyChildren.getStopAt().isPresent());
		assertFalse(hierarchyChildren.getStatistics().isPresent());
	}

	@Test
	void shouldCreateWithEntityFetchViaFactoryClassWorkAsExpected() {
		final HierarchyChildren hierarchyChildren = children("megaMenu", entityFetchAll());
		assertEquals("megaMenu", hierarchyChildren.getOutputName());
		assertFalse(hierarchyChildren.getStopAt().isPresent());
		assertEquals(entityFetchAll(), hierarchyChildren.getEntityFetch().orElse(null));
		assertFalse(hierarchyChildren.getStatistics().isPresent());
	}

	@Test
	void shouldCreateWithStatisticsViaFactoryClassWorkAsExpected() {
		final HierarchyChildren hierarchyChildren = children("megaMenu", statistics());
		assertEquals("megaMenu", hierarchyChildren.getOutputName());
		assertFalse(hierarchyChildren.getStopAt().isPresent());
		assertFalse(hierarchyChildren.getEntityFetch().isPresent());
		assertEquals(statistics(StatisticsBase.WITHOUT_USER_FILTER), hierarchyChildren.getStatistics().orElse(null));
	}
	
	@Test
	void shouldCreateWithFilterByViaFactoryClassWorkAsExpected() {
		final HierarchyChildren hierarchyChildren = children("megaMenu", stopAt(level(2)));
		assertEquals("megaMenu", hierarchyChildren.getOutputName());
		assertEquals(stopAt(level(2)), hierarchyChildren.getStopAt().orElse(null));
		assertFalse(hierarchyChildren.getEntityFetch().isPresent());
		assertFalse(hierarchyChildren.getStatistics().isPresent());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyChildren(null).isApplicable());
		assertTrue(children("megaMenu", stopAt(level(2))).isApplicable());
		assertTrue(children("megaMenu", entityFetchAll()).isApplicable());
		assertTrue(children("megaMenu", statistics()).isApplicable());
		assertTrue(children("megaMenu").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyChildren hierarchyChildren = children("megaMenu", stopAt(level(2)));
		assertEquals("children('megaMenu',stopAt(level(2)))", hierarchyChildren.toString());

		final HierarchyChildren hierarchyChildren2 = children("megaMenu", entityFetchAll());
		assertEquals("children('megaMenu',entityFetch(attributeContent(),associatedDataContent(),priceContent(ALL),referenceContent(),dataInLocales()))", hierarchyChildren2.toString());

		final HierarchyChildren hierarchyChildren3 = children("megaMenu", statistics());
		assertEquals("children('megaMenu',statistics(WITHOUT_USER_FILTER,CHILDREN_COUNT,QUERIED_ENTITY_COUNT))", hierarchyChildren3.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(children("megaMenu"), children("megaMenu"));
		assertEquals(children("megaMenu"), children("megaMenu"));
		assertEquals(children("megaMenu", statistics()), children("megaMenu", statistics()));
		assertNotEquals(children("megaMenu"), children("megaMenu", entityFetchAll()));
		assertNotEquals(children("megaMenu"), children("megaMenu", stopAt(level(2))));
		assertNotEquals(children("megaMenu"), children("megaMenu", statistics()));
		assertEquals(children("megaMenu").hashCode(), children("megaMenu").hashCode());
		assertNotEquals(children("megaMenu").hashCode(), children("megaMenu", stopAt(level(2))).hashCode());
		assertNotEquals(children("megaMenu").hashCode(), children("megaMenu", entityFetchAll()).hashCode());
		assertNotEquals(children("megaMenu").hashCode(), children("megaMenu", statistics()).hashCode());
	}

}
