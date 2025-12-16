/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import static io.evitadb.api.query.QueryConstraints.facetCalculationRules;
import static io.evitadb.api.query.require.FacetRelationType.CONJUNCTION;
import static io.evitadb.api.query.require.FacetRelationType.DISJUNCTION;
import static io.evitadb.api.query.require.FacetRelationType.EXCLUSIVITY;
import static io.evitadb.api.query.require.FacetRelationType.NEGATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link FacetCalculationRules} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class FacetCalculationRulesTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetCalculationRules facetCalculationRules1 = facetCalculationRules(EXCLUSIVITY, NEGATION);
		assertEquals(EXCLUSIVITY, facetCalculationRules1.getFacetsWithSameGroupRelationType());
		assertEquals(NEGATION, facetCalculationRules1.getFacetsWithDifferentGroupsRelationType());

		final FacetCalculationRules facetCalculationRules2 = facetCalculationRules(NEGATION, DISJUNCTION);
		assertEquals(NEGATION, facetCalculationRules2.getFacetsWithSameGroupRelationType());
		assertEquals(DISJUNCTION, facetCalculationRules2.getFacetsWithDifferentGroupsRelationType());

		final FacetCalculationRules facetCalculationRules3 = facetCalculationRules(null, null);
		assertEquals(DISJUNCTION, facetCalculationRules3.getFacetsWithSameGroupRelationType());
		assertEquals(CONJUNCTION, facetCalculationRules3.getFacetsWithDifferentGroupsRelationType());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new FacetCalculationRules(null, null).isApplicable());
		assertTrue(new FacetCalculationRules(NEGATION, null).isApplicable());
		assertTrue(new FacetCalculationRules(null, NEGATION).isApplicable());
		assertTrue(new FacetCalculationRules(EXCLUSIVITY, NEGATION).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetCalculationRules facetCalculationRules1 = facetCalculationRules(EXCLUSIVITY, NEGATION);
		assertEquals("facetCalculationRules(EXCLUSIVITY,NEGATION)", facetCalculationRules1.toString());

		final FacetCalculationRules facetCalculationRules2 = facetCalculationRules(null, null);
		assertEquals("facetCalculationRules(DISJUNCTION,CONJUNCTION)", facetCalculationRules2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetCalculationRules(null, null), facetCalculationRules(null, null));
		assertEquals(facetCalculationRules(null, null), facetCalculationRules(null, null));

		assertNotSame(facetCalculationRules(EXCLUSIVITY, NEGATION), facetCalculationRules(EXCLUSIVITY, NEGATION));
		assertEquals(facetCalculationRules(EXCLUSIVITY, NEGATION), facetCalculationRules(EXCLUSIVITY, NEGATION));

		assertEquals(facetCalculationRules(DISJUNCTION, CONJUNCTION), facetCalculationRules(null, null));

		assertNotEquals(facetCalculationRules(null, null), facetCalculationRules(NEGATION, EXCLUSIVITY));
		assertNotEquals(facetCalculationRules(null, NEGATION), facetCalculationRules(CONJUNCTION, NEGATION));
		assertNotEquals(facetCalculationRules(DISJUNCTION, EXCLUSIVITY), facetCalculationRules(DISJUNCTION, NEGATION));
	}

}
