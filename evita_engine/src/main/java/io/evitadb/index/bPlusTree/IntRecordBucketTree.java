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

import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The write surface of an `int` record-set bucket tree: each key maps to a set of `int` record ids (a lean single-record
 * bucket that promotes to a {@link io.evitadb.index.bitmap.TransactionalBitmap} on the second distinct id). This is the
 * default bucket tree backing the inverted and owner-unique indexes. The mutually-exclusive single-`long` write surface
 * lives on {@link LongPayloadBucketTree}; a reference of this type cannot reach it.
 *
 * @param <K> the key (bucket value) type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface IntRecordBucketTree<K extends Comparable<K>> extends BucketBPlusTree<K> {

	/**
	 * Adds a single record id into the bucket with the specified value, creating the bucket when absent and promoting it
	 * to a multi-record bitmap on the second distinct id.
	 *
	 * @param value the value identifying the bucket
	 * @param pk    the record id to add (may be any int, including negative ids)
	 */
	void addRecord(@Nonnull K value, int pk);

	/**
	 * Adds multiple record ids into the bucket with the specified value, creating the bucket when absent and promoting it
	 * to a multi-record bitmap as needed.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to add; must be non-empty (may contain negative ids)
	 */
	void addRecord(@Nonnull K value, @Nonnull int... pks);

	/**
	 * Removes one or multiple record ids from the bucket with the specified value, deleting the bucket when it drops to
	 * zero records.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to remove; must be non-empty (may contain negative ids)
	 */
	void removeRecord(@Nonnull K value, @Nonnull int... pks);

	/**
	 * Returns the record set associated with the given value (a lean single-record view, a transactional bitmap, or the
	 * empty bitmap when absent).
	 *
	 * @param value the value to look up (may be null ⇒ empty bitmap)
	 * @return the record set for the value, never null
	 */
	@Nonnull
	Bitmap getRecordsEqualTo(@Nullable K value);

}
