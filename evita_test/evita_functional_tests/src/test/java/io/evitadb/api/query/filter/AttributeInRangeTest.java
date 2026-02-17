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
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.attributeInRangeNow;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeInRange} verifying construction, applicability, property accessors,
 * suffix behavior, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeInRange constraint")
class AttributeInRangeTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create 'now' moment variant via factory method")
		void shouldCreateNowMomentViaFactoryClassWorkAsExpected() {
			final AttributeInRange constraint = attributeInRangeNow("validity");

			assertEquals("validity", constraint.getAttributeName());
			assertNull(constraint.getTheMoment());
			assertNull(constraint.getTheValue());
		}

		@Test
		@DisplayName("should create OffsetDateTime variant via factory method")
		void shouldCreateMomentViaFactoryClassWorkAsExpected() {
			final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
			final AttributeInRange constraint = attributeInRange("validity", now);

			assertEquals("validity", constraint.getAttributeName());
			assertEquals(now, constraint.getTheMoment());
			assertNull(constraint.getTheValue());
		}

		@Test
		@DisplayName("should create Number variant via factory method")
		void shouldCreateNumberViaFactoryClassWorkAsExpected() {
			final AttributeInRange constraint = attributeInRange("age", 19);

			assertEquals("age", constraint.getAttributeName());
			assertEquals(19, constraint.getTheValue());
			assertNull(constraint.getTheMoment());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should recognize applicable and non-applicable instances")
		void shouldRecognizeApplicability() {
			assertFalse(new AttributeInRange(null, (Number) null).isApplicable());
			assertTrue(new AttributeInRange("validity").isApplicable());
			assertTrue(
				QueryConstraints.attributeInRange("validity", OffsetDateTime.now(ZoneOffset.UTC)).isApplicable()
			);
			assertTrue(attributeInRange("age", 19).isApplicable());
		}

		@Test
		@DisplayName("should report cloned instance with excess arguments as not applicable")
		void shouldReportClonedInstanceWithExcessArgumentsAsNotApplicable() {
			final AttributeInRange original = attributeInRange("age", 19);
			final FilterConstraint clonedWithExcessArgs =
				original.cloneWithArguments(new Serializable[]{"age", 19, 25});

			assertFalse(clonedWithExcessArgs.isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeInRange constraint = attributeInRange("age", 18);

			assertEquals("age", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return the moment for OffsetDateTime variant")
		void shouldReturnTheMoment() {
			final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
			final AttributeInRange constraint = attributeInRange("validity", now);

			assertEquals(now, constraint.getTheMoment());
		}

		@Test
		@DisplayName("should return null moment for Number variant")
		void shouldReturnNullMomentForNumberVariant() {
			final AttributeInRange constraint = attributeInRange("age", 18);

			assertNull(constraint.getTheMoment());
		}

		@Test
		@DisplayName("should return the value for Number variant")
		void shouldReturnTheValue() {
			final AttributeInRange constraint = attributeInRange("age", 18);

			assertEquals(18, constraint.getTheValue());
		}

		@Test
		@DisplayName("should return null value for OffsetDateTime variant")
		void shouldReturnNullValueForDateTimeVariant() {
			final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
			final AttributeInRange constraint = attributeInRange("validity", now);

			assertNull(constraint.getTheValue());
		}

		@Test
		@DisplayName("should return unknown argument for two-arg variant")
		void shouldReturnUnknownArgumentForTwoArgVariant() {
			final AttributeInRange constraint = attributeInRange("age", 18);

			assertEquals(18, constraint.getUnknownArgument());
		}

		@Test
		@DisplayName("should return null unknown argument for one-arg 'now' variant")
		void shouldReturnNullUnknownArgumentForNowVariant() {
			final AttributeInRange constraint = attributeInRangeNow("validity");

			assertNull(constraint.getUnknownArgument());
		}

		@Test
		@DisplayName("should return null moment and null value for 'now' variant")
		void shouldReturnNullMomentAndValueForNowVariant() {
			final AttributeInRange constraint = attributeInRangeNow("validity");

			assertNull(constraint.getTheMoment());
			assertNull(constraint.getTheValue());
		}
	}

	@Nested
	@DisplayName("Suffix")
	class SuffixTest {

		@Test
		@DisplayName("should return 'now' suffix for one-arg variant")
		void shouldReturnNowSuffixForOneArgVariant() {
			final AttributeInRange constraint = attributeInRangeNow("validity");

			assertEquals(Optional.of("now"), constraint.getSuffixIfApplied());
		}

		@Test
		@DisplayName("should return empty suffix for two-arg variant")
		void shouldReturnEmptySuffixForTwoArgVariant() {
			final AttributeInRange constraint = attributeInRange("age", 18);

			assertEquals(Optional.empty(), constraint.getSuffixIfApplied());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeInRange original = attributeInRange("age", 19);
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"age", 19});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeInRange.class, clone);
		}

		@Test
		@DisplayName("should clone 'now' variant via cloneWithArguments")
		void shouldCloneNowVariantWithArguments() {
			final AttributeInRange original = attributeInRangeNow("validity");
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"validity"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeInRange.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeInRange constraint = attributeInRange("age", 18);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> c) {
					visited.set(c);
				}
			});

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnCorrectType() {
			final AttributeInRange constraint = attributeInRange("age", 18);

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format for OffsetDateTime variant")
		void shouldToStringReturnExpectedFormatForDateTimeVariant() {
			final AttributeInRange constraint = QueryConstraints.attributeInRange(
				"validity", OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			);

			assertEquals("attributeInRange('validity',2021-01-01T00:00:00Z)", constraint.toString());
		}

		@Test
		@DisplayName("should produce expected toString format for Number variant")
		void shouldToStringReturnExpectedFormatForNumberVariant() {
			final AttributeInRange constraint = attributeInRange("age", 19);

			assertEquals("attributeInRange('age',19)", constraint.toString());
		}

		@Test
		@DisplayName("should produce expected toString format for 'now' variant")
		void shouldToStringReturnExpectedFormatForNowVariant() {
			final AttributeInRange constraint = attributeInRangeNow("validity");

			assertEquals("attributeInRangeNow('validity')", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeInRange("age", 19), attributeInRange("age", 19));
			assertEquals(attributeInRange("age", 19), attributeInRange("age", 19));
			assertNotEquals(attributeInRange("age", 19), attributeInRange("age", 16));
			assertNotEquals(
				attributeInRange("age", 19),
				QueryConstraints.attributeInRange("validity", OffsetDateTime.now(ZoneOffset.UTC))
			);
			assertEquals(attributeInRange("age", 19).hashCode(), attributeInRange("age", 19).hashCode());
			assertNotEquals(attributeInRange("age", 19).hashCode(), attributeInRange("age", 6).hashCode());
			assertNotEquals(
				attributeInRange("age", 19).hashCode(),
				attributeInRange("whatever", 19).hashCode()
			);
		}
	}
}
