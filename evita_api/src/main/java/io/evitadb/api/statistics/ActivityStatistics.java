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
import java.time.OffsetDateTime;

/**
 * The {@link CatalogStatisticsComponent#ACTIVITY} component - how much write work this catalog has done, and how fast
 * it is doing it right now.
 *
 * Every figure is a counter read or an already-sampled rate; nothing here touches the file system, so this component
 * costs nothing regardless of how large the catalog is.
 *
 * **The counters are process-scoped, and `countingSince` is what makes them readable.** They start at zero when the
 * catalog is loaded and they are *not* persisted, so a client that treats `transactionsCommitted` as "transactions
 * this catalog has ever seen" is wrong by everything that happened before that load. `countingSince` is the instant
 * they were zeroed, so `transactionsCommitted / (now - countingSince)` is a lifetime average that is actually true,
 * and two consecutive polls difference into an exact rate over the poll interval. They do survive a catalog
 * *generation* switch: a commit replaces the live catalog instance but not the transaction pipeline behind it, so
 * they do not reset under write load.
 *
 * **The rates are short-window and decay while nothing is written.** They are exponentially-weighted over recent
 * commits and scaled down by the time elapsed since the last one, so a catalog written hard for a minute and then left
 * alone converges on zero instead of reporting that same minute's load forever. The same reasoning - and the same
 * treatment - as the waste accumulation rate in {@link FragmentationStatistics}. A client that wants a rate over a
 * window *it* chooses should difference two polls of the counters rather than read these.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog. A catalog in `WARMING_UP` reports
 * {@link ComponentAvailability#FEATURE_DISABLED}: writes in that state bypass the transactional pipeline entirely, so
 * every counter here would read zero no matter how much data was being ingested - *idle and healthy*, which is the
 * inverse of the truth.
 *
 * @param transactionsCommitted  transactions accepted by conflict resolution and appended to the write-ahead log since
 *                               `countingSince`; the point of no return, after which the transaction is committed
 *                               whatever happens next
 * @param transactionsRolledBack transactions discarded at session close because the session was marked rollback-only;
 *                               these never reach the pipeline at all
 * @param transactionsConflicted transactions rejected by conflict resolution because another transaction had already
 *                               claimed one of their conflict keys
 * @param mutationsApplied       mutations carried by the committed transactions - the count the transaction itself
 *                               declares, not the local mutations they expand into
 * @param walBytesAppended       bytes those transactions appended to the write-ahead log
 * @param pipelineDepth          versions accepted but not yet visible to readers, i.e.
 *                               `lastAssignedCatalogVersion - lastFinalizedCatalogVersion`. Deliberately the same
 *                               quantity as {@link CommitPipelineStatistics#writeLag()} plus
 *                               {@link CommitPipelineStatistics#visibilityLag()} - it is repeated here so a client
 *                               polling only this component can tell a busy catalog from a backed-up one without
 *                               requesting a second component
 * @param transactionsPerSecond  recent commit rate, decayed towards zero while the catalog is idle
 * @param mutationsPerSecond     recent mutation rate, on the same window
 * @param walBytesPerSecond      recent write-ahead log growth rate, on the same window
 * @param countingSince          the instant the counters above were zeroed - never before this catalog was opened
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ActivityStatistics(
	long transactionsCommitted,
	long transactionsRolledBack,
	long transactionsConflicted,
	long mutationsApplied,
	long walBytesAppended,
	long pipelineDepth,
	double transactionsPerSecond,
	double mutationsPerSecond,
	double walBytesPerSecond,
	@Nonnull OffsetDateTime countingSince
) {

	/**
	 * Transactions that ended without reaching the write-ahead log, for whichever reason.
	 *
	 * @return rolled back and conflicted transactions together
	 */
	public long transactionsFailed() {
		return this.transactionsRolledBack + this.transactionsConflicted;
	}

	/**
	 * Share of finished transactions that were rejected by conflict resolution.
	 *
	 * A rising conflict share is the signal that write contention - not throughput - is what limits this catalog.
	 * The denominator is every transaction that finished, rollbacks included: a client-initiated rollback is not
	 * itself a sign of contention, but it did occupy the pipeline, and excluding it would inflate the share whenever
	 * an application rolls back routinely.
	 *
	 * @return conflicted transactions over all finished ones, `0` when none has finished yet
	 */
	public double conflictShare() {
		final long finished = this.transactionsCommitted + transactionsFailed();
		return finished == 0L ? 0.0d : (double) this.transactionsConflicted / (double) finished;
	}

}
