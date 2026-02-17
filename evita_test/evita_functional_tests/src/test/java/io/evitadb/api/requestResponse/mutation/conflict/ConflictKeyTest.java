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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all conflict key record types: equality, hashCode, and toString verification.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Conflict keys")
class ConflictKeyTest implements EvitaTestSupport {

	@Nested
	@DisplayName("CatalogConflictKey")
	class CatalogConflictKeyTest {

		@Test
		@DisplayName("should be equal for same catalog name")
		void shouldBeEqualForSameCatalogName() {
			final CatalogConflictKey key1 = new CatalogConflictKey("testCatalog");
			final CatalogConflictKey key2 = new CatalogConflictKey("testCatalog");

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different catalog names")
		void shouldNotBeEqualForDifferentCatalogNames() {
			final CatalogConflictKey key1 = new CatalogConflictKey("catalog1");
			final CatalogConflictKey key2 = new CatalogConflictKey("catalog2");

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final CatalogConflictKey key = new CatalogConflictKey("testCatalog");

			assertTrue(key.toString().contains("testCatalog"));
		}
	}

	@Nested
	@DisplayName("CollectionConflictKey")
	class CollectionConflictKeyTest {

		@Test
		@DisplayName("should be equal for same entity type")
		void shouldBeEqualForSameEntityType() {
			final CollectionConflictKey key1 = new CollectionConflictKey("Product");
			final CollectionConflictKey key2 = new CollectionConflictKey("Product");

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different entity types")
		void shouldNotBeEqualForDifferentEntityTypes() {
			final CollectionConflictKey key1 = new CollectionConflictKey("Product");
			final CollectionConflictKey key2 = new CollectionConflictKey("Category");

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final CollectionConflictKey key = new CollectionConflictKey("Product");

			assertTrue(key.toString().contains("Product"));
		}
	}

	@Nested
	@DisplayName("EntityConflictKey")
	class EntityConflictKeyTest {

		@Test
		@DisplayName("should be equal for same entity type and primary key")
		void shouldBeEqualForSameEntityTypeAndPrimaryKey() {
			final EntityConflictKey key1 = new EntityConflictKey("Product", 1);
			final EntityConflictKey key2 = new EntityConflictKey("Product", 1);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different primary keys")
		void shouldNotBeEqualForDifferentPrimaryKeys() {
			final EntityConflictKey key1 = new EntityConflictKey("Product", 1);
			final EntityConflictKey key2 = new EntityConflictKey("Product", 2);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final EntityConflictKey key = new EntityConflictKey("Product", 42);
			final String str = key.toString();

			assertTrue(str.contains("Product"));
			assertTrue(str.contains("42"));
		}
	}

	@Nested
	@DisplayName("AttributeConflictKey")
	class AttributeConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final AttributeConflictKey key1 = new AttributeConflictKey("Product", 1, "name");
			final AttributeConflictKey key2 = new AttributeConflictKey("Product", 1, "name");

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different attribute names")
		void shouldNotBeEqualForDifferentAttributeNames() {
			final AttributeConflictKey key1 = new AttributeConflictKey("Product", 1, "name");
			final AttributeConflictKey key2 = new AttributeConflictKey("Product", 1, "code");

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final AttributeConflictKey key = new AttributeConflictKey("Product", 1, "name");
			final String str = key.toString();

			assertTrue(str.contains("name"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("ReferenceConflictKey")
	class ReferenceConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final ReferenceConflictKey key1 = new ReferenceConflictKey("Product", 1, "brand", 10);
			final ReferenceConflictKey key2 = new ReferenceConflictKey("Product", 1, "brand", 10);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different reference primary keys")
		void shouldNotBeEqualForDifferentReferencePrimaryKeys() {
			final ReferenceConflictKey key1 = new ReferenceConflictKey("Product", 1, "brand", 10);
			final ReferenceConflictKey key2 = new ReferenceConflictKey("Product", 1, "brand", 20);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final ReferenceConflictKey key = new ReferenceConflictKey("Product", 1, "brand", 10);
			final String str = key.toString();

			assertTrue(str.contains("brand"));
			assertTrue(str.contains("10"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("ReferenceAttributeConflictKey")
	class ReferenceAttributeConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final ReferenceAttributeConflictKey key1 = new ReferenceAttributeConflictKey(
				"Product", 1, "brand", 10, "priority"
			);
			final ReferenceAttributeConflictKey key2 = new ReferenceAttributeConflictKey(
				"Product", 1, "brand", 10, "priority"
			);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final ReferenceAttributeConflictKey key = new ReferenceAttributeConflictKey(
				"Product", 1, "brand", 10, "priority"
			);
			final String str = key.toString();

			assertTrue(str.contains("brand"));
			assertTrue(str.contains("priority"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("PriceConflictKey")
	class PriceConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final PriceConflictKey key1 = new PriceConflictKey(
				"Product", 1, 100, Currency.getInstance("USD"), "basic"
			);
			final PriceConflictKey key2 = new PriceConflictKey(
				"Product", 1, 100, Currency.getInstance("USD"), "basic"
			);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different currencies")
		void shouldNotBeEqualForDifferentCurrencies() {
			final PriceConflictKey key1 = new PriceConflictKey(
				"Product", 1, 100, Currency.getInstance("USD"), "basic"
			);
			final PriceConflictKey key2 = new PriceConflictKey(
				"Product", 1, 100, Currency.getInstance("EUR"), "basic"
			);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should include priceList in toString")
		void shouldIncludePriceListInToString() {
			final PriceConflictKey key = new PriceConflictKey(
				"Product", 1, 100, Currency.getInstance("USD"), "basic"
			);
			final String str = key.toString();

			assertTrue(str.contains("basic"), "toString should contain priceList value");
			assertTrue(str.contains("USD"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("HierarchyConflictKey")
	class HierarchyConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final HierarchyConflictKey key1 = new HierarchyConflictKey("Category", 5);
			final HierarchyConflictKey key2 = new HierarchyConflictKey("Category", 5);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final HierarchyConflictKey key = new HierarchyConflictKey("Category", 5);
			final String str = key.toString();

			assertTrue(str.contains("hierarchy"));
			assertTrue(str.contains("Category"));
			assertTrue(str.contains("5"));
		}
	}

	@Nested
	@DisplayName("AssociatedDataConflictKey")
	class AssociatedDataConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final AssociatedDataConflictKey key1 = new AssociatedDataConflictKey("Product", 1, "description");
			final AssociatedDataConflictKey key2 = new AssociatedDataConflictKey("Product", 1, "description");

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final AssociatedDataConflictKey key = new AssociatedDataConflictKey("Product", 1, "description");
			final String str = key.toString();

			assertTrue(str.contains("description"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("PriceInnerRecordHandlingStrategyConflictKey")
	class PriceInnerRecordHandlingStrategyConflictKeyTest {

		@Test
		@DisplayName("should be equal for same components")
		void shouldBeEqualForSameComponents() {
			final PriceInnerRecordHandlingStrategyConflictKey key1 =
				new PriceInnerRecordHandlingStrategyConflictKey("Product", 1);
			final PriceInnerRecordHandlingStrategyConflictKey key2 =
				new PriceInnerRecordHandlingStrategyConflictKey("Product", 1);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final PriceInnerRecordHandlingStrategyConflictKey key =
				new PriceInnerRecordHandlingStrategyConflictKey("Product", 1);
			final String str = key.toString();

			assertTrue(str.contains("price inner record handling"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("AttributeDeltaConflictKey")
	class AttributeDeltaConflictKeyTest {

		@Test
		@DisplayName("should aggregate numbers using sum")
		void shouldAggregateNumbersUsingSum() {
			final AttributeDeltaConflictKey key = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, null
			);

			final Number result = key.aggregate(3, 7);

			assertEquals(10, result.intValue());
		}

		@Test
		@DisplayName("should not be constrained to range when allowedRange is null")
		void shouldNotBeConstrainedWhenRangeIsNull() {
			final AttributeDeltaConflictKey key = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, null
			);

			assertFalse(key.isConstrainedToRange());
		}

		@Test
		@DisplayName("should be constrained to range when allowedRange is present")
		void shouldBeConstrainedWhenRangeIsPresent() {
			final AttributeDeltaConflictKey key = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, IntegerNumberRange.between(0, 100)
			);

			assertTrue(key.isConstrainedToRange());
		}

		@Test
		@DisplayName("should pass range check when value is within range")
		void shouldPassRangeCheckWhenWithinRange() {
			final AttributeDeltaConflictKey key = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, IntegerNumberRange.between(0, 100)
			);

			assertDoesNotThrow(() -> key.assertInAllowedRange("catalog", 1L, 50));
		}

		@Test
		@DisplayName("should throw when value is outside allowed range")
		void shouldThrowWhenValueIsOutsideRange() {
			final AttributeDeltaConflictKey key = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, IntegerNumberRange.between(0, 100)
			);

			assertThrows(
				ConflictingCatalogCommutativeMutationException.class,
				() -> key.assertInAllowedRange("catalog", 1L, 200)
			);
		}

		@Test
		@DisplayName("custom equals includes deltaValue and allowedRange")
		void customEqualsIncludesDeltaValueAndAllowedRange() {
			final AttributeDeltaConflictKey key1 = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, null
			);
			final AttributeDeltaConflictKey key2 = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 10, null
			);

			// Different deltaValue means not equal
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("custom hashCode excludes deltaValue for bucket stability")
		void customHashCodeExcludesDeltaValueForBucketStability() {
			// hashCode intentionally excludes deltaValue/allowedRange so that keys with different
			// deltas land in the same hash bucket, enabling aggregation lookups
			final AttributeDeltaConflictKey key1 = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, null
			);
			final AttributeDeltaConflictKey key2 = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 10, null
			);

			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final AttributeDeltaConflictKey key = new AttributeDeltaConflictKey(
				"Product", 1, new AttributeKey("quantity"), 5, null
			);
			final String str = key.toString();

			assertTrue(str.contains("quantity"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("ReferenceAttributeDeltaConflictKey")
	class ReferenceAttributeDeltaConflictKeyTest {

		@Test
		@DisplayName("should aggregate numbers using sum")
		void shouldAggregateNumbersUsingSum() {
			final ReferenceAttributeDeltaConflictKey key = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 5, null
			);

			final Number result = key.aggregate(3, 7);

			assertEquals(10, result.intValue());
		}

		@Test
		@DisplayName("should not be constrained to range when allowedRange is null")
		void shouldNotBeConstrainedWhenRangeIsNull() {
			final ReferenceAttributeDeltaConflictKey key = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 5, null
			);

			assertFalse(key.isConstrainedToRange());
		}

		@Test
		@DisplayName("should throw when value is outside allowed range")
		void shouldThrowWhenValueIsOutsideRange() {
			final ReferenceAttributeDeltaConflictKey key = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 5,
				IntegerNumberRange.between(0, 100)
			);

			assertThrows(
				ConflictingCatalogCommutativeMutationException.class,
				() -> key.assertInAllowedRange("catalog", 1L, 200)
			);
		}

		@Test
		@DisplayName("custom equals includes deltaValue and allowedRange")
		void customEqualsIncludesDeltaValueAndAllowedRange() {
			final ReferenceAttributeDeltaConflictKey key1 = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 5, null
			);
			final ReferenceAttributeDeltaConflictKey key2 = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 10, null
			);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("custom hashCode excludes deltaValue for bucket stability")
		void customHashCodeExcludesDeltaValueForBucketStability() {
			final ReferenceAttributeDeltaConflictKey key1 = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 5, null
			);
			final ReferenceAttributeDeltaConflictKey key2 = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 10, null
			);

			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final ReferenceAttributeDeltaConflictKey key = new ReferenceAttributeDeltaConflictKey(
				"Product", 1, new ReferenceKey("brand", 10), new AttributeKey("priority"), 5, null
			);
			final String str = key.toString();

			assertTrue(str.contains("brand"));
			assertTrue(str.contains("priority"));
			assertTrue(str.contains("Product"));
		}
	}

	@Nested
	@DisplayName("ConflictPolicy")
	class ConflictPolicyTest {

		@Test
		@DisplayName("should have correct granularity flags")
		void shouldHaveCorrectGranularityFlags() {
			assertFalse(ConflictPolicy.CATALOG.isGranular());
			assertFalse(ConflictPolicy.COLLECTION.isGranular());
			assertFalse(ConflictPolicy.ENTITY.isGranular());
			assertTrue(ConflictPolicy.ENTITY_ATTRIBUTE.isGranular());
			assertTrue(ConflictPolicy.REFERENCE.isGranular());
			assertTrue(ConflictPolicy.REFERENCE_ATTRIBUTE.isGranular());
			assertTrue(ConflictPolicy.ASSOCIATED_DATA.isGranular());
			assertTrue(ConflictPolicy.PRICE.isGranular());
			assertTrue(ConflictPolicy.HIERARCHY.isGranular());
		}

		@Test
		@DisplayName("should have nine enum values")
		void shouldHaveNineEnumValues() {
			assertEquals(9, ConflictPolicy.values().length);
		}
	}
}
