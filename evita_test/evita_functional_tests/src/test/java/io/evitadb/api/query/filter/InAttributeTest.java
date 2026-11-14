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
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.inAttribute;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InAttribute} verifying construction, the factory's null handling, applicability, cloning, visitor
 * support, string representation and the equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("InAttribute constraint")
@Tag(CONTRACT)
@Tag(FILTER)
class InAttributeTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		/** All names must survive both public construction paths. */
		@Test
		@DisplayName("should construct and create a selector with several searchable attributes")
		void shouldCreateWithSeveralAttributeNames() {
			final InAttribute constraint = new InAttribute("a", "b");
			assertArrayEquals(new String[]{"a", "b"}, constraint.getAttributeNames());
			assertEquals(constraint, inAttribute("a", "b"));
			assertTrue(constraint.isApplicable());
		}

		/** Every construction path refuses zero names and any null or blank name, also after a valid one. */
		@Test
		@DisplayName("should refuse zero names and any null or blank searchable attribute name")
		void shouldThrowWhenAnyAttributeNameIsMissing() {
			assertInvalidNames(() -> new InAttribute());
			assertInvalidNames(() -> inAttribute());
			assertInvalidNames(() -> inAttribute(new String[0]));
			assertInvalidNames(() -> inAttribute((String[]) null));
			assertInvalidNames(() -> new InAttribute((String[]) null));
			assertInvalidNames(() -> new InAttribute((String) null));
			for (final String invalid : new String[]{null, "", " ", "\t"}) {
				assertInvalidNames(() -> new InAttribute(invalid));
				assertInvalidNames(() -> new InAttribute("a", invalid));
				assertInvalidNames(() -> inAttribute("a", invalid));
				assertInvalidNames(() -> inAttribute(invalid));
			}
		}

		@Test
		@DisplayName("should create via factory")
		void shouldCreateViaFactory() {
			final InAttribute constraint = inAttribute("name");

			assertArrayEquals(new String[]{"name"}, constraint.getAttributeNames());
		}

		/** Names keep their order and repeats, and the returned array is a copy the caller may change. */
		@Test
		@DisplayName("should return searchable attribute names in supplied order, repeats included, as a copy")
		void shouldReturnAttributeNamesInSuppliedOrderAsCopy() {
			final InAttribute constraint = new InAttribute("a", "b", "a");

			final String[] names = constraint.getAttributeNames();
			names[0] = "changed";

			assertArrayEquals(new String[]{"a", "b", "a"}, constraint.getAttributeNames());
		}

		@Test
		@DisplayName("should be a text matches specification")
		void shouldBeTextMatchesSpecification() {
			assertInstanceOf(TextMatchesSpecificationFilterConstraint.class, inAttribute("name"));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with searchable attribute name")
		void shouldBeApplicableWithAttributeName() {
			assertTrue(inAttribute("name").isApplicable());
		}
	}

	@Nested
	@DisplayName("Cloning with arguments")
	class CloneWithArgumentsTest {

		/** Cloning retains every name and validates later arguments as strictly as constructors. */
		@Test
		@DisplayName("should clone with several searchable attribute names and validate every argument")
		void shouldCloneWithSeveralAttributeNames() {
			final InAttribute original = inAttribute("name");
			final InAttribute clone = (InAttribute) original.cloneWithArguments(new Serializable[]{"a", "b"});
			assertArrayEquals(new String[]{"a", "b"}, clone.getAttributeNames());
			assertTrue(clone.isApplicable());
			for (final Serializable invalid : new Serializable[]{null, "", " ", 1}) {
				assertInvalidNames(() -> original.cloneWithArguments(new Serializable[]{"a", invalid}));
			}
		}

		@Test
		@DisplayName("should create new instance with different searchable attribute name")
		void shouldCreateNewInstanceWithDifferentAttributeName() {
			final FilterConstraint clone = inAttribute("name").cloneWithArguments(new Serializable[]{"brand"});

			assertEquals(inAttribute("brand"), clone);
		}

		@Test
		@DisplayName("should throw when wrong arguments provided")
		void shouldThrowWhenWrongArgumentsProvided() {
			final InAttribute original = inAttribute("name");

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
			assertEquals(FilterConstraint.class, inAttribute("name").getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final InAttribute constraint = inAttribute("name");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format searchable attribute name")
		void shouldFormatAttributeName() {
			assertEquals("inAttribute('name')", inAttribute("name").toString());
			assertEquals("inAttribute('a','b')", inAttribute("a", "b").toString());
		}
	}

	@Nested
	@DisplayName("Equality and hash code")
	class EqualityTest {

		@Test
		@DisplayName("should equal constraint with same searchable attribute name")
		void shouldEqualConstraintWithSameAttributeName() {
			assertEquals(inAttribute("name"), inAttribute("name"));
			assertEquals(inAttribute("name").hashCode(), inAttribute("name").hashCode());
		}

		@Test
		@DisplayName("should not equal constraint with different searchable attribute name")
		void shouldNotEqualConstraintWithDifferentAttributeName() {
			assertNotEquals(inAttribute("name"), inAttribute("brand"));
			assertNotEquals(inAttribute("name").hashCode(), inAttribute("brand").hashCode());
		}

		@Test
		@DisplayName("should not equal constraint naming only the first of its searchable attributes")
		void shouldNotEqualConstraintWithFewerAttributeNames() {
			assertNotEquals(inAttribute("a", "b"), inAttribute("a"));
		}
	}

	/** Every invalid form gives the same actionable selector-specific diagnostic. */
	private static void assertInvalidNames(@Nonnull Executable action) {
		final EvitaInvalidUsageException exception = assertThrows(EvitaInvalidUsageException.class, action);
		assertTrue(exception.getMessage().contains("inAttribute"), exception.getMessage());
		assertTrue(exception.getMessage().contains("at least one searchable attribute name"), exception.getMessage());
	}

}
