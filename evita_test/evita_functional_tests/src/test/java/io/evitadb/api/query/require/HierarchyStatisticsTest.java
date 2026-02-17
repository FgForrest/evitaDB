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
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.statistics;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyStatistics} verifying construction, applicability, visitor acceptance,
 * string representation, equality contract, and cloning behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchyStatistics constraint")
class HierarchyStatisticsTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via no-arg factory method with default statistics base")
		void shouldCreateViaNoArgFactory() {
			final HierarchyStatistics constraint = statistics();

			assertNotNull(constraint);
			assertEquals(StatisticsBase.WITHOUT_USER_FILTER, constraint.getStatisticsBase());
		}

		@Test
		@DisplayName("should create via factory method with explicit statistics base")
		void shouldCreateWithExplicitStatisticsBase() {
			final HierarchyStatistics constraint = statistics(StatisticsBase.COMPLETE_FILTER);

			assertNotNull(constraint);
			assertEquals(StatisticsBase.COMPLETE_FILTER, constraint.getStatisticsBase());
		}

		@Test
		@DisplayName("should create with statistics base and statistics types")
		void shouldCreateWithStatisticsBaseAndTypes() {
			final HierarchyStatistics constraint = new HierarchyStatistics(
				StatisticsBase.COMPLETE_FILTER,
				StatisticsType.CHILDREN_COUNT,
				StatisticsType.QUERIED_ENTITY_COUNT
			);

			assertEquals(StatisticsBase.COMPLETE_FILTER, constraint.getStatisticsBase());
			assertEquals(
				EnumSet.of(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT),
				constraint.getStatisticsType()
			);
		}

		@Test
		@DisplayName("should return empty statistics type set when no types specified")
		void shouldReturnEmptyStatisticsTypeSet() {
			final HierarchyStatistics constraint = statistics();

			assertTrue(constraint.getStatisticsType().isEmpty());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when statistics base is provided")
		void shouldRecognizeApplicability() {
			assertTrue(new HierarchyStatistics(StatisticsBase.COMPLETE_FILTER).isApplicable());
			assertTrue(statistics().isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyStatistics constraint = statistics();
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set(c));

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			assertEquals(RequireConstraint.class, statistics().getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should produce correct toString with defaults excluded")
		void shouldToStringReturnExpectedFormat() {
			final HierarchyStatistics hierarchyStatistics = statistics();

			assertEquals("statistics()", hierarchyStatistics.toString());
		}

		@Test
		@DisplayName("should include non-default statistics base in toString")
		void shouldIncludeNonDefaultStatisticsBase() {
			final HierarchyStatistics constraint =
				new HierarchyStatistics(StatisticsBase.COMPLETE_FILTER);

			assertEquals("statistics(COMPLETE_FILTER)", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(statistics(), statistics());
			assertEquals(statistics(), statistics());
			assertNotEquals(
				statistics(StatisticsBase.COMPLETE_FILTER),
				statistics(StatisticsBase.WITHOUT_USER_FILTER)
			);
			assertEquals(statistics().hashCode(), statistics().hashCode());
			assertNotEquals(
				statistics(StatisticsBase.COMPLETE_FILTER).hashCode(),
				statistics(StatisticsBase.WITHOUT_USER_FILTER).hashCode()
			);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return new instance from cloneWithArguments")
		void shouldReturnNewInstanceFromCloneWithArguments() {
			final HierarchyStatistics constraint =
				new HierarchyStatistics(StatisticsBase.COMPLETE_FILTER);

			final RequireConstraint cloned = constraint.cloneWithArguments(
				new Serializable[]{StatisticsBase.COMPLETE_FILTER}
			);

			assertNotSame(constraint, cloned);
			assertInstanceOf(HierarchyStatistics.class, cloned);
			assertEquals(
				StatisticsBase.COMPLETE_FILTER,
				((HierarchyStatistics) cloned).getStatisticsBase()
			);
		}
	}
}
