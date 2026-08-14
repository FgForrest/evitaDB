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

package io.evitadb.core.transaction;

import javax.annotation.Nonnull;

/**
 * How much write work has passed through the commit pipeline, and how fast it is passing through right now - the
 * input behind {@link io.evitadb.api.statistics.ActivityStatistics}.
 *
 * The three counters and the three rates live in one record on purpose. They are all sampled at the same point - the
 * moment a transaction's bytes reach the write-ahead log, which is the transaction's point of no return - so a reader
 * that saw them separately could observe a byte count from one transaction against a transaction count from the next.
 * Publishing them through a single reference makes every read internally consistent, exactly as `WasteAccumulation`
 * does for one data file in the storage layer.
 *
 * **The rates decay while nothing is committed, and they have to.** An exponentially-weighted average sampled per
 * commit cannot observe an idle period: no commit happens, so no sample is taken, and the last rate would stand until
 * the process ends. A catalog written hard for a minute and then left alone would report that minute's load forever.
 * Reading a rate through one of the `effective...` methods folds the elapsed idle time in, so a quiet catalog
 * converges on zero on its own. The stored rates are the undecayed measurements and are not what a client should be
 * shown.
 *
 * Counters that are *not* sampled here - rolled back and conflicted transactions - are plain counters on
 * {@link TransactionManager}. They are incremented on entirely different paths (session close and conflict
 * resolution respectively), so folding them in would mean contending on this record from three places to gain a
 * consistency nothing reads.
 *
 * @param transactionsCommitted    transactions whose bytes reached the write-ahead log since this instance was created
 * @param mutationsApplied         mutations those transactions carried
 * @param walBytesAppended         bytes those transactions appended to the write-ahead log
 * @param transactionRatePerSecond exponentially-weighted commit rate as of the last commit, before idle-time decay;
 *                                 `0` until two commits have been observed
 * @param mutationRatePerSecond    the same for mutations
 * @param walByteRatePerSecond     the same for write-ahead log bytes
 * @param transactionsAtLastSample the commit counter as the currently-open sampling window opened, so the next
 *                                 measurable commit divides *everything* committed since then rather than only itself
 * @param mutationsAtLastSample    the mutation counter at the same moment, for the same reason
 * @param walBytesAtLastSample     the write-ahead log counter at the same moment, for the same reason
 * @param lastSampleAtMillis       epoch millis of the commit that produced the rates; `0` when none has been observed
 * @param lastSampleIntervalMillis how long the interval between the last two commits was, which is the cadence the
 *                                 idle-time decay measures staleness against; `0` before a second commit
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ActivityAccumulation(
	long transactionsCommitted,
	long mutationsApplied,
	long walBytesAppended,
	double transactionRatePerSecond,
	double mutationRatePerSecond,
	double walByteRatePerSecond,
	long transactionsAtLastSample,
	long mutationsAtLastSample,
	long walBytesAtLastSample,
	long lastSampleAtMillis,
	long lastSampleIntervalMillis
) {

	/**
	 * A pipeline that has not committed anything yet.
	 */
	public static final ActivityAccumulation NONE =
		new ActivityAccumulation(0L, 0L, 0L, 0.0d, 0.0d, 0.0d, 0L, 0L, 0L, 0L, 0L);

	/**
	 * Weight given to the newest sample. Low enough that one bulk transaction does not dominate the reported rate,
	 * high enough that a genuine change in write load shows up within a handful of commits.
	 */
	private static final double SMOOTHING_FACTOR = 0.3d;

	/**
	 * Folds one committed transaction into the accumulation.
	 *
	 * The first commit only opens the sampling window - there is no preceding sample to measure an interval against,
	 * so it contributes to the counters and leaves the rates alone. The second one seeds each average with its own
	 * value instead of blending it into a zero, which would otherwise halve the very first rate reported.
	 *
	 * **Every transaction lands in exactly one sample, including those committed inside an already-sampled
	 * millisecond.** Each numerator is the counter's movement across the whole open window - not this one
	 * transaction's contribution - so a burst of commits sharing a millisecond is carried into the next measurable
	 * sample rather than dropped from the rate. Fifty commits in one millisecond followed by one a tenth of a second
	 * later is a burst of fifty-one, not of one.
	 *
	 * @param mutationCount   mutations this transaction carried
	 * @param walBytes        bytes it appended to the write-ahead log
	 * @param sampledAtMillis epoch millis at which its bytes reached the log
	 * @return the accumulation including this transaction
	 */
	@Nonnull
	public ActivityAccumulation sampled(int mutationCount, long walBytes, long sampledAtMillis) {
		final long committed = this.transactionsCommitted + 1L;
		final long mutations = this.mutationsApplied + mutationCount;
		final long bytes = this.walBytesAppended + walBytes;
		if (this.lastSampleAtMillis == 0L) {
			// the window opens here: this transaction landed over an interval nothing observed, so it seeds the
			// counters but must not become the numerator of the next sample
			return new ActivityAccumulation(
				committed, mutations, bytes,
				this.transactionRatePerSecond, this.mutationRatePerSecond, this.walByteRatePerSecond,
				committed, mutations, bytes,
				sampledAtMillis, this.lastSampleIntervalMillis
			);
		}
		final long intervalMillis = sampledAtMillis - this.lastSampleAtMillis;
		if (intervalMillis <= 0L) {
			// two commits inside the same millisecond - there is no interval to divide by, so the counters move and
			// the window stays open until a measurable one comes along. The window's opening counters stay put with
			// it, which is what keeps these commits in the next sample instead of losing them
			return new ActivityAccumulation(
				committed, mutations, bytes,
				this.transactionRatePerSecond, this.mutationRatePerSecond, this.walByteRatePerSecond,
				this.transactionsAtLastSample, this.mutationsAtLastSample, this.walBytesAtLastSample,
				this.lastSampleAtMillis, this.lastSampleIntervalMillis
			);
		}
		final boolean seeding = this.lastSampleIntervalMillis == 0L;
		return new ActivityAccumulation(
			committed, mutations, bytes,
			smooth(this.transactionRatePerSecond, committed - this.transactionsAtLastSample, intervalMillis, seeding),
			smooth(this.mutationRatePerSecond, mutations - this.mutationsAtLastSample, intervalMillis, seeding),
			smooth(this.walByteRatePerSecond, bytes - this.walBytesAtLastSample, intervalMillis, seeding),
			committed, mutations, bytes,
			sampledAtMillis, intervalMillis
		);
	}

	/**
	 * Returns the commit rate as it stands at the given moment, with the time elapsed since the last commit folded in.
	 *
	 * @param nowMillis epoch millis to read the rate at
	 * @return transactions per second, `0` when none are being committed
	 */
	public double effectiveTransactionsPerSecond(long nowMillis) {
		return decayed(this.transactionRatePerSecond, nowMillis);
	}

	/**
	 * Returns the mutation rate as it stands at the given moment, with the time elapsed since the last commit folded
	 * in.
	 *
	 * @param nowMillis epoch millis to read the rate at
	 * @return mutations per second, `0` when none are being applied
	 */
	public double effectiveMutationsPerSecond(long nowMillis) {
		return decayed(this.mutationRatePerSecond, nowMillis);
	}

	/**
	 * Returns the write-ahead log growth rate as it stands at the given moment, with the time elapsed since the last
	 * commit folded in.
	 *
	 * @param nowMillis epoch millis to read the rate at
	 * @return bytes per second, `0` when the log is not growing
	 */
	public double effectiveWalBytesPerSecond(long nowMillis) {
		return decayed(this.walByteRatePerSecond, nowMillis);
	}

	/**
	 * Blends one sample into a running average, seeding it on the first measurable interval.
	 *
	 * @param currentRate    the average so far
	 * @param amount         what this transaction contributed
	 * @param intervalMillis how long since the previous sample, always positive
	 * @param seeding        whether this is the first measurable interval, which the average takes verbatim
	 * @return the updated average, per second
	 */
	private static double smooth(double currentRate, double amount, long intervalMillis, boolean seeding) {
		final double sample = amount * 1000.0d / (double) intervalMillis;
		return seeding ? sample : SMOOTHING_FACTOR * sample + (1.0d - SMOOTHING_FACTOR) * currentRate;
	}

	/**
	 * Scales a measured rate down by how far the current idle gap exceeds the cadence it was measured at.
	 *
	 * While commits keep arriving at that cadence this is the measured rate unchanged. Once the gap grows past it, the
	 * rate falls off in proportion - so a catalog nobody writes to converges on zero rather than standing behind a
	 * stale measurement. See the class comment for why the correction cannot be applied on the write path.
	 *
	 * @param rate      the undecayed measurement
	 * @param nowMillis epoch millis to read it at
	 * @return the rate as it stands now
	 */
	private double decayed(double rate, long nowMillis) {
		if (rate <= 0.0d || this.lastSampleIntervalMillis <= 0L) {
			return 0.0d;
		}
		final long idleMillis = nowMillis - this.lastSampleAtMillis;
		if (idleMillis <= this.lastSampleIntervalMillis) {
			return rate;
		}
		return rate * (double) this.lastSampleIntervalMillis / (double) idleMillis;
	}

}
