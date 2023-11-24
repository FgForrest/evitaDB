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
		final FacetSummary facetSummary6 = facetSummary(
			FacetStatisticsDepth.COUNTS,
			filterBy(entityPrimaryKeyInSet(1)),
			filterGroupBy(entityPrimaryKeyInSet(2)),
			orderBy(attributeNatural("code", OrderDirection.ASC)),
			orderGroupBy(attributeNatural("code", OrderDirection.ASC))
		);
		assertEquals(
			new FacetSummary(
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
			facetSummary(FacetStatisticsDepth.COUNTS),
			new FacetSummary(FacetStatisticsDepth.COUNTS, new EntityRequire[0])
		);
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))),
			new FacetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")))
		);
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS, entityGroupFetch()),
			new FacetSummary(FacetStatisticsDepth.COUNTS, entityGroupFetch())
		);
		assertEquals(
			facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")), entityGroupFetch()),
			new FacetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")),
				entityGroupFetch())
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
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll())).isApplicable());
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch(attributeContent("code"))).isApplicable());
		assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("code"))).isApplicable());
		assertTrue(
			facetSummary(
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
		assertEquals("facetSummary(COUNTS)", facetSummary().toString());
		assertEquals("facetSummary(IMPACT,entityFetch())", facetSummary(FacetStatisticsDepth.IMPACT, entityFetch()).toString());
		assertEquals("facetSummary(IMPACT,entityFetch(attributeContent('code')),entityGroupFetch())", facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch()).toString());
		assertEquals(
			"facetSummary(COUNTS,filterBy(entityPrimaryKeyInSet(1)),filterGroupBy(entityPrimaryKeyInSet(2)),orderBy(attributeNatural('code',ASC)),orderGroupBy(attributeNatural('code',ASC)))",
			facetSummary(
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
		assertNotSame(facetSummary(), facetSummary());
		assertEquals(facetSummary(), facetSummary());
		assertNotEquals(facetSummary(), facetSummary(FacetStatisticsDepth.IMPACT));
		assertEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch()), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch()));
		assertNotEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch()), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll())));
		assertNotEquals(
			facetSummary(
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			),
			facetSummary(
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(4)),
				filterGroupBy(entityPrimaryKeyInSet(3)),
				orderBy(attributeNatural("code", OrderDirection.DESC)),
				orderGroupBy(attributeNatural("code", OrderDirection.DESC))
			)
		);
		assertEquals(facetSummary().hashCode(), facetSummary().hashCode());
		assertEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch()).hashCode(), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode());
		assertNotEquals(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch()).hashCode(), facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).hashCode());
		assertNotEquals(
			facetSummary(
				FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			).hashCode(),
			facetSummary(
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
			facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getFacetEntityRequirement().orElse(null)
		);
		assertEquals(
			entityFetch(attributeContent("code")),
			facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getFacetEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getFacetEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getFacetEntityRequirement().orElse(null)
		);
	}

	@Test
	void shouldReturnGroupEntityRequirement() {
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement().orElse(null)
		);
		assertEquals(
			entityGroupFetch(attributeContent("name")),
			facetSummary(FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name"))).getGroupEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code"))).getGroupEntityRequirement().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getGroupEntityRequirement().orElse(null)
		);
	}

	@Test
	void shouldReturnFacetFilterConstraint() {
		assertEquals(
			filterBy(entityPrimaryKeyInSet(1)),
			facetSummary(FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1))).getFilterBy().orElse(null)
		);
		assertEquals(
			filterBy(entityPrimaryKeyInSet(1)),
			facetSummary(FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getFilterBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, orderBy(attributeNatural("code", OrderDirection.ASC))).getFilterBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getFilterBy().orElse(null)
		);
	}

	@Test
	void shouldReturnFacetFilterGroupConstraint() {
		assertEquals(
			filterGroupBy(entityPrimaryKeyInSet(1)),
			facetSummary(FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1))).getFilterGroupBy().orElse(null)
		);
		assertEquals(
			filterGroupBy(entityPrimaryKeyInSet(1)),
			facetSummary(FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1)), orderGroupBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getFilterGroupBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, orderBy(attributeNatural("code", OrderDirection.ASC))).getFilterGroupBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getFilterGroupBy().orElse(null)
		);
	}

	@Test
	void shouldReturnOrderFilterConstraint() {
		assertEquals(
			orderBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummary(FacetStatisticsDepth.IMPACT, orderBy(attributeNatural("code", OrderDirection.ASC))).getOrderBy().orElse(null)
		);
		assertEquals(
			orderBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummary(FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getOrderBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1))).getOrderBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getOrderBy().orElse(null)
		);
	}

	@Test
	void shouldReturnOrderGroupFilterConstraint() {
		assertEquals(
			orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummary(FacetStatisticsDepth.IMPACT, orderGroupBy(attributeNatural("code", OrderDirection.ASC))).getOrderGroupBy().orElse(null)
		);
		assertEquals(
			orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
			facetSummary(FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1)), orderGroupBy(attributeNatural("code", OrderDirection.ASC)), entityGroupFetch(attributeContent("name"))).getOrderGroupBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1))).getOrderGroupBy().orElse(null)
		);
		assertNull(
			facetSummary(FacetStatisticsDepth.IMPACT).getOrderGroupBy().orElse(null)
		);
	}

}