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

package io.evitadb.store.offsetIndex.model;

import javax.annotation.Nonnull;

/**
 * How much of a data store the records of a single {@link RecordKey#recordType()} occupy - both how many there are and
 * how many bytes they take.
 *
 * The record-type histogram used to carry the count alone, which can invert the answer it exists to give: half
 * a million small attribute records read as dominant by count while a couple of thousand large associated-data blobs
 * dominate the actual file. Both numbers are accumulated at the same statements, so carrying the pair costs one object
 * per record type per retained version instead of one boxed `Integer`.
 *
 * **This type doubles as a signed delta** while a flush is being promoted: `count` may be negative (records removed)
 * and `totalBytes` may be negative (a record replaced by a smaller one). Only the values held in the per-version
 * histogram itself are guaranteed non-negative.
 *
 * @param count      number of records of this type currently held
 * @param totalBytes total bytes those records occupy on disk
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record RecordTypeUsage(
	int count,
	long totalBytes
) {

	/**
	 * The zero usage - what a record type that is not present in the histogram amounts to.
	 */
	public static final RecordTypeUsage EMPTY = new RecordTypeUsage(0, 0L);

	/**
	 * Adds another usage (or signed delta) to this one.
	 *
	 * @param other the usage to add; both of its components may be negative when it represents a delta
	 * @return the summed usage
	 */
	@Nonnull
	public RecordTypeUsage plus(@Nonnull RecordTypeUsage other) {
		return new RecordTypeUsage(this.count + other.count, this.totalBytes + other.totalBytes);
	}

}
