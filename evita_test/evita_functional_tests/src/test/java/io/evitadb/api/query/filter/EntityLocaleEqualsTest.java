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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityLocaleEquals} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityLocaleEquals constraint")
class EntityLocaleEqualsTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with locale via factory")
		void shouldCreateWithLocaleViaFactory() {
			final EntityLocaleEquals constraint = entityLocaleEquals(Locale.ENGLISH);

			assertNotNull(constraint);
			assertEquals(Locale.ENGLISH, constraint.getLocale());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return locale")
		void shouldReturnLocale() {
			final EntityLocaleEquals constraint = entityLocaleEquals(Locale.FRANCE);

			assertEquals(Locale.FRANCE, constraint.getLocale());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with non-null locale")
		void shouldBeApplicableWithNonNullLocale() {
			assertTrue(entityLocaleEquals(Locale.ENGLISH).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable with null locale")
		void shouldNotBeApplicableWithNullLocale() {
			assertFalse(new EntityLocaleEquals(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should clone with new locale argument")
		void shouldCloneWithNewLocaleArgument() {
			final EntityLocaleEquals original = entityLocaleEquals(Locale.ENGLISH);

			final FilterConstraint cloned = original.cloneWithArguments(
				new Serializable[]{Locale.FRANCE}
			);

			assertInstanceOf(EntityLocaleEquals.class, cloned);
			assertNotSame(original, cloned);
			assertEquals(Locale.FRANCE, ((EntityLocaleEquals) cloned).getLocale());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, entityLocaleEquals(Locale.ENGLISH).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final EntityLocaleEquals constraint = entityLocaleEquals(Locale.ENGLISH);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with locale")
		void shouldFormatWithLocale() {
			assertEquals("entityLocaleEquals('en')", entityLocaleEquals(Locale.ENGLISH).toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH));
			assertEquals(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH));
			assertNotEquals(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.FRANCE));
			assertNotEquals(entityLocaleEquals(Locale.ENGLISH), new EntityLocaleEquals(null));
			assertEquals(
				entityLocaleEquals(Locale.ENGLISH).hashCode(),
				entityLocaleEquals(Locale.ENGLISH).hashCode()
			);
			assertNotEquals(
				entityLocaleEquals(Locale.ENGLISH).hashCode(),
				entityLocaleEquals(Locale.FRANCE).hashCode()
			);
			assertNotEquals(
				entityLocaleEquals(Locale.ENGLISH).hashCode(),
				new EntityLocaleEquals(null).hashCode()
			);
		}
	}
}
