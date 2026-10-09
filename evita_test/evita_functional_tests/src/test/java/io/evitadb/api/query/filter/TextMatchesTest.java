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
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.inField;
import static io.evitadb.api.query.QueryConstraints.textMatches;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TextMatches} verifying construction, the factory's null handling, property accessors,
 * applicability and necessity (which keep the constraint alive through query purification), child management,
 * cloning, visitor support, string representation and the equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TextMatches constraint")
@Tag(CONTRACT)
@Tag(FILTER)
class TextMatchesTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory without field restrictions")
		void shouldCreateViaFactoryWithoutFieldRestrictions() {
			final TextMatches constraint = textMatches("black leather jacket");

			assertNotNull(constraint);
			assertEquals("black leather jacket", constraint.getText());
			assertEquals(0, constraint.getChildrenCount());
		}

		@Test
		@DisplayName("should create via factory with field restrictions")
		void shouldCreateViaFactoryWithFieldRestrictions() {
			final TextMatches constraint = textMatches("jacket", inField("name"), inField("description"));

			assertNotNull(constraint);
			assertEquals(2, constraint.getChildrenCount());
		}

		@Test
		@DisplayName("should drop null field restrictions")
		void shouldDropNullFieldRestrictions() {
			final TextMatches constraint = textMatches("jacket", inField(null), inField("name"));

			assertNotNull(constraint);
			assertArrayEquals(new InField[]{inField("name")}, constraint.getInFields());
		}

		@Test
		@DisplayName("should create via constructor with null specification array")
		void shouldCreateViaConstructorWithNullSpecificationArray() {
			final TextMatches constraint = new TextMatches("jacket", (TextMatchesSpecificationFilterConstraint[]) null);

			assertEquals(0, constraint.getChildrenCount());
		}

		@Test
		@DisplayName("should return null from factory when text is null")
		void shouldReturnNullFromFactoryWhenTextIsNull() {
			assertNull(textMatches(null, inField("name")));
		}

		@Test
		@DisplayName("should return null from factory when text is blank")
		void shouldReturnNullFromFactoryWhenTextIsBlank() {
			assertNull(textMatches(""));
			assertNull(textMatches("   ", inField("name")));
		}

		@Test
		@DisplayName("should throw when constructed with blank text")
		void shouldThrowWhenConstructedWithBlankText() {
			assertThrows(EvitaInvalidUsageException.class, () -> new TextMatches(""));
			assertThrows(EvitaInvalidUsageException.class, () -> new TextMatches(" \t"));
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return text")
		void shouldReturnText() {
			assertEquals("černá kožená bunda", textMatches("černá kožená bunda").getText());
		}

		@Test
		@DisplayName("should return field restrictions in declaration order")
		void shouldReturnFieldRestrictionsInDeclarationOrder() {
			final TextMatches constraint = textMatches("jacket", inField("name"), inField("description"));

			assertArrayEquals(new InField[]{inField("name"), inField("description")}, constraint.getInFields());
			assertArrayEquals(
				new TextMatchesSpecificationFilterConstraint[]{inField("name"), inField("description")},
				constraint.getSpecifications()
			);
		}

		@Test
		@DisplayName("should return empty field restrictions when searching all fields")
		void shouldReturnEmptyFieldRestrictionsWhenSearchingAllFields() {
			final TextMatches constraint = textMatches("jacket");

			assertEquals(0, constraint.getInFields().length);
			assertEquals(0, constraint.getSpecifications().length);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable and necessary without children")
		void shouldBeApplicableAndNecessaryWithoutChildren() {
			final TextMatches constraint = textMatches("jacket");

			assertTrue(constraint.isApplicable());
			assertTrue(constraint.isNecessary());
		}

		@Test
		@DisplayName("should be applicable and necessary with a single child")
		void shouldBeApplicableAndNecessaryWithSingleChild() {
			final TextMatches constraint = textMatches("jacket", inField("name"));

			assertTrue(constraint.isApplicable());
			assertTrue(constraint.isNecessary());
		}

		@Test
		@DisplayName("should be applicable and necessary with multiple children")
		void shouldBeApplicableAndNecessaryWithMultipleChildren() {
			final TextMatches constraint = textMatches("jacket", inField("name"), inField("description"));

			assertTrue(constraint.isApplicable());
			assertTrue(constraint.isNecessary());
		}
	}

	@Nested
	@DisplayName("Query purification")
	class PurificationTest {

		@Test
		@DisplayName("should keep constraint without children")
		void shouldKeepConstraintWithoutChildren() {
			final TextMatches constraint = textMatches("jacket");

			assertSame(constraint, QueryPurifierVisitor.purify(constraint));
		}

		@Test
		@DisplayName("should not flatten constraint with a single child")
		void shouldNotFlattenConstraintWithSingleChild() {
			final TextMatches constraint = textMatches("jacket", inField("name"));

			assertEquals(constraint, QueryPurifierVisitor.purify(constraint));
		}

		@Test
		@DisplayName("should keep constraint inside a flattened container")
		void shouldKeepConstraintInsideFlattenedContainer() {
			final TextMatches constraint = textMatches("jacket", inField("name"));

			assertEquals(constraint, QueryPurifierVisitor.purify(and(constraint)));
		}
	}

	@Nested
	@DisplayName("Child management")
	class ChildManagementTest {

		@Test
		@DisplayName("should create copy with new children preserving text")
		void shouldCreateCopyWithNewChildrenPreservingText() {
			final TextMatches original = textMatches("jacket", inField("name"));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{inField("description")},
				new Constraint<?>[0]
			);

			assertEquals(textMatches("jacket", inField("description")), copy);
		}

		@Test
		@DisplayName("should create copy without children")
		void shouldCreateCopyWithoutChildren() {
			final TextMatches original = textMatches("jacket", inField("name"));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertEquals(textMatches("jacket"), copy);
		}

		@Test
		@DisplayName("should throw when child is not a text matches specification")
		void shouldThrowWhenChildIsNotTextMatchesSpecification() {
			final TextMatches original = textMatches("jacket");

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{attributeEquals("code", "A")},
					new Constraint<?>[0]
				)
			);
		}

		@Test
		@DisplayName("should throw when additional children provided")
		void shouldThrowWhenAdditionalChildrenProvided() {
			final TextMatches original = textMatches("jacket");

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[0],
					new Constraint<?>[]{attributeEquals("code", "A")}
				)
			);
		}
	}

	@Nested
	@DisplayName("Cloning with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should create new instance with different text preserving children")
		void shouldCreateNewInstanceWithDifferentTextPreservingChildren() {
			final TextMatches original = textMatches("jacket", inField("name"));

			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"coat"});

			assertEquals(textMatches("coat", inField("name")), clone);
		}

		@Test
		@DisplayName("should throw when wrong argument type provided")
		void shouldThrowWhenWrongArgumentTypeProvided() {
			final TextMatches original = textMatches("jacket");

			assertThrows(EvitaInvalidUsageException.class, () -> original.cloneWithArguments(new Serializable[]{1}));
		}

		@Test
		@DisplayName("should throw when argument count is wrong")
		void shouldThrowWhenArgumentCountIsWrong() {
			final TextMatches original = textMatches("jacket");

			assertThrows(EvitaInvalidUsageException.class, () -> original.cloneWithArguments(new Serializable[0]));
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{"a", "b"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, textMatches("jacket").getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final TextMatches constraint = textMatches("jacket", inField("name"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format without children")
		void shouldFormatWithoutChildren() {
			assertEquals("textMatches('jacket')", textMatches("jacket").toString());
		}

		@Test
		@DisplayName("should format with children and diacritics")
		void shouldFormatWithChildrenAndDiacritics() {
			assertEquals(
				"textMatches('černá kožená bunda',inField('name'),inField('brand'))",
				textMatches("černá kožená bunda", inField("name"), inField("brand")).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hash code")
	class EqualityTest {

		@Test
		@DisplayName("should equal identical constraint")
		void shouldEqualIdenticalConstraint() {
			assertEquals(textMatches("jacket", inField("name")), textMatches("jacket", inField("name")));
			assertEquals(
				textMatches("jacket", inField("name")).hashCode(),
				textMatches("jacket", inField("name")).hashCode()
			);
		}

		@Test
		@DisplayName("should not equal constraint with different text")
		void shouldNotEqualConstraintWithDifferentText() {
			assertNotEquals(textMatches("jacket"), textMatches("coat"));
			assertNotEquals(textMatches("jacket").hashCode(), textMatches("coat").hashCode());
		}

		@Test
		@DisplayName("should not equal constraint with different children")
		void shouldNotEqualConstraintWithDifferentChildren() {
			assertNotEquals(textMatches("jacket", inField("name")), textMatches("jacket", inField("brand")));
			assertNotEquals(textMatches("jacket", inField("name")), textMatches("jacket"));
		}
	}
}
