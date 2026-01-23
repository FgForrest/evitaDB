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
 * - `version` — monotonically increasing version of the buffer slot that allows distinguishing
 *   an old entry from a newly overwritten one even when the slot `index` is reused.
 * - `index` — physical position in the ring buffer where the conflicting operation was recorded.
 * - `conflictKey` — logical key describing the affected entity or resource as defined by
 *   {@link io.evitadb.api.requestResponse.mutation.conflict.ConflictKey ConflictKey}.
 *
 * This composite is used by the transaction/CDC infrastructure to quickly identify whether two
 * mutations target the same logical resource and originate from the same buffer slot incarnation.
 *
 * @param version	monotonically increasing version associated with the ring buffer slot
 * @param index	zero-based index of the slot within the ring buffer
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
