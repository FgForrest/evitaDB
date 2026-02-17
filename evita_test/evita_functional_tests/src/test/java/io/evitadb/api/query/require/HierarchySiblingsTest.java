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
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchySiblings} verifying construction, applicability, visitor acceptance,
 * string representation, equality contract, and cloning behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchySiblings constraint")
class HierarchySiblingsTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with output name only")
		void shouldCreateWithOutputNameOnly() {
			final HierarchySiblings hierarchySiblings = siblings("megaMenu");

			assertEquals("megaMenu", hierarchySiblings.getOutputName());
			assertFalse(hierarchySiblings.getEntityFetch().isPresent());
			assertFalse(hierarchySiblings.getStatistics().isPresent());
		}

		@Test
		@DisplayName("should create with output name and entity fetch")
		void shouldCreateWithEntityFetch() {
			final HierarchySiblings hierarchySiblings = siblings("megaMenu", entityFetchAll());

			assertEquals("megaMenu", hierarchySiblings.getOutputName());
			assertEquals(entityFetchAll(), hierarchySiblings.getEntityFetch().orElse(null));
		}

		@Test
		@DisplayName("should create with output name and statistics")
		void shouldCreateWithStatistics() {
			final HierarchySiblings hierarchySiblings = siblings("megaMenu", statistics());

			assertEquals("megaMenu", hierarchySiblings.getOutputName());
			assertFalse(hierarchySiblings.getEntityFetch().isPresent());
			assertEquals(statistics(), hierarchySiblings.getStatistics().orElse(null));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			assertTrue(new HierarchySiblings(null).isApplicable());
			assertTrue(siblings("megaMenu", entityFetchAll()).isApplicable());
			assertTrue(siblings("megaMenu", statistics()).isApplicable());
			assertTrue(siblings("megaMenu").isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchySiblings constraint = siblings("megaMenu");
			final AtomicReference<Constraint<?>> firstVisited = new AtomicReference<>();

			constraint.accept(c -> {
				if (firstVisited.get() == null) {
					firstVisited.set(c);
				}
			});

			assertSame(constraint, firstVisited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			assertEquals(RequireConstraint.class, siblings("megaMenu").getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should produce correct toString with entity fetch")
		void shouldToStringReturnExpectedFormatWithEntityFetch() {
			final HierarchySiblings hierarchySiblings = siblings("megaMenu", entityFetchAll());

			assertEquals(
				"siblings('megaMenu',entityFetch(attributeContentAll(),hierarchyContent(),"
					+ "associatedDataContentAll(),priceContentAll(),"
					+ "referenceContentAllWithAttributes(),dataInLocalesAll()))",
				hierarchySiblings.toString()
			);
		}

		@Test
		@DisplayName("should produce correct toString with statistics")
		void shouldToStringReturnExpectedFormatWithStatistics() {
			final HierarchySiblings hierarchySiblings = siblings("megaMenu", statistics());

			assertEquals("siblings('megaMenu',statistics())", hierarchySiblings.toString());
		}

		@Test
		@DisplayName("should produce correct toString without output name")
		void shouldToStringReturnExpectedFormatWithoutOutputName() {
			final HierarchySiblings hierarchySiblings = siblings(statistics());

			assertEquals("siblings(statistics())", hierarchySiblings.toString());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(siblings("megaMenu"), siblings("megaMenu"));
			assertEquals(siblings("megaMenu"), siblings("megaMenu"));
			assertEquals(
				siblings("megaMenu", statistics()),
				siblings("megaMenu", statistics())
			);
			assertNotEquals(siblings("megaMenu"), siblings("megaMenu", entityFetchAll()));
			assertNotEquals(siblings("megaMenu"), siblings("megaMenu", statistics()));
			assertEquals(siblings("megaMenu").hashCode(), siblings("megaMenu").hashCode());
			assertNotEquals(
				siblings("megaMenu").hashCode(),
				siblings("megaMenu", entityFetchAll()).hashCode()
			);
			assertNotEquals(
				siblings("megaMenu").hashCode(),
				siblings("megaMenu", statistics()).hashCode()
			);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return new instance from cloneWithArguments")
		void shouldReturnNewInstanceFromCloneWithArguments() {
			final HierarchySiblings constraint = siblings("megaMenu", statistics());

			final RequireConstraint cloned = constraint.cloneWithArguments(
				new Serializable[]{"otherMenu"}
			);

			assertNotSame(constraint, cloned);
			assertInstanceOf(HierarchySiblings.class, cloned);
			assertEquals("otherMenu", ((HierarchySiblings) cloned).getOutputName());
		}

		@Test
		@DisplayName("should return new instance from cloneWithArguments with empty args")
		void shouldReturnNewInstanceFromCloneWithEmptyArguments() {
			final HierarchySiblings constraint = siblings(statistics());

			final RequireConstraint cloned = constraint.cloneWithArguments(new Serializable[0]);
			assertNotSame(constraint, cloned);
			assertInstanceOf(HierarchySiblings.class, cloned);
			assertNull(((HierarchySiblings) cloned).getOutputName());
		}

		@Test
		@DisplayName("should produce new instance from getCopyWithNewChildren")
		void shouldProduceCopyWithNewChildren() {
			final HierarchySiblings constraint = siblings("megaMenu");

			final RequireConstraint copy = constraint.getCopyWithNewChildren(
				new RequireConstraint[]{statistics()},
				new Constraint<?>[0]
			);

			assertNotSame(constraint, copy);
			assertInstanceOf(HierarchySiblings.class, copy);
			assertTrue(((HierarchySiblings) copy).getStatistics().isPresent());
		}
	}
}
