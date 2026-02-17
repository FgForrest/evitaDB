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
import io.evitadb.utils.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AccompanyingPriceContent} verifying construction, property access, suffix behavior, applicability,
 * EntityContentRequire contract, cloning, string representation, and equality.
 *
 * @author evitaDB
 */
@DisplayName("AccompanyingPriceContent constraint")
class AccompanyingPriceContentTest {

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("should create default accompanying price content via no-arg constructor")
		void shouldCreateDefaultAccompanyingPriceContentViaNoArgConstructor() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent();

			assertEquals(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE, constraint.getAccompanyingPriceName().orElse(null));
			assertArrayEquals(new String[0], constraint.getPriceLists());
		}

		@Test
		@DisplayName("should create default accompanying price content via factory method")
		void shouldCreateDefaultAccompanyingPriceContentViaFactoryMethod() {
			final AccompanyingPriceContent constraint = accompanyingPriceContentDefault();

			assertEquals(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE, constraint.getAccompanyingPriceName().orElse(null));
			assertArrayEquals(new String[0], constraint.getPriceLists());
		}

		@Test
		@DisplayName("should create accompanying price content with name and price lists")
		void shouldCreateAccompanyingPriceContentWithNameAndPriceLists() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference", "basic");

			assertEquals("myPrice", constraint.getAccompanyingPriceName().orElse(null));
			assertArrayEquals(new String[]{"reference", "basic"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should create accompanying price content with name only")
		void shouldCreateAccompanyingPriceContentWithNameOnly() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice");

			assertEquals("myPrice", constraint.getAccompanyingPriceName().orElse(null));
			assertArrayEquals(new String[0], constraint.getPriceLists());
		}

		@Test
		@DisplayName("should create accompanying price content via factory method with name and price lists")
		void shouldCreateAccompanyingPriceContentViaFactoryMethodWithNameAndPriceLists() {
			final AccompanyingPriceContent constraint = accompanyingPriceContent("myPrice", "reference", "basic");

			assertEquals("myPrice", constraint.getAccompanyingPriceName().orElse(null));
			assertArrayEquals(new String[]{"reference", "basic"}, constraint.getPriceLists());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return accompanying price name")
		void shouldReturnAccompanyingPriceName() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference");

			assertEquals("myPrice", constraint.getAccompanyingPriceName().orElse(null));
		}

		@Test
		@DisplayName("should return default accompanying price name for default constructor")
		void shouldReturnDefaultAccompanyingPriceNameForDefaultConstructor() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent();

			assertEquals(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE, constraint.getAccompanyingPriceName().orElse(null));
		}

		@Test
		@DisplayName("should return price lists")
		void shouldReturnPriceLists() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference", "basic", "vip");

			assertArrayEquals(new String[]{"reference", "basic", "vip"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should return empty array when no price lists specified")
		void shouldReturnEmptyArrayWhenNoPriceListsSpecified() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice");

			assertArrayEquals(new String[0], constraint.getPriceLists());
		}
	}

	@Nested
	@DisplayName("Suffix behavior")
	class SuffixBehaviorTest {

		@Test
		@DisplayName("should return 'default' suffix when default accompanying price name is used")
		void shouldReturnDefaultSuffixWhenDefaultAccompanyingPriceNameIsUsed() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent();

			assertEquals("default", constraint.getSuffixIfApplied().orElse(null));
		}

		@Test
		@DisplayName("should return empty suffix when non-default accompanying price name is used")
		void shouldReturnEmptySuffixWhenNonDefaultAccompanyingPriceNameIsUsed() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference");

			assertTrue(constraint.getSuffixIfApplied().isEmpty());
		}

		@Test
		@DisplayName("should omit default accompanying price name from toString when using default")
		void shouldOmitDefaultAccompanyingPriceNameFromToStringWhenUsingDefault() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent();

			assertEquals("accompanyingPriceContentDefault()", constraint.toString());
		}

		@Test
		@DisplayName("should include accompanying price name in toString when non-default")
		void shouldIncludeAccompanyingPriceNameInToStringWhenNonDefault() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference", "basic");

			assertEquals("accompanyingPriceContent('myPrice','reference','basic')", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable for default constructor")
		void shouldBeApplicableForDefaultConstructor() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent();

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be applicable with name and price lists")
		void shouldBeApplicableWithNameAndPriceLists() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference", "basic");

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be applicable with name only")
		void shouldBeApplicableWithNameOnly() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice");

			assertTrue(constraint.isApplicable());
		}
	}

	@Nested
	@DisplayName("EntityContentRequire contract")
	class EntityContentRequireContractTest {

		@Test
		@DisplayName("should be combinable with another AccompanyingPriceContent")
		void shouldBeCombinableWithAnotherAccompanyingPriceContent() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("otherPrice", "basic");

			assertTrue(constraint1.isCombinableWith(constraint2));
		}

		@Test
		@DisplayName("should not be combinable with other EntityContentRequire types")
		void shouldNotBeCombinableWithOtherEntityContentRequireTypes() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference");

			assertFalse(constraint.isCombinableWith(attributeContentAll()));
			assertFalse(constraint.isCombinableWith(associatedDataContentAll()));
		}

		@Test
		@DisplayName("should combine by returning the other requirement")
		void shouldCombineByReturningTheOtherRequirement() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("otherPrice", "basic");

			final AccompanyingPriceContent combined = constraint1.combineWith(constraint2);

			assertSame(constraint2, combined);
		}

		@Test
		@DisplayName("should never be fully contained within another requirement")
		void shouldNeverBeFullyContainedWithinAnotherRequirement() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("myPrice", "reference");

			assertFalse(constraint1.isFullyContainedWithin(constraint2));
		}

		@Test
		@DisplayName("should not be fully contained within different constraint type")
		void shouldNotBeFullyContainedWithinDifferentConstraintType() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference");

			assertFalse(constraint.isFullyContainedWithin(attributeContentAll()));
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should clone with new arguments")
		void shouldCloneWithNewArguments() {
			final AccompanyingPriceContent original = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent cloned = (AccompanyingPriceContent) original.cloneWithArguments(
				new Serializable[]{"otherPrice", "basic", "vip"}
			);

			assertNotSame(original, cloned);
			assertEquals("otherPrice", cloned.getAccompanyingPriceName().orElse(null));
			assertArrayEquals(new String[]{"basic", "vip"}, cloned.getPriceLists());
		}

		@Test
		@DisplayName("should throw assertion error when cloning with non-string first argument")
		void shouldThrowAssertionErrorWhenCloningWithNonStringFirstArgument() {
			final AccompanyingPriceContent original = new AccompanyingPriceContent("myPrice", "reference");

			assertThrows(IllegalArgumentException.class, () ->
				original.cloneWithArguments(new Serializable[]{123, "basic"})
			);
		}

		@Test
		@DisplayName("should throw assertion error when cloning with empty arguments")
		void shouldThrowAssertionErrorWhenCloningWithEmptyArguments() {
			final AccompanyingPriceContent original = new AccompanyingPriceContent("myPrice", "reference");

			assertThrows(IllegalArgumentException.class, () ->
				original.cloneWithArguments(new Serializable[]{})
			);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should delegate to visitor")
		void shouldDelegateToVisitor() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> {
				visited.set(c);
			});

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference");

			assertEquals(RequireConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should format toString with default suffix")
		void shouldFormatToStringWithDefaultSuffix() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent();

			assertEquals("accompanyingPriceContentDefault()", constraint.toString());
		}

		@Test
		@DisplayName("should format toString with name only")
		void shouldFormatToStringWithNameOnly() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice");

			assertEquals("accompanyingPriceContent('myPrice')", constraint.toString());
		}

		@Test
		@DisplayName("should format toString with name and price lists")
		void shouldFormatToStringWithNameAndPriceLists() {
			final AccompanyingPriceContent constraint = new AccompanyingPriceContent("myPrice", "reference", "basic");

			assertEquals("accompanyingPriceContent('myPrice','reference','basic')", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same arguments")
		void shouldBeEqualForSameArguments() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("myPrice", "reference");

			assertEquals(constraint1, constraint2);
			assertEquals(constraint1.hashCode(), constraint2.hashCode());
		}

		@Test
		@DisplayName("should be equal for default constructors")
		void shouldBeEqualForDefaultConstructors() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent();
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent();

			assertEquals(constraint1, constraint2);
			assertEquals(constraint1.hashCode(), constraint2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different names")
		void shouldNotBeEqualForDifferentNames() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("otherPrice", "reference");

			assertNotEquals(constraint1, constraint2);
			assertNotEquals(constraint1.hashCode(), constraint2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different price lists")
		void shouldNotBeEqualForDifferentPriceLists() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("myPrice", "basic");

			assertNotEquals(constraint1, constraint2);
			assertNotEquals(constraint1.hashCode(), constraint2.hashCode());
		}

		@Test
		@DisplayName("should not be same instance for same arguments")
		void shouldNotBeSameInstanceForSameArguments() {
			final AccompanyingPriceContent constraint1 = new AccompanyingPriceContent("myPrice", "reference");
			final AccompanyingPriceContent constraint2 = new AccompanyingPriceContent("myPrice", "reference");

			assertNotSame(constraint1, constraint2);
		}
	}
}
