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

package io.evitadb.core.cache.model;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.BitUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Type of record in cache.
 */
@Getter
@RequiredArgsConstructor
public enum CacheRecordType {

	FORMULA((byte) 7), SORTED_RESULT((byte) 6), EXTRA_RESULT((byte) 5), ENTITY((byte) 4);

	/**
	 * Offset of the flat in bitmask.
	 */
	private final byte offset;

	/**
	 * Extracts the cache record type from the bitset byte.
	 * @param flags bitset byte
	 * @return cache record type
	 * @throws GenericEvitaInternalError if the flags do not represent any known cache record type
	 */
	@Nonnull
	public static CacheRecordType fromBitset(byte flags) {
		for (CacheRecordType value : values()) {
			if (BitUtils.isBitSet(flags, value.getOffset())) {
				return value;
			}
		}
		throw new GenericEvitaInternalError("Unknown cache record type: " + flags);
	}
}
