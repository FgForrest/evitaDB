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
 * **Waste and file growth are two different rates, and the file grows at the second one.** A removal strands bytes and
 * appends none; a record replaced by a larger one appends more than it strands. The two coincide only when a record and
 * its replacement are the same size, so a forecast that extrapolated the file's size from the waste rate would fire
 * early on delete-heavy load and late on a workload whose records are growing - in neither direction is it a bound.
 * Both are therefore sampled from the same flush and reported separately: `rateBytesPerSecond` is what the file is
 * *wasting*, `growthRateBytesPerSecond` is what it is *getting bigger by*, and only the latter says when a size
 * threshold is reached.
 *
 * Instances are immutable and are published through a single volatile write, so a reader always sees a counter and a
 * rate that were computed from the same flush rather than a pair straddling two.
 *
 * @param wasteBytesGenerated           bytes stranded in the current data file by rewrites and removals since this
 *                                      index instance opened it
 * @param fileBytesAppended             bytes appended to the current data file over the same period - the record
 *                                      bodies actually written, which is what makes the file longer
 * @param rateBytesPerSecond            exponentially-weighted rate of the waste counter's growth as of the last flush,
 *                                      before any idle-time decay; `0` until two flushes have been observed
 * @param growthRateBytesPerSecond      the same measurement over `fileBytesAppended` - the rate the file itself is
 *                                      lengthening at
 * @param wasteBytesAtLastSample        the waste counter as the currently-open sampling window opened, so the next
 *                                      measurable flush divides *everything* stranded since then rather than only its
 *                                      own bytes
 * @param fileBytesAppendedAtLastSample the append counter at the same moment, for the same reason
 * @param lastSampleAtMillis            epoch millis of the flush that produced the rates; `0` when no flush has been
 *                                      observed yet
 * @param lastSampleIntervalMillis      how long the interval between the last two flushes was, which is the cadence the
 *                                      idle-time decay measures staleness against; `0` before a second flush
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record WasteAccumulation(
	long wasteBytesGenerated,
	long fileBytesAppended,
	double rateBytesPerSecond,
	double growthRateBytesPerSecond,
	long wasteBytesAtLastSample,
	long fileBytesAppendedAtLastSample,
	long lastSampleAtMillis,
	long lastSampleIntervalMillis
) {

	/**
	 * A data file that has stranded no bytes yet and whose rate has never been sampled.
	 */
	public static final WasteAccumulation NONE = new WasteAccumulation(0L, 0L, 0.0d, 0.0d, 0L, 0L, 0L, 0L);

	/**
	 * Weight given to the newest sample. Low enough that a single bulk rewrite does not dominate the projection, high
	 * enough that a genuine change in write load shows up within a handful of flushes.
	 */
	private static final double SMOOTHING_FACTOR = 0.3d;

	/**
	 * Folds one flush into the accumulation.
	 *
	 * The first flush only opens the sampling window - there is no preceding sample to measure an interval against, so
	 * it contributes to the counters and leaves the rates alone. The second one seeds the averages with their own value
	 * instead of blending them into a zero, which would otherwise halve the very first rate reported.
	 *
	 * **Every byte lands in exactly one sample, including bytes flushed inside an already-sampled millisecond.** Each
	 * numerator is the counter's movement across the whole open window - not this one flush's contribution - so a burst
	 * of flushes sharing a millisecond is carried into the next measurable sample rather than dropped from the rate.
	 * Sub-millisecond flush spacing is the ordinary case during bulk load and WAL replay, not an edge case.
	 *
	 * @param wasteBytesInThisFlush    bytes this flush stranded in the data file
	 * @param appendedBytesInThisFlush bytes this flush appended to it - see the class comment for why the two are
	 *                                 counted apart
	 * @param sampledAtMillis          epoch millis at which the flush was promoted
	 * @return the accumulation including this flush
	 */
	@Nonnull
	public WasteAccumulation sampled(long wasteBytesInThisFlush, long appendedBytesInThisFlush, long sampledAtMillis) {
		final long generated = this.wasteBytesGenerated + wasteBytesInThisFlush;
		final long appended = this.fileBytesAppended + appendedBytesInThisFlush;
		if (this.lastSampleAtMillis == 0L) {
			// the window opens here: this flush's bytes were stranded over an interval nothing observed, so they seed
			// the counters but must not become the numerator of the next sample
			return new WasteAccumulation(
				generated, appended, this.rateBytesPerSecond, this.growthRateBytesPerSecond,
				generated, appended, sampledAtMillis, this.lastSampleIntervalMillis
			);
		}
		final long intervalMillis = sampledAtMillis - this.lastSampleAtMillis;
		if (intervalMillis <= 0L) {
			// two flushes inside the same millisecond - there is no interval to divide by, so the counters move and
			// the window stays open until a measurable one comes along. The window's opening counters stay put with
			// it, which is what keeps these bytes in the next sample instead of losing them
			return new WasteAccumulation(
				generated, appended, this.rateBytesPerSecond, this.growthRateBytesPerSecond,
				this.wasteBytesAtLastSample, this.fileBytesAppendedAtLastSample,
				this.lastSampleAtMillis, this.lastSampleIntervalMillis
			);
		}
		final double sample = (double) (generated - this.wasteBytesAtLastSample) * 1000.0d / (double) intervalMillis;
		final double growthSample =
			(double) (appended - this.fileBytesAppendedAtLastSample) * 1000.0d / (double) intervalMillis;
		final boolean seeding = this.lastSampleIntervalMillis == 0L;
		final double smoothed = seeding ?
			sample : SMOOTHING_FACTOR * sample + (1.0d - SMOOTHING_FACTOR) * this.rateBytesPerSecond;
		final double smoothedGrowth = seeding ?
			growthSample : SMOOTHING_FACTOR * growthSample + (1.0d - SMOOTHING_FACTOR) * this.growthRateBytesPerSecond;
		return new WasteAccumulation(
			generated, appended, smoothed, smoothedGrowth, generated, appended, sampledAtMillis, intervalMillis
		);
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
		return decayed(this.rateBytesPerSecond, nowMillis);
	}

	/**
	 * Returns the rate the data file is *lengthening* at, decayed for idle time exactly as
	 * {@link #effectiveRateBytesPerSecond(long)} decays the waste rate.
	 *
	 * This is the one a size threshold must be extrapolated from - see the class comment for why the waste rate is not
	 * a stand-in for it in either direction.
	 *
	 * @param nowMillis epoch millis to read the rate at
	 * @return bytes per second the file is growing by, `0` when it is not growing
	 */
	public double effectiveGrowthRateBytesPerSecond(long nowMillis) {
		return decayed(this.growthRateBytesPerSecond, nowMillis);
	}

	/**
	 * Folds elapsed idle time into a sampled rate.
	 *
	 * While flushes keep arriving at the cadence the rate was measured at, the measurement stands. Once the gap since
	 * the last flush grows past that cadence, the rate is scaled down by how many times over it has been exceeded.
	 *
	 * @param rate      the undecayed rate to correct
	 * @param nowMillis epoch millis to read it at
	 * @return the rate as it stands at that moment
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

	/**
	 * Returns the accumulation the compacted successor of this data file starts from.
	 *
	 * The counters reset because the new file holds none of the stranded bytes and none of the appended ones; the rates
	 * carry over because the write load that produced them did not change when the file was rewritten, and dropping
	 * them would make every freshly compacted store report "no compaction foreseeable" until it had flushed twice more.
	 *
	 * The window-start counters reset alongside the counters they are measured against - they are two ends of one
	 * subtraction, and resetting only one end would make the next sample's numerator negative.
	 *
	 * @return the accumulation for the file compaction has just written
	 */
	@Nonnull
	public WasteAccumulation carriedOverToCompactedFile() {
		return new WasteAccumulation(
			0L, 0L, this.rateBytesPerSecond, this.growthRateBytesPerSecond,
			0L, 0L, this.lastSampleAtMillis, this.lastSampleIntervalMillis
		);
	}

}
