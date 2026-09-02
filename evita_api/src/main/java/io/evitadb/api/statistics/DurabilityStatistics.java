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

package io.evitadb.api.statistics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The {@link CatalogStatisticsComponent#DURABILITY} component - how far behind the physical device this catalog is
 * allowed to run, and what the last checkpoint to catch it up cost.
 *
 * Every figure is an in-memory read of state the checkpoint path already maintains; nothing here touches the file
 * system.
 *
 * **What a checkpoint is here.** Writes are forced to the device and the bootstrap record pointing at them written,
 * no more often than the configured interval. Between checkpoints the catalog is crash-consistent but *behind*: a
 * crash replays the write-ahead log from the last checkpoint forward. That window is what this component measures.
 *
 * **This is the time-domain answer to "how much replay would a crash cost me right now".**
 * {@link CommitPipelineStatistics#durabilityLag()} answers the same question in catalog versions. Neither derives
 * from the other - a handful of large transactions and a flood of small ones give the same version lag and very
 * different fence depths - so a management screen wanting the operator-legible number wants this one.
 *
 * **The counters are process-scoped** and `countingSince` is what makes them readable: `checkpointsCompleted` starts
 * at zero when the catalog is opened and is not persisted, so reading it as "checkpoints this catalog has ever taken"
 * is wrong by everything before that open. Same treatment as {@link ActivityStatistics}.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog. A catalog that checkpoints at the end of *every* round reports
 * {@link ComponentAvailability#FEATURE_DISABLED} rather than a fence of depth zero, and the reason names which of the
 * two causes applies: no checkpoint interval is configured, or writes are not synced to the device at all. The second
 * matters - with syncing off, zeroes here would read as *durability is instant and free* when they actually mean
 * durability is not happening.
 *
 * @param checkpointIntervalMillis the configured interval a checkpoint is deferred by (milliseconds). The upper bound
 *                                 on how long a change may wait to become durable, and therefore the knob that trades
 *                                 write throughput against crash-replay cost
 * @param lastCadenceMillis        time between the last two completed checkpoints (milliseconds); `0` before the first
 *                                 one completes. Sustained values above `checkpointIntervalMillis` mean checkpointing
 *                                 is not keeping up with the write rate
 * @param lastFenceDepthMillis     how long the oldest change covered by the last checkpoint waited to become durable
 *                                 (milliseconds); `0` when that round checkpointed without deferring anything
 * @param lastFilesForced          number of files the last checkpoint forced to the device
 * @param lastForceDurationMillis  wall-clock time those forces took (milliseconds) - the cost the interval exists to
 *                                 amortise, paid once per checkpoint instead of once per round
 * @param checkpointsCompleted     checkpoints completed since `countingSince`
 * @param lastCheckpointAt         when the last checkpoint completed, or `null` when none has since this catalog was
 *                                 opened
 * @param countingSince            the instant `checkpointsCompleted` was zeroed - never before this catalog was opened
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record DurabilityStatistics(
	long checkpointIntervalMillis,
	long lastCadenceMillis,
	long lastFenceDepthMillis,
	int lastFilesForced,
	long lastForceDurationMillis,
	long checkpointsCompleted,
	@Nullable OffsetDateTime lastCheckpointAt,
	@Nonnull OffsetDateTime countingSince
) {

	/**
	 * When the last checkpoint completed, if one has.
	 *
	 * `null` means no checkpoint has completed since this catalog was opened - a freshly opened or write-idle
	 * catalog, not a stalled one. Distinguish it from a stall by `lastCadenceMillis`, which stays `0` in the same
	 * situation, together with the write rate in {@link ActivityStatistics}.
	 *
	 * @return the last checkpoint's completion time, or empty when none has completed
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastCheckpointAtIfKnown() {
		return Optional.ofNullable(this.lastCheckpointAt);
	}

	/**
	 * Whether the last checkpoint's fence depth exceeded the interval that was supposed to bound it.
	 *
	 * The interval is the *target* upper bound on how long a change waits for the device, not a guarantee: a
	 * checkpoint can only start when the round that owes it finishes, and a slow device stretches the force itself.
	 * A fence depth past the interval is therefore the signal that durability is falling behind the configured
	 * policy, which is the state worth alerting on rather than any absolute depth.
	 *
	 * @return `true` when the last checkpoint let a change wait longer than the configured interval
	 */
	public boolean fenceOverdue() {
		return this.checkpointIntervalMillis > 0L && this.lastFenceDepthMillis > this.checkpointIntervalMillis;
	}

}
