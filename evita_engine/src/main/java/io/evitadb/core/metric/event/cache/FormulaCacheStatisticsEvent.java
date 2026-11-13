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

package io.evitadb.core.metric.event.cache;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.core.cache.FormulaCacheSupervisor.Statistics;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Periodic formula result cache statistics. Counters carry nonnegative differences from the previous emission;
 * gauges carry the current value. The first emission includes all work since cache creation. The emitter owns
 * the previous statistics, so collecting metrics adds no bookkeeping to query execution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Name(AbstractFormulaCacheEvent.PACKAGE_NAME + ".FormulaCacheStatistics")
@Label("Formula cache statistics")
@Description("Formula result cache counter deltas and current gauges.")
@Period("1m")
@Getter
public class FormulaCacheStatisticsEvent extends AbstractFormulaCacheEvent {

	/** Cache hits since the previous emission. */
	@Label("Cache hits")
	@Description("Cache hits since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long hits;

	/** Cache misses since the previous emission. */
	@Label("Cache misses")
	@Description("Cache misses since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long misses;

	/** Unique admissions since the previous emission. */
	@Label("Unique admissions")
	@Description("Unique admissions since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long admissions;

	/** Duplicate admissions since the previous emission. */
	@Label("Duplicate admissions")
	@Description("Duplicate admissions since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long duplicateAdmissions;

	/** Duplicate computation milliseconds since the previous emission. */
	@Label("Duplicate computation milliseconds")
	@Description("Duplicate computation milliseconds since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long duplicateComputeMillis;

	/** Duplicate computation bytes since the previous emission. */
	@Label("Duplicate computation bytes")
	@Description("Duplicate computation bytes since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long duplicateBytes;

	/** Oversized entries refused since the previous emission. */
	@Label("Oversized entries refused")
	@Description("Oversized entries refused since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long refusedOversized;

	/** Unsized entries refused since the previous emission. */
	@Label("Unsized entries refused")
	@Description("Unsized entries refused since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long refusedUnsized;

	/** Prefetch bypasses since the previous emission. */
	@Label("Prefetch bypasses")
	@Description("Prefetch bypasses since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long prefetchBypasses;

	/** Transaction bypasses since the previous emission. */
	@Label("Transaction bypasses")
	@Description("Transaction bypasses since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long transactionBypasses;

	/** Cache evictions since the previous emission. */
	@Label("Cache evictions")
	@Description("Cache evictions since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long evictions;

	/** Evicted weight bytes since the previous emission. */
	@Label("Evicted weight bytes")
	@Description("Evicted weight bytes since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long evictedWeightBytes;

	/** Verified hits since the previous emission. */
	@Label("Verified hits")
	@Description("Verified hits since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long verifiedHits;

	/** Verification milliseconds since the previous emission. */
	@Label("Verification milliseconds")
	@Description("Verification milliseconds since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long verificationMillis;

	/** Confirmed mismatches since the previous emission. */
	@Label("Confirmed mismatches")
	@Description("Confirmed mismatches since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long mismatches;

	/** Retained entries at this observation. */
	@Label("Retained entries")
	@Description("Retained entries at this observation.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long entries;

	/** Retained weight bytes at this observation. */
	@Label("Retained weight bytes")
	@Description("Retained weight bytes at this observation.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long weightedSizeBytes;

	/** Maximum weight bytes at this observation. */
	@Label("Maximum weight bytes")
	@Description("Maximum weight bytes at this observation.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long maximumWeightBytes;

	/** Cache active: 1 when usable, 0 after shutdown or a confirmed mismatch. */
	@Label("Cache active")
	@Description("Cache active: 1 when usable, 0 after shutdown or a confirmed mismatch.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int active;

	/**
	 * Extracts every exported value from the supervisor's detached statistics.
	 *
	 * @param current current cumulative counters and gauges
	 * @param previous statistics used by the previous emission, or null for the first emission
	 */
	public FormulaCacheStatisticsEvent(@Nonnull Statistics current, @Nullable Statistics previous) {
		this.hits = delta(current.hits(), previous == null ? 0 : previous.hits());
		this.misses = delta(current.misses(), previous == null ? 0 : previous.misses());
		this.admissions = delta(current.admissions(), previous == null ? 0 : previous.admissions());
		this.duplicateAdmissions =
			delta(current.duplicateAdmissions(), previous == null ? 0 : previous.duplicateAdmissions());
		this.duplicateComputeMillis =
			delta(current.duplicateComputeMillis(), previous == null ? 0 : previous.duplicateComputeMillis());
		this.duplicateBytes = delta(current.duplicateBytes(), previous == null ? 0 : previous.duplicateBytes());
		this.refusedOversized = delta(current.refusedOversized(), previous == null ? 0 : previous.refusedOversized());
		this.refusedUnsized = delta(current.refusedUnsized(), previous == null ? 0 : previous.refusedUnsized());
		this.prefetchBypasses = delta(current.prefetchBypasses(), previous == null ? 0 : previous.prefetchBypasses());
		this.transactionBypasses =
			delta(current.transactionBypasses(), previous == null ? 0 : previous.transactionBypasses());
		this.evictions = delta(current.evictions(), previous == null ? 0 : previous.evictions());
		this.evictedWeightBytes = delta(current.evictedWeightBytes(), previous == null ? 0 : previous.evictedWeightBytes());
		this.verifiedHits = delta(current.verifiedHits(), previous == null ? 0 : previous.verifiedHits());
		this.verificationMillis = delta(current.verificationMillis(), previous == null ? 0 : previous.verificationMillis());
		this.mismatches = delta(current.mismatches(), previous == null ? 0 : previous.mismatches());
		this.entries = current.entries();
		this.weightedSizeBytes = current.weightedSizeBytes();
		this.maximumWeightBytes = current.maximumWeightBytes();
		this.active = current.active() ? 1 : 0;
	}

	/**
	 * Clamps a regressing source to zero. Current supervisor counters never reset during the engine lifetime;
	 * the clamp also protects export if a future source resets or a weakly consistent observation regresses.
	 */
	private static long delta(long current, long previous) {
		return Math.max(0, current - previous);
	}
}
