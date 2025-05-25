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

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.dataType.ByteNumberRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies constract of {@link ApplyDeltaAttributeMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ApplyDeltaAttributeMutationTest extends AbstractMutationTest {

	@Test
	void shouldIncrementVersionByUpdatingAttribute() {
		final ApplyDeltaAttributeMutation<Byte> mutation = new ApplyDeltaAttributeMutation<>("a", (byte)5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (byte)3));
		assertEquals(2L, newValue.version());
	}

	@Test
	void shouldIncrementExistingByteValue() {
		final ApplyDeltaAttributeMutation<Byte> mutation = new ApplyDeltaAttributeMutation<>("a", (byte)5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey( "a"), (byte)3));
		assertEquals((byte) 8, (byte) newValue.value());
	}

	@Test
	void shouldDecrementExistingByteValue() {
		final ApplyDeltaAttributeMutation<Byte> mutation = new ApplyDeltaAttributeMutation<>("a", (byte)-5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (byte)3));
		assertEquals((byte)-2, (byte) newValue.value());
	}

	@Test
	void shouldIncrementExistingShortValue() {
		final ApplyDeltaAttributeMutation<Short> mutation = new ApplyDeltaAttributeMutation<>("a", (short)5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (short)3));
		assertEquals((short) 8, (short) newValue.value());
	}

	@Test
	void shouldDecrementExistingShortValue() {
		final ApplyDeltaAttributeMutation<Short> mutation = new ApplyDeltaAttributeMutation<>("a", (short)-5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (short)3));
		assertEquals((short)-2, (short) newValue.value());
	}

	@Test
	void shouldIncrementExistingIntValue() {
		final ApplyDeltaAttributeMutation<Integer> mutation = new ApplyDeltaAttributeMutation<>("a", 5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), 3));
		assertEquals(8, (int) newValue.value());
	}

	@Test
	void shouldDecrementExistingIntValue() {
		final ApplyDeltaAttributeMutation<Integer> mutation = new ApplyDeltaAttributeMutation<>("a", -5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), 3));
		assertEquals(-2, (int) newValue.value());
	}

	@Test
	void shouldIncrementExistingLongValue() {
		final ApplyDeltaAttributeMutation<Long> mutation = new ApplyDeltaAttributeMutation<>("a", (long)5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (long)3));
		assertEquals(8, (long) newValue.value());
	}

	@Test
	void shouldDecrementExistingLongValue() {
		final ApplyDeltaAttributeMutation<Long> mutation = new ApplyDeltaAttributeMutation<>("a", (long)-5);
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (long)3));
		assertEquals(-2, (long) newValue.value());
	}

	@Test
	void shouldIncrementExistingBigDecimalValue() {
		final ApplyDeltaAttributeMutation<BigDecimal> mutation = new ApplyDeltaAttributeMutation<>("a", new BigDecimal("5.123"));
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), new BigDecimal("3.005")));
		assertEquals(new BigDecimal("8.128"), newValue.value());
	}

	@Test
	void shouldDecrementExistingBigDecimalValue() {
		final ApplyDeltaAttributeMutation<BigDecimal> mutation = new ApplyDeltaAttributeMutation<>("a", new BigDecimal("-5.123"));
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), new BigDecimal("3.005")));
		assertEquals(new BigDecimal("-2.118"), newValue.value());
	}

	@Test
	void shouldPassRangeCheckWithValidValue() {
		final ApplyDeltaAttributeMutation<Byte> mutation = new ApplyDeltaAttributeMutation<>("a", (byte)5, ByteNumberRange.from((byte)7));
		final AttributeValue newValue = mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (byte)3));
		assertEquals((byte) 8, (byte) newValue.value());
	}

	@Test
	void shouldFailRangeCheckWithInvalidValue() {
		final ApplyDeltaAttributeMutation<Byte> mutation = new ApplyDeltaAttributeMutation<>("a", (byte)5, ByteNumberRange.from((byte)7));
		assertThrows(InvalidMutationException.class, () -> mutation.mutateLocal(this.productSchema, new AttributeValue(new AttributeKey("a"), (byte)1)));
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
				new ApplyDeltaAttributeMutation<>("abc", 5).getSkipToken(this.catalogSchema, this.productSchema),
				new ApplyDeltaAttributeMutation<>("abc", 6).getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertEquals(
				new ApplyDeltaAttributeMutation<>("abc", Locale.ENGLISH, 5).getSkipToken(this.catalogSchema, this.productSchema),
				new ApplyDeltaAttributeMutation<>("abc", Locale.ENGLISH, 6).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
				new ApplyDeltaAttributeMutation<>("abc", 5).getSkipToken(this.catalogSchema, this.productSchema),
				new ApplyDeltaAttributeMutation<>("abe", 6).getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
				new ApplyDeltaAttributeMutation<>("abc", Locale.ENGLISH, 5).getSkipToken(this.catalogSchema, this.productSchema),
				new ApplyDeltaAttributeMutation<>("abc", Locale.GERMAN, 6).getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

}
