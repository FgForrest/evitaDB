/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import static io.evitadb.utils.BitUtils.isBitSet;
import static io.evitadb.utils.BitUtils.setBit;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link BitUtils} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class BitUtilsTest {

	@Test
	void shouldWriteAndReadSpecificByte() {
		byte control = 0;
		final byte encoded = setBit(control, (byte) 0, true);
		assertTrue(isBitSet(encoded, (byte) 0));
		assertFalse(isBitSet(encoded, (byte) 1));
		assertFalse(isBitSet(encoded, (byte) 2));
		assertFalse(isBitSet(encoded, (byte) 3));
		assertFalse(isBitSet(encoded, (byte) 4));
		assertFalse(isBitSet(encoded, (byte) 5));
		assertFalse(isBitSet(encoded, (byte) 6));
	}

	@Test
	void shouldWriteAndReadSpecificByteInTheMiddle() {
		byte control = 0;
		final byte encoded = setBit(control, (byte) 5, true);
		assertFalse(isBitSet(encoded, (byte) 0));
		assertFalse(isBitSet(encoded, (byte) 1));
		assertFalse(isBitSet(encoded, (byte) 2));
		assertFalse(isBitSet(encoded, (byte) 3));
		assertFalse(isBitSet(encoded, (byte) 4));
		assertTrue(isBitSet(encoded, (byte) 5));
		assertFalse(isBitSet(encoded, (byte) 6));
	}

	@Test
	void shouldWriteAndReadSpecificByteConsequently() {
		byte control = 0;
		final byte encoded =
			setBit(
				setBit(
					setBit(control, (byte) 7, true),
					(byte) 5, true
				),
				(byte) 2, true
			);
		assertFalse(isBitSet(encoded, (byte) 0));
		assertFalse(isBitSet(encoded, (byte) 1));
		assertTrue(isBitSet(encoded, (byte) 2));
		assertFalse(isBitSet(encoded, (byte) 3));
		assertFalse(isBitSet(encoded, (byte) 4));
		assertTrue(isBitSet(encoded, (byte) 5));
		assertFalse(isBitSet(encoded, (byte) 6));

		assertFalse(
			isBitSet(
				setBit(encoded, (byte) 2, false), (byte) 2
			)
		);
	}

}
