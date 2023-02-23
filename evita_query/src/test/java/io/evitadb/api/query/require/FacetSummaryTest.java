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

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetSummary} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetSummaryTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetSummary facetSummary = facetSummary();
		assertNotNull(facetSummary);
		final FacetSummary facetSummary2 = facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(), entityGroupFetch(attributeContent("code")));
		assertEquals(new FacetSummary(FacetStatisticsDepth.COUNTS, entityFetch(), entityGroupFetch(attributeContent("code"))), facetSummary2);
		final FacetSummary facetSummary3 = facetSummary(FacetStatisticsDepth.COUNTS, entityFetch());
		assertEquals(new FacetSummary(FacetStatisticsDepth.COUNTS, entityFetch()), facetSummary3);
		final FacetSummary facetSummary4 = facetSummary(FacetStatisticsDepth.COUNTS, entityGroupFetch(attributeContent("code")));
		assertEquals(new FacetSummary(FacetStatisticsDepth.COUNTS, entityGroupFetch(attributeContent("code"))), facetSummary4);
		final FacetSummary facetSummary5 = facetSummary(FacetStatisticsDepth.COUNTS);
		assertEquals(new FacetSummary(FacetStatisticsDepth.COUNTS), facetSummary5);
	}

	@Test
	void shouldCreateFromRequirements() {
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS),
			new FacetSummary(FacetStatisticsDepth.COUNTS, new EntityRequire[0])
		);
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))),
			new FacetSummary(FacetStatisticsDepth.COUNTS, new EntityRequire[] {
				entityFetch(attributeContent("code"))
			})
		);
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS, entityGroupFetch()),
			new FacetSummary(FacetStatisticsDepth.COUNTS, new EntityRequire[] {
				entityGroupFetch()
			})
		);
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")), entityGroupFetch()),
			new FacetSummary(FacetStatisticsDepth.COUNTS, new EntityRequire[] {
				entityFetch(attributeContent("code")),
				entityGroupFetch()
			})
		);
	}

	@Test
	void shouldNotCreateFromRequirements() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new FacetSummary(
				FacetStatisticsDepth.COUNTS,
				new EntityRequire[] { entityFetch(attributeContent("code")), entityFetch() }
			)
		);

		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new FacetSummary(
				FacetStatisticsDepth.COUNTS,
				new EntityRequire[] { entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name")), entityFetch() }
			)
		);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(facetSummary().isApplicable());
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT).isApplicable());
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).isApplicable());
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch(attributeContent("code"))).isApplicable());
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("code"))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("facetSummary(COUNTS)", facetSummary().toString());
		assertEquals("facetSummary(IMPACT,entityFetch())", facetSummary(FacetStatisticsDepth.IMPACT, entityFetch()).toString());
		assertEquals("facetSummary(IMPACT,entityFetch(attributeContent('code')),entityGroupFetch())", facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch()).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetSummary(), facetSummary());
		assertEquals(facetSummary(), facetSummary());
		assertNotEquals(facetSummary(), facetSummary(FacetStatisticsDepth.IMPACT));
		assertEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()));
		assertNotEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())));
		assertEquals(facetSummary().hashCode(), facetSummary().hashCode());
		assertEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode());
		assertNotEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).hashCode());
	}

	@Test
	void shouldReturnFacetEntityRequirement() {
		assertEquals(
			entityFetch(attributeContent("code")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getFacetEntityRequirement()
		);
		assertEquals(
			entityFetch(attributeContent("code")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getFacetEntityRequirement()
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getFacetEntityRequirement()
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT).getFacetEntityRequirement()
		);
	}

	@Test
	void shouldReturnGroupEntityRequirement() {
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement()
		);
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummary(FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement()
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getGroupEntityRequirement()
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getGroupEntityRequirement()
		);
	}

}