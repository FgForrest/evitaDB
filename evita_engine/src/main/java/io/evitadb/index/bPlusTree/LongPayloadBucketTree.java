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

package io.evitadb.index.bPlusTree;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalLong;

/**
 * The write surface of a UNIQUE single-`long` bucket tree: each key holds exactly one `long` payload and is NEVER
 * promoted to the overflow bitmap (uniqueness is enforced by the caller). This backs the global-unique value→entity
 * index, where the payload is a packed `(entityType, pk)` tuple. The mutually-exclusive `int` record-set write surface
 * lives on {@link IntRecordBucketTree}; a reference of this type cannot reach it.
 *
 * @param <K> the key (bucket value) type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface LongPayloadBucketTree<K extends Comparable<K>> extends BucketBPlusTree<K> {

	/**
	 * Adds a value→`long` bucket holding exactly one payload. The value MUST be absent — uniqueness is enforced by the
	 * caller, so a key already present is a programming error.
	 *
	 * @param value   the value identifying the bucket
	 * @param payload the lone `long` payload to store
	 */
	void addLongRecord(@Nonnull K value, long payload);

	/**
	 * Returns the `long` payload of the bucket identified by the given value, or an empty result when the value is absent.
	 *
	 * @param value the value to look up (may be null ⇒ empty)
	 * @return the bucket's payload, or empty when absent
	 */
	@Nonnull
	OptionalLong getLongRecordEqualTo(@Nullable K value);

	/**
	 * Removes the value→`long` bucket identified by the given value, rebalancing the tree as needed.
	 *
	 * @param value the value identifying the bucket to remove
	 * @return true if a bucket was removed, false when the value was absent
	 */
	boolean removeLongRecord(@Nonnull K value);

}
