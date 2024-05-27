/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.evitadb.utils.NumberUtils.join;
import static io.evitadb.utils.NumberUtils.split;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test verifies contract of {@link NumberUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class NumberUtilsTest {

	@Test
	void shouldJointAndIncrementSeparately() {
		assertEquals(4398046511105L, join(1024, 1));
		assertEquals(4398046511105L + 1L, join(1024, 2));
	}

	@Test
	void shouldJoinAndDecomposeIntsToLong() {
		assertArrayEquals(new int[]{1, 45}, split(join(1, 45)));
		assertArrayEquals(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}, split(join(Integer.MAX_VALUE, Integer.MIN_VALUE)));
		assertArrayEquals(new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE}, split(join(Integer.MIN_VALUE, Integer.MIN_VALUE)));
		assertArrayEquals(new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}, split(join(Integer.MIN_VALUE, Integer.MAX_VALUE)));
		assertArrayEquals(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}, split(join(Integer.MAX_VALUE, Integer.MIN_VALUE)));
		assertArrayEquals(new int[]{-10, -564}, split(join(-10, -564)));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldSumByteAndAnything() {
		assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((byte) 4)));
		assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((short) 4)));
		assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((int) 4)));
		assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((long) 4)));
		assertEquals((byte) 8, NumberUtils.sum(((byte) 4), new BigDecimal("4")));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldFailOnByteOverflow() {
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((byte) 127)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((short) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((int) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((long) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), new BigDecimal("512")));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldSumShortAndAnything() {
		assertEquals((short) 8, NumberUtils.sum(((short) 4), ((byte) 4)));
		assertEquals((short) 8, NumberUtils.sum(((short) 4), ((short) 4)));
		assertEquals((short) 8, NumberUtils.sum(((short) 4), ((int) 4)));
		assertEquals((short) 8, NumberUtils.sum(((short) 4), ((long) 4)));
		assertEquals((short) 8, NumberUtils.sum(((short) 4), new BigDecimal("4")));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldFailOnShortOverflow() {
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32766), ((byte) 127)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), ((short) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), ((int) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), ((long) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), new BigDecimal("512")));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldSumIntAndAnything() {
		assertEquals((int) 8, NumberUtils.sum(((int) 4), ((byte) 4)));
		assertEquals((int) 8, NumberUtils.sum(((int) 4), ((short) 4)));
		assertEquals((int) 8, NumberUtils.sum(((int) 4), ((int) 4)));
		assertEquals((int) 8, NumberUtils.sum(((int) 4), ((long) 4)));
		assertEquals((int) 8, NumberUtils.sum(((int) 4), new BigDecimal("4")));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldFailOnIntOverflow() {
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((byte) 127)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((short) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((int) 512)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((long) Integer.MAX_VALUE + 200)));
		assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, new BigDecimal("512")));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldSumLongAndAnything() {
		assertEquals((long) 8, NumberUtils.sum(((long) 4), ((byte) 4)));
		assertEquals((long) 8, NumberUtils.sum(((long) 4), ((short) 4)));
		assertEquals((long) 8, NumberUtils.sum(((long) 4), ((int) 4)));
		assertEquals((long) 8, NumberUtils.sum(((long) 4), ((long) 4)));
		assertEquals((long) 8, NumberUtils.sum(((long) 4), new BigDecimal("4")));
		assertEquals((long) 0, NumberUtils.sum(((long) 4), ((long) -4)));
	}

	@SuppressWarnings("RedundantCast")
	@Test
	void shouldSumBigDecimalAndAnything() {
		assertEquals(new BigDecimal("8"), NumberUtils.sum(new BigDecimal("4"), ((byte) 4)));
		assertEquals(new BigDecimal("8"), NumberUtils.sum(new BigDecimal("4"), ((short) 4)));
		assertEquals(new BigDecimal("8"), NumberUtils.sum(new BigDecimal("4"), ((int) 4)));
		assertEquals(new BigDecimal("8.2"), NumberUtils.sum(new BigDecimal("4.2"), ((long) 4)));
		assertEquals(new BigDecimal("9.0"), NumberUtils.sum(new BigDecimal("4.2"), new BigDecimal("4.8")));
		assertEquals(new BigDecimal("-0.6"), NumberUtils.sum(new BigDecimal("4.2"), new BigDecimal("-4.8")));
	}

	@Test
	void shouldConvertNumbersToInt() {
		assertEquals(2, NumberUtils.convertToInt((byte) 2));
		assertEquals(2, NumberUtils.convertToInt((short) 2));
		assertEquals(2, NumberUtils.convertToInt(2));
		assertEquals(2, NumberUtils.convertToInt((long) 2));
	}

	@Test
	void shouldFailToConvertBigNumbersToInt() {
		assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt(1.1f));
		assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt((double) 1.1f));
		assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt(Long.MAX_VALUE));
	}

	@Test
	void shouldConvertBigDecimalToInt() {
		assertEquals(11020, NumberUtils.convertToInt(new BigDecimal("110.2"), 2));
		assertEquals(11020, NumberUtils.convertToInt(new BigDecimal("110.20"), 2));
		assertEquals(11020, NumberUtils.convertToInt(new BigDecimal("110.2000"), 2));
		assertThrows(IllegalArgumentException.class, () -> NumberUtils.convertToInt(new BigDecimal("110.202"), 2));
	}
}
