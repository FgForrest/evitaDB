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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.offsetIndex.model;

import io.evitadb.store.offsetIndex.OffsetIndex;

import java.io.Serial;
import java.io.Serializable;

/**
 * Each record that is stored via {@link StorageRecord} and maintained by {@link OffsetIndex}
 * must be uniquely identified by this key.
 *
 * @param recordType Id of the record type gathered from {@link OffsetIndexRecordTypeRegistry#idFor(Class)}
 * @param primaryKey Primary key of the record.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record RecordKey(byte recordType, long primaryKey) implements Serializable, Comparable<RecordKey> {
	@Serial private static final long serialVersionUID = 7212147121525140183L;

	/**
	 * Comparable keys are optimal for HashMaps handling.
	 */
	@Override
	public int compareTo(RecordKey o) {
		final int result = Byte.compare(recordType, o.recordType);
		return result == 0 ? Long.compare(primaryKey, o.primaryKey) : result;
	}

}
