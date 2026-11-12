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

package io.evitadb.api.requestResponse.data.mutation.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies contract of {@link UpsertAttributeMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(ATTRIBUTE)
class UpsertAttributeMutationTest extends AbstractMutationTest {

	@Test
	void shouldCreateNewAttribute() {
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation(new AttributeKey("a"), (byte)5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, null);
		assertEquals((byte)5, newValue.value());
		assertEquals(1L, newValue.version());
	}

	@Test
	void shouldIncrementVersionByUpdatingAttribute() {
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation(new AttributeKey("a"), (byte)5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (byte)3));
		assertEquals((byte) 5, newValue.value());
		assertEquals(2L, newValue.version());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
				new UpsertAttributeMutation(new AttributeKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
				new UpsertAttributeMutation(new AttributeKey("abc"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertEquals(
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
				new UpsertAttributeMutation(new AttributeKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
				new UpsertAttributeMutation(new AttributeKey("abe"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
				new UpsertAttributeMutation(new AttributeKey("abc", Locale.GERMAN), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	@DisplayName("should cut an OffsetDateTime to whole milliseconds on the way in")
	void shouldTruncateOffsetDateTimeValue() {
		// the `456789` nanoseconds beyond the 123 milliseconds are what the mutation has to discard
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation(
			new AttributeKey("validity"),
			OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789, ZoneOffset.UTC)
		);

		assertEquals(
			OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000, ZoneOffset.UTC),
			mutation.getAttributeValue()
		);
		assertEquals(123_000_000, ((OffsetDateTime) mutation.getAttributeValue()).getNano());
	}

	@Test
	@DisplayName("should cut a LocalDateTime to whole milliseconds and keep its declared type")
	void shouldTruncateLocalDateTimeValue() {
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation(
			new AttributeKey("publishedAt"),
			LocalDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789)
		);

		assertInstanceOf(LocalDateTime.class, mutation.getAttributeValue());
		assertEquals(
			LocalDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000),
			mutation.getAttributeValue()
		);
	}

	@Test
	@DisplayName("should cut a LocalTime to whole milliseconds")
	void shouldTruncateLocalTimeValue() {
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation(
			new AttributeKey("openedAt"),
			LocalTime.of(10, 15, 30, 123_456_789)
		);

		assertEquals(LocalTime.of(10, 15, 30, 123_000_000), mutation.getAttributeValue());
	}

	@Test
	@DisplayName("should cut every element of a temporal array")
	void shouldTruncateTemporalArrayValues() {
		final UpsertAttributeMutation offsets = new UpsertAttributeMutation(
			new AttributeKey("validityPoints"),
			new OffsetDateTime[]{
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789, ZoneOffset.UTC),
				OffsetDateTime.of(2021, 6, 16, 11, 45, 0, 999_999_999, ZoneOffset.UTC)
			}
		);
		final UpsertAttributeMutation locals = new UpsertAttributeMutation(
			new AttributeKey("publishedAtPoints"),
			new LocalDateTime[]{LocalDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789)}
		);
		final UpsertAttributeMutation times = new UpsertAttributeMutation(
			new AttributeKey("openedAtPoints"),
			new LocalTime[]{LocalTime.of(10, 15, 30, 123_456_789)}
		);

		assertArrayEqualsExactly(
			new OffsetDateTime[]{
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000, ZoneOffset.UTC),
				OffsetDateTime.of(2021, 6, 16, 11, 45, 0, 999_000_000, ZoneOffset.UTC)
			},
			offsets.getAttributeValue()
		);
		assertArrayEqualsExactly(
			new LocalDateTime[]{LocalDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000)},
			locals.getAttributeValue()
		);
		assertArrayEqualsExactly(
			new LocalTime[]{LocalTime.of(10, 15, 30, 123_000_000)},
			times.getAttributeValue()
		);
	}

	@Test
	@DisplayName("should carry the truncated value all the way into the attribute")
	void shouldStoreTruncatedValueInAttribute() {
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation(
			new AttributeKey("validity"),
			OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789, ZoneOffset.UTC)
		);

		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, null);

		assertEquals(
			OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000, ZoneOffset.UTC),
			newValue.value()
		);
	}

	@Test
	@DisplayName("should clamp a moment beyond the representable window to its edge")
	void shouldClampUnrepresentableMoment() {
		final UpsertAttributeMutation latest = new UpsertAttributeMutation(
			new AttributeKey("validity"), LocalDateTime.MAX
		);
		final UpsertAttributeMutation earliest = new UpsertAttributeMutation(
			new AttributeKey("validity"), OffsetDateTime.MIN
		);

		// the declared type is kept, the moment becomes the last / first whole millisecond evitaDB can hold
		final Instant latestInstant = Instant.ofEpochMilli((Long.MAX_VALUE / 1_000L - 1L) * 1_000L + 999L);
		final Instant earliestInstant = Instant.ofEpochMilli((Long.MIN_VALUE / 1_000L + 1L) * 1_000L);
		assertEquals(LocalDateTime.ofInstant(latestInstant, ZoneOffset.UTC), latest.getAttributeValue());
		assertEquals(OffsetDateTime.ofInstant(earliestInstant, ZoneOffset.MAX), earliest.getAttributeValue());
	}

	/**
	 * `[1.0,2.0]` and `[10,20]` are different ranges whose bounds encode to the same comparable longs, each at its own
	 * scale. Upserting one over the other is a real change of the value and must replace it.
	 */
	@Test
	@DisplayName("should replace a decimal range by a different range whose bounds encode to the same longs")
	void shouldReplaceDecimalRangeByDifferentRangeEncodedToSameLongs() {
		final AttributeKey key = new AttributeKey("range");
		final BigDecimalNumberRange stored =
			BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
		final BigDecimalNumberRange replacement =
			BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));

		final AttributeValue newValue = new UpsertAttributeMutation(key, replacement)
			.mutateLocal(this.productSchema, new AttributeValue(key, stored));

		assertEquals("[10,20]", String.valueOf(newValue.value()));
		assertEquals(2L, newValue.version());
	}

	/**
	 * A date-time range naming the same instants at another zone offset compares equal by its encoded bounds, yet a
	 * reader gets back other bounds. Upserting it is a real change, as it is for a plain offset date-time.
	 */
	@Test
	@DisplayName("should replace a date-time range by a range of the same instants at another offset")
	void shouldReplaceDateTimeRangeByRangeOfSameInstantsAtAnotherOffset() {
		final AttributeKey key = new AttributeKey("validity");
		final LocalDateTime noon = LocalDateTime.of(2024, 1, 1, 12, 0);
		final DateTimeRange stored = DateTimeRange.between(
			noon.atOffset(ZoneOffset.ofHours(2)), noon.plusHours(1).atOffset(ZoneOffset.ofHours(2))
		);
		final DateTimeRange replacement = DateTimeRange.between(
			noon.minusHours(1).atOffset(ZoneOffset.ofHours(1)), noon.atOffset(ZoneOffset.ofHours(1))
		);

		final AttributeValue newValue = new UpsertAttributeMutation(key, replacement)
			.mutateLocal(this.productSchema, new AttributeValue(key, stored));

		assertEquals(replacement.toString(), String.valueOf(newValue.value()));
		assertEquals(2L, newValue.version());
	}

	/**
	 * Arrays are compared element by element under the same rule as single values: an array of ranges encoded to the
	 * same longs as the stored ones is a real change.
	 *
	 * The test does not fail where arrays are compared by identity, because there every new array instance counts as
	 * a change. What it pins is the element comparison that replaced the identity one: comparing the elements by
	 * `equals` would drop this upsert, since the elements are equal by their encoded bounds.
	 */
	@Test
	@DisplayName("should replace a decimal range array by an array of ranges encoded to the same longs")
	void shouldReplaceDecimalRangeArrayByArrayOfRangesEncodedToSameLongs() {
		final AttributeKey key = new AttributeKey("ranges");
		final BigDecimalNumberRange[] stored = {
			BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))
		};
		final BigDecimalNumberRange[] replacement = {
			BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"))
		};

		final AttributeValue newValue = new UpsertAttributeMutation(key, replacement)
			.mutateLocal(this.productSchema, new AttributeValue(key, stored));

		assertEquals("[10,20]", String.valueOf(((Object[]) newValue.value())[0]));
		assertEquals(2L, newValue.version());
	}

	/**
	 * An array holding the very same values as the stored one is no change, exactly as a single value equal to the
	 * stored one is not - the array instances are compared by their elements, not by identity.
	 */
	@Test
	@DisplayName("should keep the stored value when an array of the very same values is upserted")
	void shouldKeepStoredValueWhenArrayOfSameValuesIsUpserted() {
		final AttributeKey key = new AttributeKey("ranges");
		final AttributeValue stored = new AttributeValue(
			key,
			new BigDecimalNumberRange[]{
				BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))
			}
		);

		final AttributeValue newValue = new UpsertAttributeMutation(
			key,
			new BigDecimalNumberRange[]{
				BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))
			}
		).mutateLocal(this.productSchema, stored);

		assertSame(stored, newValue);
	}

	/**
	 * Compares the mutation's array value element by element against the exact expected values.
	 *
	 * @param expected the exact values every element must equal
	 * @param actual   the mutation's array value
	 */
	private static void assertArrayEqualsExactly(@Nonnull Serializable[] expected, @Nonnull Serializable actual) {
		final Serializable[] actualArray = (Serializable[]) actual;
		assertEquals(expected.length, actualArray.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actualArray[i]);
		}
	}

}
