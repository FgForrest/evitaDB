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

package io.evitadb.spi.store.catalog.persistence;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * The storage-layer view of how a catalog's deferred-checkpoint fence is behaving - what the last completed checkpoint
 * cost and how far behind the device the catalog is allowed to run. Produced by
 * {@link CatalogPersistenceService#measureDurability()}, which returns `null` when the catalog checkpoints at the end
 * of every round and there is no fence to describe.
 *
 * **Every figure describes the *same* checkpoint.** They are captured together at the moment one completes, because a
 * reader that assembled them field by field could pair a cadence from one checkpoint with a fence depth from the next
 * and see a combination that never occurred.
 *
 * **The counters are process-scoped.** They start at zero when the catalog's checkpoint coordinator is built and are
 * not persisted, so `countingSince` is what makes `checkpointsCompleted` readable at all.
 *
 * @param checkpointIntervalMillis  the configured interval a checkpoint is deferred by, in milliseconds
 * @param lastCadenceMillis         time between the last two completed checkpoints, in milliseconds; `0` before the
 *                                  first one completes. Sustained values above the configured interval mean
 *                                  checkpointing is not keeping up with the write rate
 * @param lastFenceDepthMillis      how long the oldest change covered by the last checkpoint waited to become durable,
 *                                  in milliseconds; `0` when that round checkpointed without deferring anything. This
 *                                  is the time-domain answer to "how much replay would a crash cost me right now" -
 *                                  distinct from `CommitPipelineStatistics#durabilityLag()`, which answers the same
 *                                  question in catalog versions
 * @param lastFilesForced           number of files the last checkpoint forced to the device
 * @param lastForceDurationMillis   wall-clock time those forces took, in milliseconds - the cost the interval exists
 *                                  to amortise, paid once per checkpoint rather than once per round
 * @param checkpointsCompleted      checkpoints completed since `countingSince`
 * @param lastCheckpointAt          when the last checkpoint completed, or `null` when none has since this catalog was
 *                                  opened
 * @param countingSince             the instant `checkpointsCompleted` was zeroed
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record DurabilitySnapshot(
	long checkpointIntervalMillis,
	long lastCadenceMillis,
	long lastFenceDepthMillis,
	int lastFilesForced,
	long lastForceDurationMillis,
	long checkpointsCompleted,
	@Nullable OffsetDateTime lastCheckpointAt,
	@Nonnull OffsetDateTime countingSince
) {
}
