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

package io.evitadb.store.offsetIndex.model;

import javax.annotation.Nonnull;

/**
 * How fast one data file is accumulating dead bytes - the input a compaction forecast extrapolates from.
 *
 * A record superseded by a rewrite, or removed outright, leaves its bytes behind in the append-only data file until
 * that file is compacted. `wasteBytesGenerated` counts exactly those bytes, **as observed by this index instance**: it
 * starts at zero when the store is opened and again when compaction writes a fresh file, because in neither case has
 * this instance stranded anything yet. It is therefore *waste produced since the store was opened or last compacted*,
 * not the total waste the file holds - that one is measured from the files themselves and reported as `wasteBytes`.
 * The distinction matters only to the counter: the projection is driven by the file's current size and live bytes, so
 * a reopened store forecasts exactly as it did before.
 *
 * The rate is sampled once per flush rather than per write, because a flush is where the bytes actually become part of
 * the file, and it is smoothed so that one large rewrite does not project a compaction that a steady workload would
 * never reach.
 *
 * **The rate decays while nothing is written, and it has to.** An EWMA over flushes has no way to observe an idle
 * period - no flush happens, so no sample is taken, and the last rate would stand forever. A store written hard for a
 * minute and then left alone would keep predicting an imminent compaction for as long as the process lives. Reading
 * the rate through {@link #effectiveRateBytesPerSecond(long)} folds the elapsed idle time in, so the answer falls back
 * towards zero on its own; the stored {@link #rateBytesPerSecond()} is the undecayed measurement and is not what a
 * client should be shown.
 *
 * Instances are immutable and are published through a single volatile write, so a reader always sees a counter and a
 * rate that were computed from the same flush rather than a pair straddling two.
 *
 * @param wasteBytesGenerated      bytes stranded in the current data file by rewrites and removals since this
 *                                 index instance opened it
 * @param rateBytesPerSecond       exponentially-weighted rate of that counter's growth as of the last flush, before
 *                                 any idle-time decay; `0` until two flushes have been observed
 * @param lastSampleAtMillis       epoch millis of the flush that produced `rateBytesPerSecond`; `0` when no flush has
 *                                 been observed yet
 * @param lastSampleIntervalMillis how long the interval between the last two flushes was, which is the cadence the
 *                                 idle-time decay measures staleness against; `0` before a second flush
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record WasteAccumulation(
	long wasteBytesGenerated,
	double rateBytesPerSecond,
	long lastSampleAtMillis,
	long lastSampleIntervalMillis
) {

	/**
	 * A data file that has stranded no bytes yet and whose rate has never been sampled.
	 */
	public static final WasteAccumulation NONE = new WasteAccumulation(0L, 0.0d, 0L, 0L);

	/**
	 * Weight given to the newest sample. Low enough that a single bulk rewrite does not dominate the projection, high
	 * enough that a genuine change in write load shows up within a handful of flushes.
	 */
	private static final double SMOOTHING_FACTOR = 0.3d;

	/**
	 * Folds one flush into the accumulation.
	 *
	 * The first flush only opens the sampling window - there is no preceding sample to measure an interval against, so
	 * it contributes to the counter and leaves the rate alone. The second one seeds the average with its own value
	 * instead of blending it into a zero, which would otherwise halve the very first rate reported.
	 *
	 * @param wasteBytesInThisFlush bytes this flush stranded in the data file
	 * @param sampledAtMillis       epoch millis at which the flush was promoted
	 * @return the accumulation including this flush
	 */
	@Nonnull
	public WasteAccumulation sampled(long wasteBytesInThisFlush, long sampledAtMillis) {
		final long generated = this.wasteBytesGenerated + wasteBytesInThisFlush;
		if (this.lastSampleAtMillis == 0L) {
			return new WasteAccumulation(
				generated, this.rateBytesPerSecond, sampledAtMillis, this.lastSampleIntervalMillis
			);
		}
		final long intervalMillis = sampledAtMillis - this.lastSampleAtMillis;
		if (intervalMillis <= 0L) {
			// two flushes inside the same millisecond - there is no interval to divide by, so the counter moves and
			// the window stays open until a measurable one comes along
			return new WasteAccumulation(
				generated, this.rateBytesPerSecond, this.lastSampleAtMillis, this.lastSampleIntervalMillis
			);
		}
		final double sample = (double) wasteBytesInThisFlush * 1000.0d / (double) intervalMillis;
		final double smoothed = this.lastSampleIntervalMillis == 0L ?
			sample : SMOOTHING_FACTOR * sample + (1.0d - SMOOTHING_FACTOR) * this.rateBytesPerSecond;
		return new WasteAccumulation(generated, smoothed, sampledAtMillis, intervalMillis);
	}

	/**
	 * Returns the rate as it stands at the given moment, with the time elapsed since the last flush folded in.
	 *
	 * While flushes keep arriving at the cadence the rate was measured at, this is the measured rate. Once the gap
	 * since the last flush grows past that cadence, the rate is scaled down by how many times over it has been
	 * exceeded - so a store nobody writes to converges on zero rather than standing behind a stale prediction. See the
	 * class comment for why that correction cannot be applied on the write path.
	 *
	 * @param nowMillis epoch millis to read the rate at
	 * @return bytes per second of waste accumulation, `0` when none is accruing
	 */
	public double effectiveRateBytesPerSecond(long nowMillis) {
		if (this.rateBytesPerSecond <= 0.0d || this.lastSampleIntervalMillis <= 0L) {
			return 0.0d;
		}
		final long idleMillis = nowMillis - this.lastSampleAtMillis;
		if (idleMillis <= this.lastSampleIntervalMillis) {
			return this.rateBytesPerSecond;
		}
		return this.rateBytesPerSecond * (double) this.lastSampleIntervalMillis / (double) idleMillis;
	}

	/**
	 * Returns the accumulation the compacted successor of this data file starts from.
	 *
	 * The counter resets because the new file holds none of the stranded bytes; the rate carries over because the
	 * write load that produced them did not change when the file was rewritten, and dropping it would make every
	 * freshly compacted store report "no compaction foreseeable" until it had flushed twice more.
	 *
	 * @return the accumulation for the file compaction has just written
	 */
	@Nonnull
	public WasteAccumulation carriedOverToCompactedFile() {
		return new WasteAccumulation(
			0L, this.rateBytesPerSecond, this.lastSampleAtMillis, this.lastSampleIntervalMillis
		);
	}

}
