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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.facetCalculationRules;
import static io.evitadb.api.query.require.FacetRelationType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetCalculationRules} verifying construction, applicability, getters,
 * clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("FacetCalculationRules constraint")
class FacetCalculationRulesTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with explicit relation types")
		void shouldCreateWithExplicitRelationTypes() {
			final FacetCalculationRules rules = facetCalculationRules(EXCLUSIVITY, NEGATION);

			assertEquals(EXCLUSIVITY, rules.getFacetsWithSameGroupRelationType());
			assertEquals(NEGATION, rules.getFacetsWithDifferentGroupsRelationType());
		}

		@Test
		@DisplayName("should default nulls to DISJUNCTION and CONJUNCTION")
		void shouldDefaultNullsToDisjunctionAndConjunction() {
			final FacetCalculationRules rules = facetCalculationRules(null, null);

			assertEquals(DISJUNCTION, rules.getFacetsWithSameGroupRelationType());
			assertEquals(CONJUNCTION, rules.getFacetsWithDifferentGroupsRelationType());
		}

		@Test
		@DisplayName("should default only null sameGroup to DISJUNCTION")
		void shouldDefaultOnlyNullSameGroupToDisjunction() {
			final FacetCalculationRules rules = facetCalculationRules(null, NEGATION);

			assertEquals(DISJUNCTION, rules.getFacetsWithSameGroupRelationType());
			assertEquals(NEGATION, rules.getFacetsWithDifferentGroupsRelationType());
		}

		@Test
		@DisplayName("should default only null differentGroups to CONJUNCTION")
		void shouldDefaultOnlyNullDifferentGroupsToConjunction() {
			final FacetCalculationRules rules = facetCalculationRules(NEGATION, null);

			assertEquals(NEGATION, rules.getFacetsWithSameGroupRelationType());
			assertEquals(CONJUNCTION, rules.getFacetsWithDifferentGroupsRelationType());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable regardless of null arguments")
		void shouldAlwaysBeApplicable() {
			assertTrue(new FacetCalculationRules(null, null).isApplicable());
			assertTrue(new FacetCalculationRules(NEGATION, null).isApplicable());
			assertTrue(new FacetCalculationRules(null, NEGATION).isApplicable());
			assertTrue(new FacetCalculationRules(EXCLUSIVITY, NEGATION).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(RequireConstraint.class, facetCalculationRules(EXCLUSIVITY, NEGATION).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetCalculationRules rules = facetCalculationRules(EXCLUSIVITY, NEGATION);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			rules.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(rules, visited.get());
		}
	}

	@Nested
	@DisplayName("Clone operations")
	class CloneTest {

		@Test
		@DisplayName("should clone with new FacetRelationType arguments")
		void shouldCloneWithNewArguments() {
			final FacetCalculationRules original = facetCalculationRules(EXCLUSIVITY, NEGATION);
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{CONJUNCTION, DISJUNCTION}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(FacetCalculationRules.class, cloned);
			final FacetCalculationRules clonedRules = (FacetCalculationRules) cloned;
			assertEquals(CONJUNCTION, clonedRules.getFacetsWithSameGroupRelationType());
			assertEquals(DISJUNCTION, clonedRules.getFacetsWithDifferentGroupsRelationType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with explicit types")
		void shouldProduceToStringWithExplicitTypes() {
			assertEquals(
				"facetCalculationRules(EXCLUSIVITY,NEGATION)",
				facetCalculationRules(EXCLUSIVITY, NEGATION).toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with defaults")
		void shouldProduceToStringWithDefaults() {
			assertEquals(
				"facetCalculationRules(DISJUNCTION,CONJUNCTION)",
				facetCalculationRules(null, null).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				facetCalculationRules(null, null),
				facetCalculationRules(null, null)
			);
			assertEquals(
				facetCalculationRules(null, null),
				facetCalculationRules(null, null)
			);
			assertEquals(
				facetCalculationRules(EXCLUSIVITY, NEGATION),
				facetCalculationRules(EXCLUSIVITY, NEGATION)
			);
			// null defaults to DISJUNCTION/CONJUNCTION
			assertEquals(
				facetCalculationRules(DISJUNCTION, CONJUNCTION),
				facetCalculationRules(null, null)
			);
			assertNotEquals(
				facetCalculationRules(null, null),
				facetCalculationRules(NEGATION, EXCLUSIVITY)
			);
			assertNotEquals(
				facetCalculationRules(null, NEGATION),
				facetCalculationRules(CONJUNCTION, NEGATION)
			);
			assertNotEquals(
				facetCalculationRules(DISJUNCTION, EXCLUSIVITY),
				facetCalculationRules(DISJUNCTION, NEGATION)
			);
			assertEquals(
				facetCalculationRules(EXCLUSIVITY, NEGATION).hashCode(),
				facetCalculationRules(EXCLUSIVITY, NEGATION).hashCode()
			);
			assertNotEquals(
				facetCalculationRules(DISJUNCTION, EXCLUSIVITY).hashCode(),
				facetCalculationRules(DISJUNCTION, NEGATION).hashCode()
			);
		}
	}
}
