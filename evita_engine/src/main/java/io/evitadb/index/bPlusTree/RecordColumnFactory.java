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

/**
 * Factory that creates a fresh empty {@link RecordColumn} of the kind chosen for a {@link TransactionalBucketBPlusTree}'s
 * single-record payload. The choice is made once per tree (threaded into every empty-leaf / split-target creation),
 * mirroring how {@link ValueColumnFactory} selects the key column kind. The default ({@link #INT}) backs the inverted /
 * owner-unique indexes with a 4-byte {@code int[]}; {@link #LONG} backs the global-unique value→entity tree with an
 * 8-byte {@code long[]} payload.
 */
@FunctionalInterface
interface RecordColumnFactory {

	/**
	 * The 4-byte {@code int[]} payload — the default for every record-set tree (inverted / owner-unique indexes).
	 */
	RecordColumnFactory INT = IntRecordColumn::new;

	/**
	 * The 8-byte {@code long[]} payload — for the global-unique value→entity tree whose payload is a packed
	 * {@code (entityType, pk)} long.
	 */
	RecordColumnFactory LONG = LongRecordColumn::new;

	/**
	 * Creates a fresh empty payload column with the given **logical** capacity (the leaf block size). The column
	 * allocates no backing storage until its first write — see {@link RecordColumn} for the logical / physical split.
	 *
	 * @param capacity the logical capacity (block size)
	 * @return a fresh empty payload column of this factory's kind
	 */
	@Nonnull
	RecordColumn create(int capacity);

}
