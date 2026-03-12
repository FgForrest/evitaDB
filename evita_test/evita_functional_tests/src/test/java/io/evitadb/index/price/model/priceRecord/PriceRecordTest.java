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

package io.evitadb.index.price.model.priceRecord;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.require.QueryPriceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceRecord}, {@link PriceRecordInnerRecordSpecific}, and
 * {@link CumulatedVirtualPriceRecord} verifying construction, field access,
 * equality/hashCode contracts, comparison ordering, relatesTo semantics,
 * and serialization behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Price record model classes")
class PriceRecordTest {

	@Nested
	@DisplayName("PriceRecord")
	class PriceRecordTests {

		@Nested
		@DisplayName("Construction and field access")
		class ConstructionAndFieldAccess {

			@Test
			@DisplayName("should return all constructor fields via accessors")
			void shouldReturnAllFields() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);

				assertEquals(1, record.internalPriceId());
				assertEquals(10, record.priceId());
				assertEquals(100, record.entityPrimaryKey());
				assertEquals(5000, record.priceWithTax());
				assertEquals(4200, record.priceWithoutTax());
			}

			@Test
			@DisplayName("should return zero for inner record id")
			void shouldReturnZeroForInnerRecordId() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);

				assertEquals(0, record.innerRecordId());
			}

			@Test
			@DisplayName("should not be inner record specific")
			void shouldNotBeInnerRecordSpecific() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);

				assertFalse(record.isInnerRecordSpecific());
			}
		}

		@Nested
		@DisplayName("Equals and hashCode")
		class EqualsAndHashCode {

			@Test
			@DisplayName("should be equal when same internal price id")
			void shouldBeEqualWhenSameInternalPriceId() {
				final PriceRecord record1 = new PriceRecord(1, 10, 100, 5000, 4200);
				final PriceRecord record2 = new PriceRecord(1, 10, 100, 5000, 4200);

				assertEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal when different internal price id")
			void shouldNotBeEqualWhenDifferentInternalPriceId() {
				final PriceRecord record1 = new PriceRecord(1, 10, 100, 5000, 4200);
				final PriceRecord record2 = new PriceRecord(2, 10, 100, 5000, 4200);

				assertNotEquals(record1, record2);
			}

			@Test
			@DisplayName("should ignore other fields in equality comparison")
			void shouldIgnoreOtherFieldsInEquals() {
				// same internalPriceId, but all other fields differ
				final PriceRecord record1 = new PriceRecord(1, 10, 100, 5000, 4200);
				final PriceRecord record2 = new PriceRecord(1, 20, 200, 9000, 7500);

				assertEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal to null")
			void shouldNotBeEqualToNull() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);

				assertNotEquals(null, record);
			}

			@Test
			@DisplayName("should not be equal to different type")
			void shouldNotBeEqualToDifferentType() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);

				// PriceRecordInnerRecordSpecific is a different class
				final PriceRecordInnerRecordSpecific other =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);

				assertNotEquals(record, other);
			}

			@Test
			@DisplayName("should produce consistent hash code based on internal price id")
			void shouldHaveConsistentHashCode() {
				final PriceRecord record1 = new PriceRecord(42, 10, 100, 5000, 4200);
				final PriceRecord record2 = new PriceRecord(42, 20, 200, 9000, 7500);

				assertEquals(record1.hashCode(), record2.hashCode());
				assertEquals(Integer.hashCode(42), record1.hashCode());
			}

			@Test
			@DisplayName("should be reflexive")
			void shouldBeReflexive() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);

				assertEquals(record, record);
			}
		}

		@Nested
		@DisplayName("CompareTo and ordering")
		class CompareToAndOrdering {

			@Test
			@DisplayName("should compare by internal price id")
			void shouldCompareByInternalPriceId() {
				final PriceRecord lower = new PriceRecord(1, 10, 100, 5000, 4200);
				final PriceRecord higher = new PriceRecord(5, 10, 100, 5000, 4200);

				assertTrue(lower.compareTo(higher) < 0);
				assertTrue(higher.compareTo(lower) > 0);
				assertEquals(0, lower.compareTo(new PriceRecord(1, 99, 99, 99, 99)));
			}

			@Test
			@DisplayName("should sort correctly in array")
			void shouldSortCorrectlyInArray() {
				final PriceRecord r1 = new PriceRecord(3, 10, 100, 5000, 4200);
				final PriceRecord r2 = new PriceRecord(1, 20, 200, 6000, 5100);
				final PriceRecord r3 = new PriceRecord(2, 30, 300, 7000, 6000);
				final PriceRecordContract[] records = {r1, r2, r3};

				Arrays.sort(records);

				assertEquals(1, records[0].internalPriceId());
				assertEquals(2, records[1].internalPriceId());
				assertEquals(3, records[2].internalPriceId());
			}

			@Test
			@DisplayName("should be consistent with static PRICE_RECORD_COMPARATOR")
			void shouldBeConsistentWithComparator() {
				final PriceRecord r1 = new PriceRecord(1, 10, 100, 5000, 4200);
				final PriceRecord r2 = new PriceRecord(5, 20, 200, 6000, 5100);

				final int compareToResult = r1.compareTo(r2);
				final int comparatorResult =
					PriceRecordContract.PRICE_RECORD_COMPARATOR.compare(r1, r2);

				assertEquals(
					Integer.signum(compareToResult),
					Integer.signum(comparatorResult)
				);
			}
		}

		@Nested
		@DisplayName("RelatesTo")
		class RelatesTo {

			@Test
			@DisplayName("should throw UnsupportedOperationException")
			void shouldThrowUnsupportedOperationException() {
				final PriceRecord record = new PriceRecord(1, 10, 100, 5000, 4200);
				final PriceRecord other = new PriceRecord(2, 20, 200, 6000, 5100);

				final UnsupportedOperationException ex = assertThrows(
					UnsupportedOperationException.class,
					() -> record.relatesTo(other)
				);

				assertEquals(
					"PriceRecord does not represent inner record id",
					ex.getMessage()
				);
			}
		}

		@Nested
		@DisplayName("Serialization")
		class Serialization {

			@Test
			@DisplayName("should serialize and deserialize preserving all fields")
			void shouldSerializeAndDeserialize() throws Exception {
				final PriceRecord original = new PriceRecord(1, 10, 100, 5000, 4200);

				final PriceRecord deserialized = serializeAndDeserialize(original);

				assertEquals(original.internalPriceId(), deserialized.internalPriceId());
				assertEquals(original.priceId(), deserialized.priceId());
				assertEquals(original.entityPrimaryKey(), deserialized.entityPrimaryKey());
				assertEquals(original.priceWithTax(), deserialized.priceWithTax());
				assertEquals(original.priceWithoutTax(), deserialized.priceWithoutTax());
				assertEquals(original, deserialized);
			}
		}
	}

	@Nested
	@DisplayName("PriceRecordInnerRecordSpecific")
	class PriceRecordInnerRecordSpecificTests {

		@Nested
		@DisplayName("Construction and field access")
		class ConstructionAndFieldAccess {

			@Test
			@DisplayName("should return all constructor fields via accessors")
			void shouldReturnAllFields() {
				final PriceRecordInnerRecordSpecific record =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);

				assertEquals(1, record.internalPriceId());
				assertEquals(10, record.priceId());
				assertEquals(100, record.entityPrimaryKey());
				assertEquals(5, record.innerRecordId());
				assertEquals(5000, record.priceWithTax());
				assertEquals(4200, record.priceWithoutTax());
			}

			@Test
			@DisplayName("should return the specified inner record id")
			void shouldReturnInnerRecordId() {
				final PriceRecordInnerRecordSpecific record =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 42, 5000, 4200);

				assertEquals(42, record.innerRecordId());
			}

			@Test
			@DisplayName("should be inner record specific")
			void shouldBeInnerRecordSpecific() {
				final PriceRecordInnerRecordSpecific record =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);

				assertTrue(record.isInnerRecordSpecific());
			}
		}

		@Nested
		@DisplayName("Equals and hashCode")
		class EqualsAndHashCode {

			@Test
			@DisplayName("should be equal when same internal price id")
			void shouldBeEqualWhenSameInternalPriceId() {
				final PriceRecordInnerRecordSpecific record1 =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final PriceRecordInnerRecordSpecific record2 =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);

				assertEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal when different internal price id")
			void shouldNotBeEqualWhenDifferentInternalPriceId() {
				final PriceRecordInnerRecordSpecific record1 =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final PriceRecordInnerRecordSpecific record2 =
					new PriceRecordInnerRecordSpecific(2, 10, 100, 5, 5000, 4200);

				assertNotEquals(record1, record2);
			}

			@Test
			@DisplayName(
				"should ignore other fields including inner record id in equality"
			)
			void shouldIgnoreOtherFieldsIncludingInnerRecordId() {
				// same internalPriceId, but all other fields (including innerRecordId) differ
				final PriceRecordInnerRecordSpecific record1 =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final PriceRecordInnerRecordSpecific record2 =
					new PriceRecordInnerRecordSpecific(1, 20, 200, 99, 9000, 7500);

				assertEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal to PriceRecord of different class")
			void shouldNotBeEqualToPriceRecord() {
				final PriceRecordInnerRecordSpecific innerSpecific =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final PriceRecord basic = new PriceRecord(1, 10, 100, 5000, 4200);

				// different class despite same internalPriceId
				assertNotEquals(innerSpecific, basic);
				assertNotEquals(basic, innerSpecific);
			}

			@Test
			@DisplayName("should produce consistent hash code based on internal price id")
			void shouldHaveConsistentHashCode() {
				final PriceRecordInnerRecordSpecific record1 =
					new PriceRecordInnerRecordSpecific(42, 10, 100, 5, 5000, 4200);
				final PriceRecordInnerRecordSpecific record2 =
					new PriceRecordInnerRecordSpecific(42, 20, 200, 99, 9000, 7500);

				assertEquals(record1.hashCode(), record2.hashCode());
				assertEquals(Integer.hashCode(42), record1.hashCode());
			}
		}

		@Nested
		@DisplayName("RelatesTo")
		class RelatesTo {

			@Test
			@DisplayName("should relate when same inner record id")
			void shouldRelateWhenSameInnerRecordId() {
				final PriceRecordInnerRecordSpecific record1 =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final PriceRecordInnerRecordSpecific record2 =
					new PriceRecordInnerRecordSpecific(2, 20, 200, 5, 6000, 5100);

				assertTrue(record1.relatesTo(record2));
				assertTrue(record2.relatesTo(record1));
			}

			@Test
			@DisplayName("should not relate when different inner record id")
			void shouldNotRelateWhenDifferentInnerRecordId() {
				final PriceRecordInnerRecordSpecific record1 =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final PriceRecordInnerRecordSpecific record2 =
					new PriceRecordInnerRecordSpecific(2, 20, 200, 99, 6000, 5100);

				assertFalse(record1.relatesTo(record2));
				assertFalse(record2.relatesTo(record1));
			}

			@Test
			@DisplayName(
				"should relate from cumulated to inner record specific via map lookup"
			)
			void shouldRelateFromCumulatedToInnerRecordSpecific() {
				final PriceRecordInnerRecordSpecific innerSpecific =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);

				final IntObjectMap<PriceRecordContract> map = new IntObjectHashMap<>();
				map.put(5, innerSpecific);
				final CumulatedVirtualPriceRecord cumulated =
					new CumulatedVirtualPriceRecord(
						100, 5000, QueryPriceMode.WITH_TAX, map
					);

				// cumulated.relatesTo checks innerRecordPrices.keys().contains(other.innerRecordId())
				assertTrue(cumulated.relatesTo(innerSpecific));

				// innerSpecific.relatesTo checks this.innerRecordId == other.innerRecordId()
				// cumulated.innerRecordId() returns 0, so this will be false (5 != 0)
				assertFalse(innerSpecific.relatesTo(cumulated));
			}
		}

		@Nested
		@DisplayName("Serialization")
		class Serialization {

			@Test
			@DisplayName("should serialize and deserialize preserving all fields")
			void shouldSerializeAndDeserialize() throws Exception {
				final PriceRecordInnerRecordSpecific original =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);

				final PriceRecordInnerRecordSpecific deserialized =
					serializeAndDeserialize(original);

				assertEquals(original.internalPriceId(), deserialized.internalPriceId());
				assertEquals(original.priceId(), deserialized.priceId());
				assertEquals(
					original.entityPrimaryKey(), deserialized.entityPrimaryKey()
				);
				assertEquals(original.innerRecordId(), deserialized.innerRecordId());
				assertEquals(original.priceWithTax(), deserialized.priceWithTax());
				assertEquals(original.priceWithoutTax(), deserialized.priceWithoutTax());
				assertEquals(original, deserialized);
			}
		}
	}

	@SuppressWarnings("SameParameterValue")
	@Nested
	@DisplayName("CumulatedVirtualPriceRecord")
	class CumulatedVirtualPriceRecordTests {

		@Nested
		@DisplayName("Construction and field access")
		class ConstructionAndFieldAccess {

			@Test
			@DisplayName("should return zero for internal price id and price id")
			void shouldReturnZeroForInternalPriceIdAndPriceId() {
				final CumulatedVirtualPriceRecord record = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertEquals(0, record.internalPriceId());
				assertEquals(0, record.priceId());
			}

			@Test
			@DisplayName("should return zero for inner record id")
			void shouldReturnZeroForInnerRecordId() {
				final CumulatedVirtualPriceRecord record = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertEquals(0, record.innerRecordId());
			}

			@Test
			@DisplayName("should not be inner record specific")
			void shouldNotBeInnerRecordSpecific() {
				final CumulatedVirtualPriceRecord record = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertFalse(record.isInnerRecordSpecific());
			}
		}

		@Nested
		@DisplayName("Price mode dispatching")
		class PriceModeDispatching {

			@Test
			@DisplayName("should return price as priceWithTax when mode is WITH_TAX")
			void shouldReturnPriceWithTaxWhenModeIsWithTax() {
				final CumulatedVirtualPriceRecord record = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertEquals(5000, record.priceWithTax());
			}

			@Test
			@DisplayName(
				"should return zero for priceWithoutTax when mode is WITH_TAX"
			)
			void shouldReturnZeroWithoutTaxWhenModeIsWithTax() {
				final CumulatedVirtualPriceRecord record = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertEquals(0, record.priceWithoutTax());
			}

			@Test
			@DisplayName(
				"should return price as priceWithoutTax when mode is WITHOUT_TAX"
			)
			void shouldReturnPriceWithoutTaxWhenModeIsWithoutTax() {
				final CumulatedVirtualPriceRecord record = createWithoutTaxRecord(
					100, 4200, new IntObjectHashMap<>()
				);

				assertEquals(4200, record.priceWithoutTax());
			}

			@Test
			@DisplayName(
				"should return zero for priceWithTax when mode is WITHOUT_TAX"
			)
			void shouldReturnZeroWithTaxWhenModeIsWithoutTax() {
				final CumulatedVirtualPriceRecord record = createWithoutTaxRecord(
					100, 4200, new IntObjectHashMap<>()
				);

				assertEquals(0, record.priceWithTax());
			}
		}

		@Nested
		@DisplayName("Equals and hashCode")
		class EqualsAndHashCode {

			@Test
			@DisplayName(
				"should be equal when same entityPrimaryKey, price and priceMode"
			)
			void shouldBeEqualWhenSameThreeFields() {
				final CumulatedVirtualPriceRecord record1 = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord record2 = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal when different entity primary key")
			void shouldNotBeEqualWhenDifferentEntityPrimaryKey() {
				final CumulatedVirtualPriceRecord record1 = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord record2 = createWithTaxRecord(
					200, 5000, new IntObjectHashMap<>()
				);

				assertNotEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal when different price")
			void shouldNotBeEqualWhenDifferentPrice() {
				final CumulatedVirtualPriceRecord record1 = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord record2 = createWithTaxRecord(
					100, 9000, new IntObjectHashMap<>()
				);

				assertNotEquals(record1, record2);
			}

			@Test
			@DisplayName("should not be equal when different price mode")
			void shouldNotBeEqualWhenDifferentPriceMode() {
				final CumulatedVirtualPriceRecord record1 = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord record2 = createWithoutTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertNotEquals(record1, record2);
			}

			@Test
			@DisplayName(
				"should be equal ignoring innerRecordPrices map differences"
			)
			void shouldBeEqualIgnoringInnerRecordPrices() {
				final IntObjectMap<PriceRecordContract> map1 =
					new IntObjectHashMap<>();
				map1.put(1, new PriceRecord(1, 10, 100, 5000, 4200));

				final IntObjectMap<PriceRecordContract> map2 =
					new IntObjectHashMap<>();
				map2.put(2, new PriceRecord(2, 20, 200, 6000, 5100));

				final CumulatedVirtualPriceRecord record1 =
					createWithTaxRecord(100, 5000, map1);
				final CumulatedVirtualPriceRecord record2 =
					createWithTaxRecord(100, 5000, map2);

				// equals ignores innerRecordPrices
				assertEquals(record1, record2);
			}

			@Test
			@DisplayName(
				"should have consistent hash codes for equal instances "
					+ "with different inner record prices"
			)
			void shouldHaveConsistentHashCode() {
				final IntObjectMap<PriceRecordContract> map1 =
					new IntObjectHashMap<>();
				map1.put(1, new PriceRecord(1, 10, 100, 5000, 4200));

				final IntObjectMap<PriceRecordContract> map2 =
					new IntObjectHashMap<>();
				map2.put(2, new PriceRecord(2, 20, 200, 6000, 5100));

				final CumulatedVirtualPriceRecord record1 =
					createWithTaxRecord(100, 5000, map1);
				final CumulatedVirtualPriceRecord record2 =
					createWithTaxRecord(100, 5000, map2);

				// these instances are equal ...
				assertEquals(record1, record2);
				// ... and their hash codes must also be equal (equals/hashCode contract)
				assertEquals(record1.hashCode(), record2.hashCode());
			}
		}

		@Nested
		@DisplayName("CompareTo")
		class CompareTo {

			@Test
			@DisplayName("should compare by entity primary key first")
			void shouldCompareByEntityPrimaryKeyFirst() {
				final CumulatedVirtualPriceRecord lower = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord higher = createWithTaxRecord(
					200, 5000, new IntObjectHashMap<>()
				);

				assertTrue(lower.compareTo(higher) < 0);
				assertTrue(higher.compareTo(lower) > 0);
			}

			@Test
			@DisplayName(
				"should compare by priceWithoutTax second when entity PK is equal"
			)
			void shouldCompareByPriceWithoutTaxSecond() {
				// both WITHOUT_TAX so priceWithoutTax returns the price value
				final CumulatedVirtualPriceRecord lower = createWithoutTaxRecord(
					100, 4200, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord higher = createWithoutTaxRecord(
					100, 9000, new IntObjectHashMap<>()
				);

				assertTrue(lower.compareTo(higher) < 0);
				assertTrue(higher.compareTo(lower) > 0);
			}

			@Test
			@DisplayName(
				"should compare by priceWithTax third when entity PK "
					+ "and priceWithoutTax are equal"
			)
			void shouldCompareByPriceWithTaxThird() {
				// both WITH_TAX: priceWithoutTax returns 0 for both (equal),
				// priceWithTax returns the price value
				final CumulatedVirtualPriceRecord lower = createWithTaxRecord(
					100, 4200, new IntObjectHashMap<>()
				);
				final CumulatedVirtualPriceRecord higher = createWithTaxRecord(
					100, 9000, new IntObjectHashMap<>()
				);

				assertTrue(lower.compareTo(higher) < 0);
				assertTrue(higher.compareTo(lower) > 0);
			}
		}

		@Nested
		@DisplayName("RelatesTo")
		class RelatesTo {

			@Test
			@DisplayName(
				"should relate when other's inner record id is in the map"
			)
			void shouldRelateWhenInnerRecordIdIsInMap() {
				final PriceRecordInnerRecordSpecific innerSpecific =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final IntObjectMap<PriceRecordContract> map =
					new IntObjectHashMap<>();
				map.put(5, innerSpecific);

				final CumulatedVirtualPriceRecord cumulated =
					createWithTaxRecord(100, 5000, map);

				assertTrue(cumulated.relatesTo(innerSpecific));
			}

			@Test
			@DisplayName(
				"should not relate when other's inner record id is not in the map"
			)
			void shouldNotRelateWhenInnerRecordIdNotInMap() {
				final PriceRecordInnerRecordSpecific innerSpecific =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 99, 5000, 4200);
				final IntObjectMap<PriceRecordContract> map =
					new IntObjectHashMap<>();
				map.put(5, new PriceRecord(2, 20, 200, 6000, 5100));

				final CumulatedVirtualPriceRecord cumulated =
					createWithTaxRecord(100, 5000, map);

				// innerRecordId 99 is not a key in the map (only 5 is)
				assertFalse(cumulated.relatesTo(innerSpecific));
			}

			@Test
			@DisplayName("should not relate when inner record prices map is empty")
			void shouldNotRelateToEmptyMap() {
				final PriceRecordInnerRecordSpecific innerSpecific =
					new PriceRecordInnerRecordSpecific(1, 10, 100, 5, 5000, 4200);
				final CumulatedVirtualPriceRecord cumulated = createWithTaxRecord(
					100, 5000, new IntObjectHashMap<>()
				);

				assertFalse(cumulated.relatesTo(innerSpecific));
			}
		}

		@Nested
		@DisplayName("ToString")
		class ToString {

			@Test
			@DisplayName("should contain all fields in toString output")
			void shouldContainAllFieldsInToString() {
				final IntObjectMap<PriceRecordContract> map =
					new IntObjectHashMap<>();
				map.put(5, new PriceRecord(1, 10, 100, 5000, 4200));

				final CumulatedVirtualPriceRecord record =
					createWithTaxRecord(100, 5000, map);
				final String result = record.toString();

				assertTrue(
					result.contains("entityPrimaryKey=100"),
					"toString should contain entityPrimaryKey"
				);
				assertTrue(
					result.contains("price=5000"),
					"toString should contain price"
				);
				assertTrue(
					result.contains("priceMode=WITH_TAX"),
					"toString should contain priceMode"
				);
				assertTrue(
					result.contains("innerRecordPrices="),
					"toString should contain innerRecordPrices"
				);
				assertTrue(
					result.startsWith("CumulatedVirtualPriceRecord{"),
					"toString should start with class name"
				);
			}
		}

		@Nested
		@DisplayName("Serialization")
		class Serialization {

			@Test
			@DisplayName(
				"should throw NotSerializableException because HPPC map "
					+ "is not Serializable"
			)
			void shouldThrowNotSerializableException() {
				final IntObjectMap<PriceRecordContract> map =
					new IntObjectHashMap<>();
				map.put(5, new PriceRecord(1, 10, 100, 5000, 4200));

				final CumulatedVirtualPriceRecord record =
					createWithTaxRecord(100, 5000, map);

				// HPPC IntObjectHashMap does not implement Serializable
				assertThrows(
					NotSerializableException.class,
					() -> serializeAndDeserialize(record)
				);
			}
		}

		/**
		 * Creates a {@link CumulatedVirtualPriceRecord} with {@link QueryPriceMode#WITH_TAX}.
		 *
		 * @param entityPrimaryKey the entity primary key
		 * @param price the cumulated price value
		 * @param innerRecordPrices the map of inner record prices
		 * @return a new cumulated virtual price record in WITH_TAX mode
		 */
		@Nonnull
		private static CumulatedVirtualPriceRecord createWithTaxRecord(
			int entityPrimaryKey,
			int price,
			@Nonnull IntObjectMap<PriceRecordContract> innerRecordPrices
		) {
			return new CumulatedVirtualPriceRecord(
				entityPrimaryKey, price, QueryPriceMode.WITH_TAX, innerRecordPrices
			);
		}

		/**
		 * Creates a {@link CumulatedVirtualPriceRecord} with {@link QueryPriceMode#WITHOUT_TAX}.
		 *
		 * @param entityPrimaryKey the entity primary key
		 * @param price the cumulated price value
		 * @param innerRecordPrices the map of inner record prices
		 * @return a new cumulated virtual price record in WITHOUT_TAX mode
		 */
		@Nonnull
		private static CumulatedVirtualPriceRecord createWithoutTaxRecord(
			int entityPrimaryKey,
			int price,
			@Nonnull IntObjectMap<PriceRecordContract> innerRecordPrices
		) {
			return new CumulatedVirtualPriceRecord(
				entityPrimaryKey, price, QueryPriceMode.WITHOUT_TAX, innerRecordPrices
			);
		}
	}

	/**
	 * Serializes the given object to a byte array and deserializes it back,
	 * returning the reconstituted object.
	 *
	 * @param object the object to round-trip through Java serialization
	 * @param <T> the type of the object
	 * @return the deserialized copy
	 * @throws Exception if serialization or deserialization fails
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static <T> T serializeAndDeserialize(@Nonnull T object) throws Exception {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
		try (final ObjectOutputStream oos = new ObjectOutputStream(baos)) {
			oos.writeObject(object);
		}
		final ByteArrayInputStream bais =
			new ByteArrayInputStream(baos.toByteArray());
		try (final ObjectInputStream ois = new ObjectInputStream(bais)) {
			return (T) ois.readObject();
		}
	}
}
