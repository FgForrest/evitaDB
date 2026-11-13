/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.core.query.extraResult.translator.reference.producer.MutableFormula;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Engine-owned result cache. Entries retain detached keys and read-only result tuples, never query state.
 * The retained budget is an eventual target; concurrent misses and queries' own memos are outside that budget.
 * A confirmed sampled mismatch disables this engine's cache for the rest of its lifetime.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public final class FormulaCacheSupervisor implements CacheSupervisor {
	/** Upper bound on comparison work and retained key shape. */
	private static final int MAX_ADMITTED_WEIGHT = 4_096;
	/** Immutable configuration shared by all planning roots. */
	private final CacheOptions options;
	/** Completed, immutable tuples published without a loading function. */
	private final Cache<FormulaKey, CachedResult> cache;
	/** Permanent switch for this engine instance. */
	private final AtomicBoolean active = new AtomicBoolean(true);
	/** Periodic best-effort expiry reclamation, absent when expiry is disabled. */
	@Nullable private final DelayedAsyncTask maintenanceTask;
	/** Successful unique publications. */
	private final LongAdder admissions = new LongAdder();
	/** Completed computations that lost a concurrent publication. */
	private final LongAdder duplicateAdmissions = new LongAdder();
	/** Nanoseconds spent computing losing tuples. */
	private final LongAdder duplicateComputeNanos = new LongAdder();
	/** Charged bytes of losing tuples. */
	private final LongAdder duplicateBytes = new LongAdder();
	/** Entries refused by the byte cap. */
	private final LongAdder refusedOversized = new LongAdder();
	/** Entries refused because their retained shape cannot be sized. */
	private final LongAdder refusedUnsized = new LongAdder();
	/** Roots bypassed because a transaction was bound. */
	private final LongAdder transactionBypasses = new LongAdder();
	/** Bound formula computations and computer analyses bypassed because their context uses prefetch. */
	private final LongAdder prefetchBypasses = new LongAdder();
	/** Completed hit verifications. */
	private final LongAdder verifiedHits = new LongAdder();
	/** Time spent recomputing sampled hits. */
	private final LongAdder verificationNanos = new LongAdder();
	/** Confirmed mismatches, including concurrent detections after the first. */
	private final LongAdder mismatches = new LongAdder();
	/** Bounded detached description of the first mismatch. */
	@Nullable private volatile String witness;

	/** Creates a cache and schedules idle maintenance on the engine's scheduler when expiry is configured. */
	public FormulaCacheSupervisor(@Nonnull CacheOptions options, @Nonnull Scheduler scheduler, @Nonnull Ticker ticker) {
		this.options = options;
		final Caffeine<Object, Object> builder = Caffeine.newBuilder()
			.maximumWeight(options.cacheSizeInBytes()).executor(Runnable::run).ticker(ticker).recordStats();
		if (options.dropEntriesAfterSecondsOfInactivity() > 0) {
			builder.expireAfterAccess(Duration.ofSeconds(options.dropEntriesAfterSecondsOfInactivity()));
		}
		this.cache = builder.weigher((FormulaKey key, CachedResult result) -> result.weight()).build();
		if (options.dropEntriesAfterSecondsOfInactivity() > 0) {
			this.maintenanceTask = new DelayedAsyncTask(null, "Formula result cache maintenance", scheduler, () -> {
				performMaintenance();
				return 0L;
			}, options.dropEntriesAfterSecondsOfInactivity(), TimeUnit.SECONDS);
			this.maintenanceTask.schedule();
		} else {
			this.maintenanceTask = null;
		}
	}

	/** Resolves eligibility independently for a planning root using its session's pinned catalog state. */
	@Nonnull
	public CacheSupervisor resolveForRoot(@Nullable EvitaSessionContract session, @Nullable String entityType) {
		if (!isActive() || session == null || entityType == null || !session.isReadOnly()
			|| session.getCatalogState() != CatalogState.ALIVE) {
			return NoCacheSupervisor.INSTANCE;
		}
		if (Transaction.isTransactionAvailable()) {
			this.transactionBypasses.increment();
			return NoCacheSupervisor.INSTANCE;
		}
		return this;
	}

	/** Returns whether this engine still allows lookup, binding and admission. */
	public boolean isActive() {
		return this.active.get();
	}

	/** Binds distinct canonical instances without replacing nodes or traversing a shared DAG by path. */
	@Nonnull
	@Override
	public <T extends Formula> T analyse(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType, @Nonnull T formula
	) {
		if (!isActive()) return formula;
		final Set<Formula> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		final ArrayDeque<Formula> stack = new ArrayDeque<>();
		stack.push(formula);
		while (!stack.isEmpty()) {
			final Formula current = stack.pop();
			if (!visited.add(current)) continue;
			if (current instanceof MutableFormula) {
				throw new GenericEvitaInternalError("Mutable hypothetical formulas cannot be bound to the result cache.");
			}
			if (current instanceof AbstractCacheableFormula candidate &&
				candidate.getEstimatedCost() >= this.options.minimalComplexityThreshold() && eligible(candidate.getKey())) {
				candidate.bindFormulaCache(this);
			}
			for (final Formula child : current.getInnerFormulas()) stack.push(child);
		}
		return formula;
	}

	/** Wraps only histogram computers, preserving the calling execution context and the computer's memo. */
	@Nonnull
	@Override
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType, @Nonnull T computer,
		@Nonnull QueryExecutionContext context
	) {
		if (context.isPrefetchExecution()) this.prefetchBypasses.increment();
		if (!isActive() || !context.isFormulaCacheUsable() ||
			computer.getEstimatedCost() < this.options.minimalComplexityThreshold()) return computer;
		if (!(computer instanceof AttributeHistogramComputer) && !(computer instanceof PriceHistogramComputer)) {
			throw new GenericEvitaInternalError("Unsupported cacheable extra-result computer.");
		}
		final FormulaKey key = computer.getKey();
		return eligible(key) ? () -> computeHistogram(context, computer, key) : computer;
	}

	/** Rejects query-local keys before lookup or retention. */
	private static boolean eligible(@Nonnull FormulaKey key) {
		return !key.isQueryLocal() && key.getWeight() <= MAX_ADMITTED_WEIGHT;
	}

	/** Looks up an eligible key; Caffeine records both hits and misses. */
	@Nullable
	public CachedResult lookup(@Nonnull FormulaKey key) {
		return this.cache.getIfPresent(key);
	}

	/** Records one bound formula computation bypassed because it executes over prefetched entities. */
	public void recordPrefetchBypass() {
		this.prefetchBypasses.increment();
	}

	/** Selects a hit for verification without shared random state. */
	public boolean sampleHit() {
		return this.options.verifyOneHitIn() > 0 && ThreadLocalRandom.current().nextInt(this.options.verifyOneHitIn()) == 0;
	}

	/** Checks the complete long charge before allocating a retained tuple and records refusals. */
	public int admittedWeight(long charge) {
		final int weight = CachedResultWeigher.admittedWeight(charge, this.options.cacheSizeInBytes());
		if (weight == CachedResultWeigher.UNSIZED) this.refusedUnsized.increment();
		else if (weight == CachedResultWeigher.OVERSIZED) this.refusedOversized.increment();
		return weight;
	}

	/** Publishes without a mapping function, allowing children to admit while their parent is computing. */
	public void admit(@Nonnull FormulaKey key, @Nonnull CachedResult result, long computeNanos) {
		if (!isActive()) return;
		final CachedResult previous = this.cache.asMap().putIfAbsent(key, result);
		if (previous == null) {
			this.admissions.increment();
			if (!isActive()) this.cache.asMap().remove(key, result);
		} else {
			this.duplicateAdmissions.increment();
			this.duplicateComputeNanos.add(computeNanos);
			this.duplicateBytes.add(result.weight());
		}
	}

	/**
	 * Compares the recomputed tuple and fails on a confirmed mismatch without retrying the request.
	 * Plan construction may already have recorded capabilities and index activity. A dry verification can fail
	 * before traffic recording starts; ordinary execution failures pass through the existing traffic recorder.
	 * Previously injected memos in other in-flight queries are not retracted.
	 */
	public void verify(
		@Nonnull QueryExecutionContext context, @Nonnull FormulaKey key, @Nonnull String formulaClass,
		@Nonnull String description,
		@Nonnull CachedResult cached, @Nonnull CachedResult recomputed, long nanos
	) {
		this.verifiedHits.increment();
		this.verificationNanos.add(nanos);
		if (!CachedResultEquivalence.equivalent(cached, recomputed)) {
			this.mismatches.increment();
			if (this.active.compareAndSet(true, false)) {
				final String bounded = description.substring(0, Math.min(description.length(), 2_000));
				this.witness = Instant.now() + ": " + context.getQueryContext().getCatalogSchema().getName() + "/" +
					context.getQueryContext().getEvitaRequest().getEntityType() + ", class=" + formulaClass +
					", classId=" + key.getClassId() +
					", hash64=" + key.getHash64() + ", " + bounded + ", cached=" + summary(cached) +
					", recomputed=" + summary(recomputed) + ", differences=" + differences(cached, recomputed);
				this.cache.invalidateAll();
				this.cache.cleanUp();
				log.error("Formula result cache mismatch: {}", this.witness);
			}
			throw new GenericEvitaInternalError(
				String.valueOf(this.witness),
				"The formula cache returned a result differing from its recomputation and is disabled for this engine. " +
					"The request may be repeated."
			);
		}
	}

	/** Renders a bounded numeric description without retaining a payload or a query object. */
	@Nonnull
	private static String summary(@Nonnull CachedResult result) {
		if (result instanceof CachedBitmapResult bitmap) return "bitmap size " + bitmap.result().size();
		if (result instanceof CachedPriceRecordsResult prices) {
			return "bitmap size " + prices.result().size() + ", prices " + prices.priceRecords().getPriceRecords().length +
				", inner prices " + (prices.perInnerRecordPriceRecords() == null ? 0 :
				prices.perInnerRecordPriceRecords().getPriceRecords().length);
		}
		if (result instanceof CachedHistogramResult histogram) {
			return "histogram count " + histogram.histogram().getOverallCount() +
				", buckets " + histogram.histogram().getBuckets().length;
		}
		throw new GenericEvitaInternalError("Unsupported cached result type.");
	}

	/** Captures at most sixteen differing values or tuples as bounded text without retaining payload references. */
	@Nonnull
	private static String differences(@Nonnull CachedResult cached, @Nonnull CachedResult recomputed) {
		final io.evitadb.index.bitmap.Bitmap left = cached instanceof CachedBitmapResult bitmap ? bitmap.result() :
			cached instanceof CachedPriceRecordsResult prices ? prices.result() : null;
		final io.evitadb.index.bitmap.Bitmap right = recomputed instanceof CachedBitmapResult bitmap ? bitmap.result() :
			recomputed instanceof CachedPriceRecordsResult prices ? prices.result() : null;
		final StringBuilder text = new StringBuilder(512);
		final int[] remaining = {16};
		if (left != null && right != null) {
			final java.util.PrimitiveIterator.OfInt first = left.iterator();
			while (first.hasNext() && remaining[0] > 0) {
				final int id = first.nextInt();
				if (!right.contains(id)) { text.append(" cached-only:").append(id); remaining[0]--; }
			}
			final java.util.PrimitiveIterator.OfInt second = right.iterator();
			while (second.hasNext() && remaining[0] > 0) {
				final int id = second.nextInt();
				if (!left.contains(id)) { text.append(" recomputed-only:").append(id); remaining[0]--; }
			}
		}
		if (cached instanceof CachedPriceRecordsResult first && recomputed instanceof CachedPriceRecordsResult second) {
			appendPriceTuples(text, first.priceRecords(), second.priceRecords(), remaining);
			if (first.perInnerRecordPriceRecords() != null && second.perInnerRecordPriceRecords() != null) {
				appendPriceTuples(text, first.perInnerRecordPriceRecords(), second.perInnerRecordPriceRecords(), remaining);
			}
			appendValueDifference(text, "inner array present", first.perInnerRecordPriceRecords() != null,
				second.perInnerRecordPriceRecords() != null, remaining);
		} else if (cached instanceof CachedHistogramResult first && recomputed instanceof CachedHistogramResult second) {
			appendValueDifference(text, "raw min", first.histogram().getRawMin(),
				second.histogram().getRawMin(), remaining);
			appendValueDifference(text, "raw max", first.histogram().getRawMax(),
				second.histogram().getRawMax(), remaining);
			appendValueDifference(text, "max", first.histogram().getMax(), second.histogram().getMax(), remaining);
			appendValueDifference(text, "count", first.histogram().getOverallCount(),
				second.histogram().getOverallCount(), remaining);
			final CacheableHistogramContract.CacheableBucket[] firstBuckets = first.histogram().getBuckets();
			final CacheableHistogramContract.CacheableBucket[] secondBuckets = second.histogram().getBuckets();
			for (int i = 0; i < Math.max(firstBuckets.length, secondBuckets.length) && remaining[0] > 0; i++) {
				appendValueDifference(text, "bucket " + i, i < firstBuckets.length ? firstBuckets[i] : null,
					i < secondBuckets.length ? secondBuckets[i] : null, remaining);
			}
		}
		return text.substring(0, Math.min(text.length(), 2_000));
	}

	/** Renders up to sixteen physical price tuples, retaining no array or record references in the witness. */
	private static void appendPriceTuples(
		@Nonnull StringBuilder text,
		@Nonnull io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords cached,
		@Nonnull io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords recomputed,
		@Nonnull int[] remaining
	) {
		final io.evitadb.index.price.model.priceRecord.PriceRecordContract[] first = cached.getPriceRecords();
		final io.evitadb.index.price.model.priceRecord.PriceRecordContract[] second = recomputed.getPriceRecords();
		for (int i = 0; i < Math.max(first.length, second.length) && remaining[0] > 0 && text.length() < 2_000; i++) {
			if (i < first.length && i < second.length && CachedResultEquivalence.equivalentRecord(first[i], second[i])) {
				continue;
			}
			if (i < first.length) appendPriceTuple(text.append(" cached:"), first[i], remaining);
			if (i < second.length) appendPriceTuple(text.append(" recomputed:"), second[i], remaining);
		}
	}

	/** Writes a complete physical tuple or a SUM total with bounded component tuples. */
	private static void appendPriceTuple(
		@Nonnull StringBuilder text, @Nonnull io.evitadb.index.price.model.priceRecord.PriceRecordContract record,
		@Nonnull int[] remaining
	) {
		if (remaining[0] == 0) return;
		remaining[0]--;
		text.append(record.getClass().getSimpleName()).append('[').append(record.entityPrimaryKey()).append(',');
		if (record instanceof io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord sum) {
			text.append(sum.price()).append(',').append(sum.priceMode()).append(",components=")
				.append(sum.innerRecordPrices().size());
			for (final com.carrotsearch.hppc.cursors.IntObjectCursor<
				io.evitadb.index.price.model.priceRecord.PriceRecordContract> component : sum.innerRecordPrices()) {
				if (remaining[0] == 0 || text.length() >= 2_000) break;
				text.append(',').append(component.key).append('=');
				appendPriceTuple(text, component.value, remaining);
			}
		} else {
			text.append(record.internalPriceId()).append(',').append(record.priceId()).append(',')
				.append(record.innerRecordId()).append(',').append(record.priceWithTax()).append(',')
				.append(record.priceWithoutTax());
		}
		text.append(']');
	}

	/** Adds only differing immutable values, sharing the witness's global sixteen-value allowance. */
	private static void appendValueDifference(
		@Nonnull StringBuilder text, @Nonnull String label, @Nullable Object cached, @Nullable Object recomputed,
		@Nonnull int[] remaining
	) {
		if (remaining[0] > 0 && !java.util.Objects.equals(cached, recomputed)) {
			text.append(' ').append(label).append(" cached:").append(cached);
			remaining[0]--;
			if (remaining[0] > 0) {
				text.append(" recomputed:").append(recomputed);
				remaining[0]--;
			}
		}
	}

	/** Computes a histogram under the same dynamic suspension and activity rules as a bound formula. */
	@Nonnull
	@SuppressWarnings("unchecked")
	private <U> U computeHistogram(
		@Nonnull QueryExecutionContext context, @Nonnull CacheableEvitaResponseExtraResultComputer<U> computer,
		@Nonnull FormulaKey key
	) {
		if (!context.isFormulaCacheUsable() || !isActive()) {
			return computer.compute();
		}
		final CachedResult cached = lookup(key);
		if (cached != null && !sampleHit()) return (U) ((CachedHistogramResult) cached).histogram();
		final long start = System.nanoTime();
		final boolean previous = cached != null && context.suspendFormulaCache();
		final U computed;
		try {
			computed = computer.compute();
			if (cached != null) verify(context, key, computer.getClass().getName(), computer.toString(), cached,
				new CachedHistogramResult((CacheableHistogramContract) computed, cached.weight()), System.nanoTime() - start);
		} finally {
			if (cached != null) context.resumeFormulaCache(previous);
		}
		if (cached == null) {
			final long computeNanos = System.nanoTime() - start;
			final int weight = admittedWeight(CachedResultWeigher.measure(key, (CacheableHistogramContract) computed));
			if (weight > 0) admit(key, new CachedHistogramResult((CacheableHistogramContract) computed, weight),
				computeNanos);
		}
		return computed;
	}

	/** Runs best-effort maintenance, including reclamation when no query accesses the cache. */
	public void performMaintenance() {
		this.cache.cleanUp();
	}

	/** Returns weakly consistent counters and gauges used by engine observability. */
	@Nonnull
	public Statistics getStatistics() {
		final CacheStats stats = this.cache.stats();
		return new Statistics(stats.hitCount(), stats.missCount(), this.admissions.sum(),
			this.duplicateAdmissions.sum(), this.duplicateComputeNanos.sum() / 1_000_000L, this.duplicateBytes.sum(),
			this.refusedOversized.sum(), this.refusedUnsized.sum(), this.prefetchBypasses.sum(),
			this.transactionBypasses.sum(), stats.evictionCount(), stats.evictionWeight(), this.verifiedHits.sum(),
			this.verificationNanos.sum() / 1_000_000L, this.mismatches.sum(), this.cache.estimatedSize(),
			this.cache.policy().eviction().orElseThrow().weightedSize().orElse(0), this.options.cacheSizeInBytes(),
			isActive(), this.witness);
	}

	/** Stops future use and timer scheduling and discards retained entries. */
	@Override
	public void close() {
		this.active.set(false);
		if (this.maintenanceTask != null) IOUtils.closeQuietly(this.maintenanceTask::close);
		this.cache.invalidateAll();
		this.cache.cleanUp();
	}

	/**
	 * Detached, weakly consistent counters and gauges. Bypasses count roots for transactions and computer analyses
	 * or bound formula computations for prefetch. Durations are cumulative milliseconds; the optional witness
	 * contains only bounded text.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Statistics(
		long hits, long misses, long admissions, long duplicateAdmissions, long duplicateComputeMillis,
		long duplicateBytes, long refusedOversized, long refusedUnsized, long prefetchBypasses,
		long transactionBypasses, long evictions, long evictedWeightBytes, long verifiedHits, long verificationMillis,
		long mismatches, long entries, long weightedSizeBytes, long maximumWeightBytes, boolean active,
		@Nullable String witness
	) {
	}
}
