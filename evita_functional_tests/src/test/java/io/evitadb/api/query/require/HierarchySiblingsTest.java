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

import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.siblings;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchySiblings} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchySiblingsTest {

	@Test
	void shouldCreateWithStopAtViaFactoryClassWorkAsExpected() {
		final HierarchySiblings hierarchySiblings = siblings("megaMenu");
		assertEquals("megaMenu", hierarchySiblings.getOutputName());
		assertFalse(hierarchySiblings.getEntityFetch().isPresent());
		assertFalse(hierarchySiblings.getStatistics().isPresent());
	}

	@Test
	void shouldCreateWithEntityFetchViaFactoryClassWorkAsExpected() {
		final HierarchySiblings hierarchySiblings = siblings("megaMenu", entityFetchAll());
		assertEquals("megaMenu", hierarchySiblings.getOutputName());
		assertEquals(entityFetchAll(), hierarchySiblings.getEntityFetch().orElse(null));
	}

	@Test
	void shouldCreateWithStatisticsViaFactoryClassWorkAsExpected() {
		final HierarchySiblings hierarchySiblings = siblings("megaMenu", statistics());
		assertEquals("megaMenu", hierarchySiblings.getOutputName());
		assertFalse(hierarchySiblings.getEntityFetch().isPresent());
		assertEquals(statistics(), hierarchySiblings.getStatistics().orElse(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new HierarchySiblings(null).isApplicable());
		assertTrue(siblings("megaMenu", entityFetchAll()).isApplicable());
		assertTrue(siblings("megaMenu", statistics()).isApplicable());
		assertTrue(siblings("megaMenu").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchySiblings hierarchySiblings1 = siblings("megaMenu", entityFetchAll());
		assertEquals("siblings('megaMenu',entityFetch(attributeContent(),hierarchyContent(),associatedDataContent(),priceContent(ALL),referenceContent(),dataInLocales()))", hierarchySiblings1.toString());

		final HierarchySiblings hierarchySiblings2 = siblings("megaMenu", statistics());
		assertEquals("siblings('megaMenu',statistics(WITHOUT_USER_FILTER))", hierarchySiblings2.toString());

		final HierarchySiblings hierarchySiblings3 = siblings(statistics());
		assertEquals("siblings(statistics(WITHOUT_USER_FILTER))", hierarchySiblings3.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(siblings("megaMenu"), siblings("megaMenu"));
		assertEquals(siblings("megaMenu"), siblings("megaMenu"));
		assertEquals(siblings("megaMenu", statistics()), siblings("megaMenu", statistics()));
		assertNotEquals(siblings("megaMenu"), siblings("megaMenu", entityFetchAll()));
		assertNotEquals(siblings("megaMenu"), siblings("megaMenu", statistics()));
		assertEquals(siblings("megaMenu").hashCode(), siblings("megaMenu").hashCode());
		assertNotEquals(siblings("megaMenu").hashCode(), siblings("megaMenu", entityFetchAll()).hashCode());
		assertNotEquals(siblings("megaMenu").hashCode(), siblings("megaMenu", statistics()).hashCode());
	}

}
