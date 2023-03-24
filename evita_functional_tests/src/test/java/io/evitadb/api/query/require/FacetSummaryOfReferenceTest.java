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

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityGroupFetch;
import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetSummary} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetSummaryOfReferenceTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetSummaryOfReference facetSummaryOfReference = facetSummaryOfReference("parameter");
		assertEquals(new FacetSummaryOfReference("parameter"), facetSummaryOfReference);
		final FacetSummaryOfReference facetSummaryOfReference2 = facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(), entityGroupFetch(attributeContent("code")));
		assertEquals(new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(), entityGroupFetch(attributeContent("code"))), facetSummaryOfReference2);
		final FacetSummaryOfReference facetSummaryOfReference3 = facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch());
		assertEquals(new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch()), facetSummaryOfReference3);
		final FacetSummaryOfReference facetSummaryOfReference4 = facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityGroupFetch(attributeContent("code")));
		assertEquals(new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityGroupFetch(attributeContent("code"))), facetSummaryOfReference4);
		final FacetSummaryOfReference facetSummaryOfReference5 = facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS);
		assertEquals(new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS), facetSummaryOfReference5);
	}

	@Test
	void shouldCreateFromRequirements() {
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, new EntityRequire[0])
		);
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, new EntityRequire[] {
				entityFetch(attributeContent("code"))
			})
		);
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityGroupFetch()),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, new EntityRequire[] {
				entityGroupFetch()
			})
		);
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")), entityGroupFetch()),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, new EntityRequire[] {
				entityFetch(attributeContent("code")),
				entityGroupFetch()
			})
		);
	}

	@Test
	void shouldNotCreateFromRequirements() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new FacetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				new EntityRequire[] { entityFetch(attributeContent("code")), entityFetch() }
			)
		);

		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new FacetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				new EntityRequire[] { entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name")), entityFetch() }
			)
		);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(facetSummaryOfReference("parameter").isApplicable());
		assertTrue(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).isApplicable());
		assertTrue(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).isApplicable());
		assertTrue(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("code"))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("facetSummaryOfReference('parameter',COUNTS)", facetSummaryOfReference("parameter").toString());
		assertEquals("facetSummaryOfReference('parameter',IMPACT,entityFetch())", facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch()).toString());
		assertEquals("facetSummaryOfReference('parameter',IMPACT,entityFetch(attributeContent('code')),entityGroupFetch())", facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch()).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter"));
		assertEquals(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter"));
		assertNotEquals(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT));
		assertEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()));
		assertNotEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())));
		assertEquals(facetSummaryOfReference("parameter").hashCode(), facetSummaryOfReference("parameter").hashCode());
		assertEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode());
		assertNotEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).hashCode());
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
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement()
		);
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement()
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getGroupEntityRequirement()
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT).getGroupEntityRequirement()
		);
	}

}