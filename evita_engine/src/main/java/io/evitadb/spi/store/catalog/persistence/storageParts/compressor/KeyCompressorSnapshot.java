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

package io.evitadb.spi.store.catalog.persistence.storageParts.compressor;

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Atomic snapshot of a {@link ReadWriteKeyCompressor}'s mutable state.
 *
 * The two fields belong together: `peakId` is the highest id that has ever been allocated by the source
 * compressor's monotonic sequence, and `keys` contains every (id, key) pair allocated up to that moment.
 * Callers seeding a new {@link KeyCompressor} from the snapshot rely on the invariant `max(keys.keySet()) <= peakId`
 * — if the two fields were read independently across a writer's `getId(...)` mutation, the invariant could break
 * (e.g. `keys` includes id N while `peakId` is still N-1), causing the new compressor to allocate an id that
 * already exists in its seed map and producing silent id collisions at commit time.
 *
 * Producers must therefore capture both values inside the same critical section.
 *
 * @param keys   immutable id → key map taken from the source compressor
 * @param peakId highest id assigned by the source compressor at the moment of capture
 */
public record KeyCompressorSnapshot(
	@Nonnull Map<Integer, Object> keys,
	int peakId
) {
}
