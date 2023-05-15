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
 * This tests verifies basic properties of {@link HierarchyFromRoot} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyFromRootTest {

	@Test
	void shouldCreateWithStopAtViaFactoryClassWorkAsExpected() {
		final HierarchyFromRoot hierarchyFromRoot = fromRoot("megaMenu", stopAt(level(1)));
		assertEquals("megaMenu", hierarchyFromRoot.getOutputName());
		assertFalse(hierarchyFromRoot.getEntityFetch().isPresent());
		assertFalse(hierarchyFromRoot.getStatistics().isPresent());
		assertEquals(stopAt(level(1)), hierarchyFromRoot.getStopAt().orElse(null));
	}

	@Test
	void shouldCreateWithEntityFetchViaFactoryClassWorkAsExpected() {
		final HierarchyFromRoot hierarchyFromRoot = fromRoot("megaMenu", entityFetchAll());
		assertEquals("megaMenu", hierarchyFromRoot.getOutputName());
		assertFalse(hierarchyFromRoot.getStopAt().isPresent());
		assertFalse(hierarchyFromRoot.getStatistics().isPresent());
		assertEquals(entityFetchAll(), hierarchyFromRoot.getEntityFetch().orElse(null));
	}

	@Test
	void shouldCreateWithStatisticsViaFactoryClassWorkAsExpected() {
		final HierarchyFromRoot hierarchyFromRoot = fromRoot("megaMenu", statistics());
		assertEquals("megaMenu", hierarchyFromRoot.getOutputName());
		assertFalse(hierarchyFromRoot.getStopAt().isPresent());
		assertFalse(hierarchyFromRoot.getEntityFetch().isPresent());
		assertEquals(statistics(), hierarchyFromRoot.getStatistics().orElse(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyFromRoot(null).isApplicable());
		assertTrue(fromRoot("megaMenu", stopAt(level(1))).isApplicable());
		assertTrue(fromRoot("megaMenu", entityFetchAll()).isApplicable());
		assertTrue(fromRoot("megaMenu").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyFromRoot hierarchyFromRoot = fromRoot("megaMenu", stopAt(level(1)));
		assertEquals("fromRoot('megaMenu',stopAt(level(1)))", hierarchyFromRoot.toString());

		final HierarchyFromRoot hierarchyFromRoot2 = fromRoot("megaMenu", entityFetchAll());
		assertEquals("fromRoot('megaMenu',entityFetch(attributeContent(),hierarchyContent(),associatedDataContent(),priceContent(ALL),referenceContent(),dataInLocales()))", hierarchyFromRoot2.toString());

		final HierarchyFromRoot hierarchyFromRoot3 = fromRoot("megaMenu", statistics());
		assertEquals("fromRoot('megaMenu',statistics(WITHOUT_USER_FILTER))", hierarchyFromRoot3.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(fromRoot("megaMenu", stopAt(level(1))), fromRoot("megaMenu", stopAt(level(1))));
		assertEquals(fromRoot("megaMenu", stopAt(level(1))), fromRoot("megaMenu", stopAt(level(1))));
		assertNotEquals(fromRoot("megaMenu", stopAt(level(1))), fromRoot("megaMenu", entityFetchAll()));
		assertNotEquals(fromRoot("megaMenu", stopAt(level(1))), fromRoot("megaMenu", stopAt(distance(1))));
		assertEquals(fromRoot("megaMenu", stopAt(level(1))).hashCode(), fromRoot("megaMenu", stopAt(level(1))).hashCode());
		assertNotEquals(fromRoot("megaMenu", stopAt(level(1))).hashCode(), fromRoot("megaMenu", stopAt(level(2))).hashCode());
		assertNotEquals(fromRoot("megaMenu", stopAt(level(1))).hashCode(), fromRoot("megaMenu", entityFetchAll()).hashCode());
	}

}
