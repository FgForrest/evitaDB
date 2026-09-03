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
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	@DisplayName("should reject a moment evitaDB cannot represent")
	void shouldRejectUnrepresentableMoment() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new UpsertAttributeMutation(new AttributeKey("validity"), LocalDateTime.MAX)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new UpsertAttributeMutation(new AttributeKey("validity"), OffsetDateTime.MIN)
		);
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
