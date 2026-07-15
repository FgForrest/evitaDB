/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.transaction.conflict;


import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;

import javax.annotation.Nonnull;

/**
 * Represents a stable key used to track and resolve write conflicts in a ring buffer.
 *
 * The key combines:
 * - `version` — the catalog version assigned to the transaction that produced the conflict key
 *   (its commit version, never its snapshot version); versions grow monotonically with commit order,
 *   which is what keeps the ring buffer ordered and binary-searchable.
 * - `index` — zero-based ordinal of the conflict key within its transaction's conflict key set,
 *   distinguishing multiple keys registered under the same catalog version.
 * - `conflictKey` — logical key describing the affected entity or resource as defined by
 *   {@link io.evitadb.api.requestResponse.mutation.conflict.ConflictKey ConflictKey}.
 *
 * The conflict-resolution stage compares a committing transaction's snapshot version against these
 * commit versions: only keys with `version` greater than the snapshot belong to concurrent
 * transactions and are examined for overlaps.
 *
 * @param version	the commit catalog version of the transaction that registered this key
 * @param index	zero-based ordinal of the conflict key within its transaction
 * @param conflictKey	the logical conflict key describing the targeted resource, never {@code null}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record VersionedConflictKey(
	long version,
	int index,
	@Nonnull ConflictKey conflictKey
) {

}
