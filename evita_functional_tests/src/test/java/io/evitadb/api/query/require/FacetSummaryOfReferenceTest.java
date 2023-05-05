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

import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
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
		final FacetSummaryOfReference facetSummary6 = facetSummaryOfReference(
			"parameter",
			FacetStatisticsDepth.COUNTS,
			filterBy(entityPrimaryKeyInSet(1)),
			filterGroupBy(entityPrimaryKeyInSet(2)),
			orderBy(attributeNatural("code", OrderDirection.ASC)),
			orderGroupBy(attributeNatural("code", OrderDirection.ASC))
		);
		assertEquals(
			new FacetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				new FilterBy(new EntityPrimaryKeyInSet(1)),
				new FilterGroupBy(new EntityPrimaryKeyInSet(2)),
				new OrderBy(new AttributeNatural("code", OrderDirection.ASC)),
				new OrderGroupBy(new AttributeNatural("code", OrderDirection.ASC))
			),
			facetSummary6
		);
	}

	@Test
	void shouldCreateFromRequirements() {
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS)
		);
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")))
		);
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityGroupFetch()),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityGroupFetch())
		);
		assertEquals(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")), entityGroupFetch()),
			new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")),
				entityGroupFetch())
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
		assertTrue(
			facetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			).isApplicable()
		);
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("facetSummaryOfReference('parameter',COUNTS)", facetSummaryOfReference("parameter").toString());
		assertEquals("facetSummaryOfReference('parameter',IMPACT,entityFetch())", facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch()).toString());
		assertEquals("facetSummaryOfReference('parameter',IMPACT,entityFetch(attributeContent('code')),entityGroupFetch())", facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch()).toString());
		assertEquals(
			"facetSummaryOfReference('parameter',COUNTS,filterBy(entityPrimaryKeyInSet(1)),filterGroupBy(entityPrimaryKeyInSet(2)),orderBy(attributeNatural('code',ASC)),orderGroupBy(attributeNatural('code',ASC)))",
			facetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			).toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter"));
		assertEquals(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter"));
		assertNotEquals(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT));
		assertEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()));
		assertNotEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())));
		assertNotEquals(
			facetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			),
			facetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(4)),
				filterGroupBy(entityPrimaryKeyInSet(3)),
				orderBy(attributeNatural("code", OrderDirection.DESC)),
				orderGroupBy(attributeNatural("code", OrderDirection.DESC))
			)
		);
		assertEquals(facetSummaryOfReference("parameter").hashCode(), facetSummaryOfReference("parameter").hashCode());
		assertEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode());
		assertNotEquals(facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).hashCode());
		assertNotEquals(
			facetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			).hashCode(),
			facetSummaryOfReference(
				"parameter",
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(4)),
				filterGroupBy(entityPrimaryKeyInSet(3)),
				orderBy(attributeNatural("code", OrderDirection.DESC)),
				orderGroupBy(attributeNatural("code", OrderDirection.DESC))
			).hashCode()
		);
	}

	@Test
	void shouldReturnFacetEntityRequirement() {
		assertEquals(
			entityFetch(attributeContent("code")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getFacetEntityRequirement().orElse(null)
		);
		assertEquals(
			entityFetch(attributeContent("code")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getFacetEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getFacetEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT).getFacetEntityRequirement().orElse(null)
		);
	}

	@Test
	void shouldReturnGroupEntityRequirement() {
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement().orElse(null)
		);
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getGroupEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT).getGroupEntityRequirement().orElse(null)
		);
	}

	@Test
	void shouldReturnFacetFilterConstraint() {
		assertEquals(
			filterBy(entityPrimaryKeyInSet(1)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), null).getFilterBy().orElse(null)
		);
		assertEquals(
			filterBy(entityPrimaryKeyInSet(1)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getFilterBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, null, orderBy(attributeNatural("code", OrderDirection.ASC))).getFilterBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getFilterBy().orElse(null)
		);
	}

	@Test
	void shouldReturnFacetFilterGroupConstraint() {
		assertEquals(
			filterGroupBy(entityPrimaryKeyInSet(1)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1)), null).getFilterGroupBy().orElse(null)
		);
		assertEquals(
			filterGroupBy(entityPrimaryKeyInSet(1)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1)), orderGroupBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getFilterGroupBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, null, orderBy(attributeNatural("code", OrderDirection.ASC))).getFilterGroupBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getFilterGroupBy().orElse(null)
		);
	}

	@Test
	void shouldReturnOrderFilterConstraint() {
		assertEquals(
			orderBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, null, orderBy(attributeNatural("code", OrderDirection.ASC))).getOrderBy().orElse(null)
		);
		assertEquals(
			orderBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getOrderBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), null).getOrderBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getOrderBy().orElse(null)
		);
	}

	@Test
	void shouldReturnOrderGroupFilterConstraint() {
		assertEquals(
			orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, null, orderGroupBy(attributeNatural("code", OrderDirection.ASC))).getOrderGroupBy().orElse(null)
		);
		assertEquals(
			orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1)), orderGroupBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getOrderGroupBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), null).getOrderGroupBy().orElse(null)
		);
		assertNull(
			facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getOrderGroupBy().orElse(null)
		);
	}

}