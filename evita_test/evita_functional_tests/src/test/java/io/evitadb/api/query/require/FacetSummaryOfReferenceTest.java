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
 * Tests for {@link FacetSummaryOfReference} verifying construction, applicability, getters,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FacetSummaryOfReference constraint")
class FacetSummaryOfReferenceTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with reference name only (defaults to COUNTS)")
		void shouldCreateWithReferenceNameOnly() {
			final FacetSummaryOfReference constraint = facetSummaryOfReference("parameter");

			assertEquals(new FacetSummaryOfReference("parameter"), constraint);
			assertEquals("parameter", constraint.getReferenceName());
			assertEquals(FacetStatisticsDepth.COUNTS, constraint.getStatisticsDepth());
		}

		@Test
		@DisplayName("should create via factory with statistics depth and entity requirements")
		void shouldCreateViaFactoryWithDepthAndRequirements() {
			final FacetSummaryOfReference constraint = facetSummaryOfReference(
				"parameter", FacetStatisticsDepth.COUNTS,
				entityFetch(), entityGroupFetch(attributeContent("code"))
			);

			assertEquals(
				new FacetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
					entityFetch(), entityGroupFetch(attributeContent("code"))
				),
				constraint
			);
		}

		@Test
		@DisplayName("should create via factory with filter, order, and entity requirements")
		void shouldCreateViaFactoryWithFilterAndOrder() {
			final FacetSummaryOfReference constraint = facetSummaryOfReference(
				"parameter", FacetStatisticsDepth.COUNTS,
				filterBy(entityPrimaryKeyInSet(1)),
				filterGroupBy(entityPrimaryKeyInSet(2)),
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				orderGroupBy(attributeNatural("code", OrderDirection.ASC))
			);

			assertEquals(
				new FacetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
					new FilterBy(new EntityPrimaryKeyInSet(1)),
					new FilterGroupBy(new EntityPrimaryKeyInSet(2)),
					new OrderBy(new AttributeNatural("code", OrderDirection.ASC)),
					new OrderGroupBy(new AttributeNatural("code", OrderDirection.ASC))
				),
				constraint
			);
		}

		@Test
		@DisplayName("should create from EntityFetchRequire array")
		void shouldCreateFromRequirements() {
			assertEquals(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS),
				new FacetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS)
			);
			assertEquals(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))),
				new FacetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS, entityFetch(attributeContent("code"))
				)
			);
			assertEquals(
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
					entityFetch(attributeContent("code")), entityGroupFetch()
				),
				new FacetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
					entityFetch(attributeContent("code")), entityGroupFetch()
				)
			);
		}

		@Test
		@DisplayName("should reject duplicate EntityFetch requirements")
		void shouldRejectDuplicateEntityFetchRequirements() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FacetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
					new EntityFetchRequire[]{entityFetch(attributeContent("code")), entityFetch()}
				)
			);
		}

		@Test
		@DisplayName("should reject more than two requirements when third is duplicate type")
		void shouldRejectMoreThanTwoRequirementsWithDuplicate() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FacetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
					new EntityFetchRequire[]{
						entityFetch(attributeContent("code")),
						entityGroupFetch(attributeContent("name")),
						entityFetch()
					}
				)
			);
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
				facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")))
					.getFacetEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT,
					entityGroupFetch(attributeContent("name")))
					.getFacetEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT)
					.getFacetEntityRequirement().orElse(null)
			);
		}

		@Test
		@DisplayName("should return group entity requirement when present")
		void shouldReturnGroupEntityRequirement() {
			assertEquals(
				entityGroupFetch(attributeContent("name")),
				facetSummaryOfReference(
					"param", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name"))
				).getGroupEntityRequirement().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("param", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent("code")))
					.getGroupEntityRequirement().orElse(null)
			);
		}

		@Test
		@DisplayName("should return filterBy when present")
		void shouldReturnFilterBy() {
			assertEquals(
				filterBy(entityPrimaryKeyInSet(1)),
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT, filterBy(entityPrimaryKeyInSet(1))
				).getFilterBy().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getFilterBy().orElse(null)
			);
		}

		@Test
		@DisplayName("should return filterGroupBy when present")
		void shouldReturnFilterGroupBy() {
			assertEquals(
				filterGroupBy(entityPrimaryKeyInSet(1)),
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT, filterGroupBy(entityPrimaryKeyInSet(1))
				).getFilterGroupBy().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getFilterGroupBy().orElse(null)
			);
		}

		@Test
		@DisplayName("should return orderBy when present")
		void shouldReturnOrderBy() {
			assertEquals(
				orderBy(attributeNatural("code", OrderDirection.ASC)),
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					orderBy(attributeNatural("code", OrderDirection.ASC))
				).getOrderBy().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getOrderBy().orElse(null)
			);
		}

		@Test
		@DisplayName("should return orderGroupBy when present")
		void shouldReturnOrderGroupBy() {
			assertEquals(
				orderGroupBy(attributeNatural("code", OrderDirection.ASC)),
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					orderGroupBy(attributeNatural("code", OrderDirection.ASC))
				).getOrderGroupBy().orElse(null)
			);
			assertNull(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT).getOrderGroupBy().orElse(null)
			);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when reference name is provided")
		void shouldBeApplicableWithReferenceName() {
			assertTrue(facetSummaryOfReference("parameter").isApplicable());
			assertTrue(
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContentAll())).isApplicable()
			);
			assertTrue(
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
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
			final FacetSummaryOfReference constraint = facetSummaryOfReference("parameter");

			final Serializable[] argsExcludingDefaults = constraint.getArgumentsExcludingDefaults();

			// Only "parameter" should remain, COUNTS is excluded
			assertEquals(1, argsExcludingDefaults.length);
			assertEquals("parameter", argsExcludingDefaults[0]);
		}

		@Test
		@DisplayName("should keep IMPACT as non-default argument")
		void shouldKeepImpactAsNonDefault() {
			final FacetSummaryOfReference constraint = facetSummaryOfReference(
				"parameter", FacetStatisticsDepth.IMPACT
			);

			final Serializable[] argsExcludingDefaults = constraint.getArgumentsExcludingDefaults();

			assertEquals(2, argsExcludingDefaults.length);
		}

		@Test
		@DisplayName("should recognize COUNTS as implicit argument")
		void shouldRecognizeCountsAsImplicit() {
			final FacetSummaryOfReference constraint = facetSummaryOfReference("parameter");

			assertTrue(constraint.isArgumentImplicit(FacetStatisticsDepth.COUNTS));
			assertFalse(constraint.isArgumentImplicit(FacetStatisticsDepth.IMPACT));
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(RequireConstraint.class, facetSummaryOfReference("parameter").getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetSummaryOfReference constraint = facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children preserving arguments and additional children")
		void shouldCreateCopyWithNewChildren() {
			final FacetSummaryOfReference original = facetSummaryOfReference(
				"parameter", FacetStatisticsDepth.IMPACT,
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
			assertInstanceOf(FacetSummaryOfReference.class, copy);
			final FacetSummaryOfReference copyRef = (FacetSummaryOfReference) copy;
			assertEquals("parameter", copyRef.getReferenceName());
			assertEquals(FacetStatisticsDepth.IMPACT, copyRef.getStatisticsDepth());
			assertEquals(1, copyRef.getChildren().length);
			assertTrue(copyRef.getFilterBy().isPresent());
		}

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArguments() {
			final FacetSummaryOfReference original = facetSummaryOfReference(
				"parameter", FacetStatisticsDepth.COUNTS, entityFetch()
			);
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{"other", FacetStatisticsDepth.IMPACT}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(FacetSummaryOfReference.class, cloned);
			assertEquals("other", ((FacetSummaryOfReference) cloned).getReferenceName());
			assertEquals(FacetStatisticsDepth.IMPACT, ((FacetSummaryOfReference) cloned).getStatisticsDepth());
			assertTrue(((FacetSummaryOfReference) cloned).getFacetEntityRequirement().isPresent());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with reference name only")
		void shouldProduceToStringWithNameOnly() {
			assertEquals(
				"facetSummaryOfReference('parameter')",
				facetSummaryOfReference("parameter").toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with IMPACT and entity requirements")
		void shouldProduceToStringWithImpact() {
			assertEquals(
				"facetSummaryOfReference('parameter',IMPACT,entityFetch())",
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch()).toString()
			);
			assertEquals(
				"facetSummaryOfReference('parameter',IMPACT,entityFetch(attributeContent('code')),entityGroupFetch())",
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContent("code")), entityGroupFetch()
				).toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with filter and order constraints")
		void shouldProduceToStringWithFilterAndOrder() {
			assertEquals(
				"facetSummaryOfReference('parameter',filterBy(entityPrimaryKeyInSet(1)),"
					+ "filterGroupBy(entityPrimaryKeyInSet(2)),orderBy(attributeNatural('code',ASC)),"
					+ "orderGroupBy(attributeNatural('code',ASC)))",
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.COUNTS,
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
			assertNotSame(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter"));
			assertEquals(facetSummaryOfReference("parameter"), facetSummaryOfReference("parameter"));
			assertNotEquals(
				facetSummaryOfReference("parameter"),
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT)
			);
			assertEquals(
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContentAll()), entityGroupFetch()
				),
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContentAll()), entityGroupFetch()
				)
			);
			assertNotEquals(
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContentAll()), entityGroupFetch()
				),
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContentAll()))
			);
		}

		@Test
		@DisplayName("should conform to hashCode contract")
		void shouldConformToHashCodeContract() {
			assertEquals(
				facetSummaryOfReference("parameter").hashCode(),
				facetSummaryOfReference("parameter").hashCode()
			);
			assertNotEquals(
				facetSummaryOfReference(
					"parameter", FacetStatisticsDepth.IMPACT,
					entityFetch(attributeContentAll()), entityGroupFetch()
				).hashCode(),
				facetSummaryOfReference("parameter", FacetStatisticsDepth.IMPACT, entityFetch(attributeContent()))
					.hashCode()
			);
		}
	}
}
