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

/**
 * Utility class for working with individual bits in a byte value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class BitUtils {

	/**
	 * Sets bit at specified position to 1 in an arbitrary byte value. Only bit at specified index is changed, other
	 * bits stay the same.
	 */
	public static byte setBit(byte encoded, byte index, boolean bit) {
		byte bits = (byte) ((byte) 1 << index);
		if (bit) {
			return (byte) (encoded | bits);
		} else {
			return (byte) (encoded & (~bits));
		}
	}

	/**
	 * Reads bit at specified position and returns TRUE if bit is set to 1.
	 */
	public static boolean isBitSet(byte encoded, byte index) {
		return ((encoded & 0xff) & (1 << index)) != 0;
	}

	/**
	 * Copies part of the bit set from the byte value starting at specified position to new byte.
	 * The bytes that will be stripped from the beginning will be added at the and as ones.
	 *
	 * @param startPosition start index (inclusive)
	 * @param b byte value
	 * @return new byte value with copied bits
	 */
	public static byte copyBitSetFrom(byte startPosition, byte b) {
		return (byte) (b >> startPosition);
	}

}
