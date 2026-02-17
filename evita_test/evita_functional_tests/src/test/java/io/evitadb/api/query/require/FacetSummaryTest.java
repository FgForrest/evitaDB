/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetSummary} verifying construction, applicability, getters,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FacetSummary constraint")
class FacetSummaryTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory with no arguments (defaults to COUNTS)")
		void shouldCreateViaFactoryWithNoArguments() {
			final FacetSummary facetSummary = facetSummary();

			assertNotNull(facetSummary);
			assertEquals(FacetStatisticsDepth.COUNTS, facetSummary.getStatisticsDepth());
		}

		@Test
		@DisplayName("should create via factory with statistics depth and entity requirements")
		void shouldCreateViaFactoryWithDepthAndRequirements() {
			final FacetSummary facetSummary = facetSummary(
				FacetStatisticsDepth.COUNTS, entityFetch(), entityGroupFetch(attributeContent("code"))
			);

			assertEquals(
				new FacetSummary(FacetStatisticsDepth.COUNTS, entityFetch(), entityGroupFetch(attributeContent("code"))),
				facetSummary
			);
		}

		@Test
		@DisplayName("should create via factory with filter, order, and entity requirements")
		void shouldCreateViaFactoryWithFilterAndOrder() {
			final FacetSummary facetSummary = facetSummary(
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
				facetSummary
			);
		}

		@Test
		@DisplayName("should create from EntityFetchRequire array")
		void shouldCreateFromRequirements() {
			assertEquals(
				facetSummary(FacetStatisticsDepth.COUNTS),
				new FacetSummary(FacetStatisticsDepth.COUNTS, new EntityFetchRequire[0])
			);
			assertEquals(
				facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))),
				new FacetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")))
			);
			assertEquals(
				facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")), entityGroupFetch()),
				new FacetSummary(
					FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code")), entityGroupFetch()
				)
			);
		}

		@Test
		@DisplayName("should reject duplicate EntityFetch requirements")
		void shouldRejectDuplicateEntityFetchRequirements() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FacetSummary(
					FacetStatisticsDepth.COUNTS,
					new EntityFetchRequire[]{entityFetch(attributeContent("code")), entityFetch()}
				)
			);
		}

		@Test
		@DisplayName("should reject more than two requirements when third is duplicate type")
		void shouldRejectMoreThanTwoRequirementsWithDuplicate() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FacetSummary(
					FacetStatisticsDepth.COUNTS,
					new EntityFetchRequire[]{
						entityFetch(attributeContent("code")),
						entityGroupFetch(attributeContent("name")),
						entityFetch()
					}
				)
			);
		}

		@Test
		@DisplayName("should default to COUNTS when null statistics depth provided")
		void shouldDefaultToCountsWhenNullStatisticsDepth() {
			final FacetSummary facetSummary = new FacetSummary(null);

			assertEquals(FacetStatisticsDepth.COUNTS, facetSummary.getStatisticsDepth());
		}
	}

	@Nested
	@DisplayName("Getters")
	class GetterTest {

		@Test
		@DisplayName("should return facet entity requirement when present")
		void shouldReturnFacetEntityRequirement() {
			assertEquals(
				entityFetch(attributeContent("code")),
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")))
					.getFacetEntityRequirement().orElse(null)
			);
			assertEquals(
				entityFetch(attributeContent("code")),
				facetSummary(
					FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContent("code")),
					entityGroupFetch(attributeContent("name"))
				).getFacetEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT, entityGroupFetch(attributeContent("name")))
					.getFacetEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT).getFacetEntityRequirement().orElse(null)
			);
		}

		@Test
		@DisplayName("should return group entity requirement when present")
		void shouldReturnGroupEntityRequirement() {
			assertEquals(
				entityGroupFetch(attributeContent("name")),
				facetSummary(
					FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContent("code")),
					entityGroupFetch(attributeContent("name"))
				).getGroupEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")))
					.getGroupEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT).getGroupEntityRequirement().orElse(null)
			);
		}

		@Test
		@DisplayName("should return filterBy when present")
		void shouldReturnFilterBy() {
			assertEquals(
				filterBy(entityPrimaryKeyInSet(1)),
				facetSummary(FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1)))
					.getFilterBy().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT).getFilterBy().orElse(null)
			);
		}

		@Test
		@DisplayName("should return filterGroupBy when present")
		void shouldReturnFilterGroupBy() {
			assertEquals(
				filterGroupBy(entityPrimaryKeyInSet(1)),
				facetSummary(FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1)))
					.getFilterGroupBy().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT).getFilterGroupBy().orElse(null)
			);
		}

		@Test
		@DisplayName("should return orderBy when present")
		void shouldReturnOrderBy() {
			assertEquals(
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				facetSummary(
					FacetStatisticsDepth.IMPACT,
					orderBy(attributeNatural("code", OrderDirection.ASC))
				).getOrderBy().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT).getOrderBy().orElse(null)
			);
		}

		@Test
		@DisplayName("should return orderGroupBy when present")
		void shouldReturnOrderGroupBy() {
			assertEquals(
				orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
				facetSummary(
					FacetStatisticsDepth.IMPACT,
					orderGroupBy(attributeNatural("code", OrderDirection.ASC))
				).getOrderGroupBy().orElse(null)
			);
			assertNull(
				facetSummary(FacetStatisticsDepth.IMPACT).getOrderGroupBy().orElse(null)
			);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			assertTrue(facetSummary().isApplicable());
			assertTrue(facetSummary(FacetStatisticsDepth.IMPACT).isApplicable());
			assertTrue(facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll())).isApplicable());
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
	}

	@Nested
	@DisplayName("Default arguments")
	class DefaultArgumentsTest {

		@Test
		@DisplayName("should exclude COUNTS as implicit default argument")
		void shouldExcludeCountsAsDefault() {
			final FacetSummary facetSummary = facetSummary();

			final Serializable[] argsExcludingDefaults = facetSummary.getArgumentsExcludingDefaults();

			assertEquals(0, argsExcludingDefaults.length);
		}

		@Test
		@DisplayName("should keep IMPACT as non-default argument")
		void shouldKeepImpactAsNonDefault() {
			final FacetSummary facetSummary = facetSummary(FacetStatisticsDepth.IMPACT);

			final Serializable[] argsExcludingDefaults = facetSummary.getArgumentsExcludingDefaults();

			assertEquals(1, argsExcludingDefaults.length);
			assertEquals(FacetStatisticsDepth.IMPACT, argsExcludingDefaults[0]);
		}

		@Test
		@DisplayName("should recognize COUNTS as implicit argument")
		void shouldRecognizeCountsAsImplicit() {
			final FacetSummary facetSummary = facetSummary();

			assertTrue(facetSummary.isArgumentImplicit(FacetStatisticsDepth.COUNTS));
			assertFalse(facetSummary.isArgumentImplicit(FacetStatisticsDepth.IMPACT));
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(RequireConstraint.class, facetSummary().getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetSummary facetSummary = facetSummary(FacetStatisticsDepth.IMPACT);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			facetSummary.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(facetSummary, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children preserving arguments and additional children")
		void shouldCreateCopyWithNewChildren() {
			final FacetSummary original = facetSummary(
				FacetStatisticsDepth.IMPACT,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
				entityFetch(), entityGroupFetch()
			);
			final RequireConstraint copy = original.getCopyWithNewChildren(
				new RequireConstraint[]{entityFetch(attributeContent("name"))},
				original.getAdditionalChildren()
			);

			assertNotSame(original, copy);
			assertInstanceOf(FacetSummary.class, copy);
			final FacetSummary copyFacet = (FacetSummary) copy;
			assertEquals(FacetStatisticsDepth.IMPACT, copyFacet.getStatisticsDepth());
			assertEquals(1, copyFacet.getChildren().length);
			assertTrue(copyFacet.getFilterBy().isPresent());
		}

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArguments() {
			final FacetSummary original = facetSummary(FacetStatisticsDepth.COUNTS, entityFetch());
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{FacetStatisticsDepth.IMPACT}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(FacetSummary.class, cloned);
			assertEquals(FacetStatisticsDepth.IMPACT, ((FacetSummary) cloned).getStatisticsDepth());
			assertTrue(((FacetSummary) cloned).getFacetEntityRequirement().isPresent());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with default depth")
		void shouldProduceToStringWithDefaultDepth() {
			assertEquals("facetSummary()", facetSummary().toString());
		}

		@Test
		@DisplayName("should produce expected toString with IMPACT and entity requirements")
		void shouldProduceToStringWithImpact() {
			assertEquals(
				"facetSummary(IMPACT,entityFetch())",
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch()).toString()
			);
			assertEquals(
				"facetSummary(IMPACT,entityFetch(attributeContent('code')),entityGroupFetch())",
				facetSummary(
					FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContent("code")),
					entityGroupFetch()
				).toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with filter and order constraints")
		void shouldProduceToStringWithFilterAndOrder() {
			assertEquals(
				"facetSummary(filterBy(entityPrimaryKeyInSet(1)),filterGroupBy(entityPrimaryKeyInSet(2)),"
					+ "orderBy(attributeNatural('code',ASC)),orderGroupBy(attributeNatural('code',ASC)))",
				facetSummary(
					FacetStatisticsDepth.COUNTS,
					filterBy(entityPrimaryKeyInSet(1)),
					filterGroupBy(entityPrimaryKeyInSet(2)),
					orderBy(attributeNatural("code", OrderDirection.ASC)),
					orderGroupBy(attributeNatural("code", OrderDirection.ASC))
				).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(facetSummary(), facetSummary());
			assertEquals(facetSummary(), facetSummary());
			assertNotEquals(facetSummary(), facetSummary(FacetStatisticsDepth.IMPACT));
			assertEquals(
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch()),
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch())
			);
			assertNotEquals(
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch()),
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()))
			);
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
		}

		@Test
		@DisplayName("should conform to hashCode contract")
		void shouldConformToHashCodeContract() {
			assertEquals(facetSummary().hashCode(), facetSummary().hashCode());
			assertEquals(
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()), entityGroupFetch())
					.hashCode(),
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch())
					.hashCode()
			);
			assertNotEquals(
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()), entityGroupFetch())
					.hashCode(),
				facetSummary(FacetStatisticsDepth.IMPACT, entityFetch(attributeContent())).hashCode()
			);
		}
	}
}
