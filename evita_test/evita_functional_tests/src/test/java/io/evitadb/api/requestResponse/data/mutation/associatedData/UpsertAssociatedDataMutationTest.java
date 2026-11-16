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

package io.evitadb.api.requestResponse.data.mutation.associatedData;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test verifies contract of {@link UpsertAssociatedDataMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(CONTRACT)
@Tag(QUERY)
class UpsertAssociatedDataMutationTest extends AbstractMutationTest {

	@Test
	void shouldCreateNewAssociatedData() {
		final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(new AssociatedDataKey("a"), (byte) 5);
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, null);
		assertEquals((byte) 5, newValue.value());
		assertEquals(1L, newValue.version());
	}

	@Test
	void shouldIncrementVersionByUpdatingAssociatedData() {
		final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(new AssociatedDataKey("a"), (byte) 5);
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, new AssociatedDataValue(new AssociatedDataKey("a"), (byte) 3));
		assertEquals((byte) 5, newValue.value());
		assertEquals(2L, newValue.version());
	}

	/**
	 * `[1.0,2.0]` and `[10,20]` are different ranges whose bounds encode to the same comparable longs, each at its own
	 * scale. Upserting one over the other is a real change of the associated data and must replace it.
	 */
	@Test
	@DisplayName("should replace a decimal range by a different range whose bounds encode to the same longs")
	void shouldReplaceDecimalRangeByDifferentRangeEncodedToSameLongs() {
		final AssociatedDataKey key = new AssociatedDataKey("range");
		final BigDecimalNumberRange stored =
			BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
		final BigDecimalNumberRange replacement =
			BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));

		final AssociatedDataValue newValue = new UpsertAssociatedDataMutation(key, replacement)
			.mutateLocal(this.productSchema, new AssociatedDataValue(key, stored));

		assertEquals("[10,20]", String.valueOf(newValue.value()));
		assertEquals(2L, newValue.version());
	}

	/**
	 * Complex data compare equal by their trees, and a range leaf compares equal by its encoded bounds, so complex
	 * data differing only in a range leaf encoded to the same longs are equal. Upserting one over the other is a real
	 * change of the associated data and must replace it - whether the leaf is a property of a map or an item of arrays
	 * nested in it.
	 */
	@Test
	@DisplayName("should replace complex data differing only in a range leaf encoded to the same longs")
	void shouldReplaceComplexDataDifferingOnlyInRangeLeafEncodedToSameLongs() {
		final AssociatedDataKey key = new AssociatedDataKey("complex");
		final BigDecimalNumberRange oneToTwo =
			BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
		final BigDecimalNumberRange tenToTwenty =
			BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));

		final AssociatedDataValue inMap = new UpsertAssociatedDataMutation(key, rangeInMap(tenToTwenty))
			.mutateLocal(this.productSchema, new AssociatedDataValue(key, rangeInMap(oneToTwo)));
		final AssociatedDataValue inNestedArrays =
			new UpsertAssociatedDataMutation(key, rangeInNestedArrays(tenToTwenty))
				.mutateLocal(this.productSchema, new AssociatedDataValue(key, rangeInNestedArrays(oneToTwo)));

		assertEquals(rangeInMap(tenToTwenty).toString(), String.valueOf(inMap.value()));
		assertEquals(2L, inMap.version());
		assertEquals(rangeInNestedArrays(tenToTwenty).toString(), String.valueOf(inNestedArrays.value()));
		assertEquals(2L, inNestedArrays.version());
	}

	/**
	 * Complex data holding the very same range leaf as the stored one is no change.
	 */
	@Test
	@DisplayName("should keep stored complex data when complex data with the very same range leaf is upserted")
	void shouldKeepStoredComplexDataWhenComplexDataWithSameRangeLeafIsUpserted() {
		final AssociatedDataKey key = new AssociatedDataKey("complex");
		final AssociatedDataValue stored = new AssociatedDataValue(
			key, rangeInNestedArrays(BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0")))
		);

		final AssociatedDataValue newValue = new UpsertAssociatedDataMutation(
			key, rangeInNestedArrays(BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0")))
		).mutateLocal(this.productSchema, stored);

		assertSame(stored, newValue);
	}

	/**
	 * Builds complex data holding the range as a property of its root map.
	 *
	 * @param range the range leaf
	 * @return a new complex object
	 */
	@Nonnull
	private static ComplexDataObject rangeInMap(@Nonnull BigDecimalNumberRange range) {
		return new ComplexDataObject(new DataItemMap(Map.of("range", new DataItemValue(range))));
	}

	/**
	 * Builds complex data holding the range as an item of an array nested in an array of its root map, next to a null
	 * item.
	 *
	 * @param range the range leaf
	 * @return a new complex object
	 */
	@Nonnull
	private static ComplexDataObject rangeInNestedArrays(@Nonnull BigDecimalNumberRange range) {
		return new ComplexDataObject(
			new DataItemMap(
				Map.of(
					"ranges",
					new DataItemArray(
						new DataItem[]{new DataItemArray(new DataItem[]{new DataItemValue(range)}), null}
					)
				)
			)
		);
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abe"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.GERMAN), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
