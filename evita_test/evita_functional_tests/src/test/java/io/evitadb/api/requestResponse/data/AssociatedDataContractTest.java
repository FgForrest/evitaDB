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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the content comparisons of {@link AssociatedDataContract} tell apart ranges a reader can tell apart,
 * although they compare equal by `compareTo`, while plain values keep being compared by `compareTo`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AssociatedDataContract content comparison")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class AssociatedDataContractTest {

	@Test
	@DisplayName("should differ from a value holding a range whose bounds encode to the same longs")
	void shouldDifferFromValueHoldingRangeEncodedToSameLongs() {
		final AssociatedDataKey key = new AssociatedDataKey("range");
		final AssociatedDataValue oneToTwo = new AssociatedDataValue(
			key, BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))
		);

		assertTrue(
			oneToTwo.differsFrom(
				new AssociatedDataValue(
					key, BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"))
				)
			)
		);
		assertFalse(
			oneToTwo.differsFrom(
				new AssociatedDataValue(
					key, BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))
				)
			)
		);
	}

	/**
	 * Associated data values compare plain values by `compareTo`, so a decimal number rescaled from `1.0` to `1.00` is
	 * no change. Only ranges are compared more strictly on top of it.
	 */
	@Test
	@DisplayName("should not differ from a value holding the same decimal number at another scale")
	void shouldNotDifferFromValueHoldingSameDecimalNumberAtAnotherScale() {
		final AssociatedDataKey key = new AssociatedDataKey("amount");

		assertFalse(
			new AssociatedDataValue(key, new BigDecimal("1.0"))
				.differsFrom(new AssociatedDataValue(key, new BigDecimal("1.00")))
		);
	}

	@Test
	@DisplayName("should tell apart sets holding ranges encoded to the same longs, but not rescaled numbers")
	void shouldTellApartSetsHoldingRangesEncodedToSameLongsButNotRescaledNumbers() {
		assertTrue(
			AssociatedDataContract.anyAssociatedDataDifferBetween(
				associatedDataWith(BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))),
				associatedDataWith(BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20")))
			)
		);
		assertFalse(
			AssociatedDataContract.anyAssociatedDataDifferBetween(
				associatedDataWith(new BigDecimal("1.0")), associatedDataWith(new BigDecimal("1.00"))
			)
		);
	}

	/**
	 * Complex data compare equal by their trees, whose range leaves compare equal by their encoded bounds. A value
	 * holding complex data that differs only in such a leaf is a different value.
	 */
	@Test
	@DisplayName("should differ from a value holding complex data differing only in a range leaf")
	void shouldDifferFromValueHoldingComplexDataDifferingOnlyInRangeLeaf() {
		final AssociatedDataKey key = new AssociatedDataKey("complex");
		final AssociatedDataValue stored = new AssociatedDataValue(key, complexDataHolding(oneToTwo()));

		assertTrue(stored.differsFrom(new AssociatedDataValue(key, complexDataHolding(tenToTwenty()))));
		assertFalse(stored.differsFrom(new AssociatedDataValue(key, complexDataHolding(oneToTwo()))));
	}

	@Test
	@DisplayName("should tell apart sets holding complex data differing only in a range leaf")
	void shouldTellApartSetsHoldingComplexDataDifferingOnlyInRangeLeaf() {
		assertTrue(
			AssociatedDataContract.anyAssociatedDataDifferBetween(
				associatedDataWith(complexDataHolding(oneToTwo())),
				associatedDataWith(complexDataHolding(tenToTwenty()))
			)
		);
		assertFalse(
			AssociatedDataContract.anyAssociatedDataDifferBetween(
				associatedDataWith(complexDataHolding(oneToTwo())),
				associatedDataWith(complexDataHolding(oneToTwo()))
			)
		);
	}

	/**
	 * Returns the range `[1.0,2.0]`.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange oneToTwo() {
		return BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
	}

	/**
	 * Returns the range `[10,20]`, which encodes to the same comparable longs as {@link #oneToTwo()}.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange tenToTwenty() {
		return BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));
	}

	/**
	 * Builds complex data holding the range as an item of an array of its root map, next to a null item.
	 *
	 * @param range the range leaf
	 * @return a new complex object
	 */
	@Nonnull
	private static ComplexDataObject complexDataHolding(@Nonnull BigDecimalNumberRange range) {
		return new ComplexDataObject(
			new DataItemMap(
				Map.of("ranges", new DataItemArray(new DataItem[]{new DataItemValue(range), null}))
			)
		);
	}

	/**
	 * Builds an entity holding a single associated data value.
	 *
	 * @param value the value
	 * @return the entity, whose associated data are compared
	 */
	@Nonnull
	private static AssociatedDataContract associatedDataWith(@Nonnull Serializable value) {
		return new InitialEntityBuilder(EntitySchema._internalBuild("product"))
			.setAssociatedData("value", value)
			.toInstance();
	}
}
