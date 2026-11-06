/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.inField;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InField} verifying construction, the factory's null handling, applicability, cloning, visitor
 * support, string representation and the equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("InField constraint")
@Tag(CONTRACT)
@Tag(FILTER)
class InFieldTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory")
		void shouldCreateViaFactory() {
			final InField constraint = inField("name");

			assertEquals("name", constraint.getFieldName());
		}

		@Test
		@DisplayName("should return null from factory when field name is null")
		void shouldReturnNullFromFactoryWhenFieldNameIsNull() {
			assertNull(inField(null));
		}

		@Test
		@DisplayName("should throw when constructed with blank field name")
		void shouldThrowWhenConstructedWithBlankFieldName() {
			assertThrows(EvitaInvalidUsageException.class, () -> new InField(""));
			assertThrows(EvitaInvalidUsageException.class, () -> new InField("  "));
		}

		@Test
		@DisplayName("should be a text matches specification")
		void shouldBeTextMatchesSpecification() {
			assertInstanceOf(TextMatchesSpecificationFilterConstraint.class, inField("name"));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with field name")
		void shouldBeApplicableWithFieldName() {
			assertTrue(inField("name").isApplicable());
		}
	}

	@Nested
	@DisplayName("Cloning with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should create new instance with different field name")
		void shouldCreateNewInstanceWithDifferentFieldName() {
			final FilterConstraint clone = inField("name").cloneWithArguments(new Serializable[]{"brand"});

			assertEquals(inField("brand"), clone);
		}

		@Test
		@DisplayName("should throw when wrong arguments provided")
		void shouldThrowWhenWrongArgumentsProvided() {
			final InField original = inField("name");

			assertThrows(EvitaInvalidUsageException.class, () -> original.cloneWithArguments(new Serializable[]{1}));
			assertThrows(EvitaInvalidUsageException.class, () -> original.cloneWithArguments(new Serializable[0]));
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, inField("name").getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final InField constraint = inField("name");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format field name")
		void shouldFormatFieldName() {
			assertEquals("inField('name')", inField("name").toString());
		}
	}

	@Nested
	@DisplayName("Equality and hash code")
	class EqualityTest {

		@Test
		@DisplayName("should equal constraint with same field name")
		void shouldEqualConstraintWithSameFieldName() {
			assertEquals(inField("name"), inField("name"));
			assertEquals(inField("name").hashCode(), inField("name").hashCode());
		}

		@Test
		@DisplayName("should not equal constraint with different field name")
		void shouldNotEqualConstraintWithDifferentFieldName() {
			assertNotEquals(inField("name"), inField("brand"));
			assertNotEquals(inField("name").hashCode(), inField("brand").hashCode());
		}
	}
}
