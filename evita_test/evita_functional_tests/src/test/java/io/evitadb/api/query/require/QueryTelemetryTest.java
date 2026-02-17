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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QueryTelemetry} verifying construction, applicability,
 * clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("QueryTelemetry constraint")
class QueryTelemetryTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create no-arg instance via factory")
		void shouldCreateNoArgInstanceViaFactory() {
			final QueryTelemetry telemetry = queryTelemetry();

			assertNotNull(telemetry);
			assertEquals(0, telemetry.getArguments().length);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			assertTrue(queryTelemetry().isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(RequireConstraint.class, queryTelemetry().getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final QueryTelemetry telemetry = queryTelemetry();
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			telemetry.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(telemetry, visited.get());
		}
	}

	@Nested
	@DisplayName("Clone operations")
	class CloneTest {

		@Test
		@DisplayName("should clone with new arguments")
		void shouldCloneWithNewArguments() {
			final QueryTelemetry original = queryTelemetry();
			final RequireConstraint cloned = original.cloneWithArguments(new Serializable[0]);

			assertInstanceOf(QueryTelemetry.class, cloned);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString")
		void shouldProduceExpectedToString() {
			assertEquals("queryTelemetry()", queryTelemetry().toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(queryTelemetry(), queryTelemetry());
			assertEquals(queryTelemetry(), queryTelemetry());
			assertEquals(queryTelemetry().hashCode(), queryTelemetry().hashCode());
		}
	}
}
