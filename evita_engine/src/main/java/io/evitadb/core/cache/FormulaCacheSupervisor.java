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

import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.cache.FormulaCacheMismatchEvent;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.core.query.extraResult.translator.reference.producer.MutableFormula;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
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
 * An engine with the cache enabled and a positive budget owns exactly one instance, shared by the queries of all its
 * threads; every planning root obtains its effective supervisor through
 * {@link #resolveForRoot(EvitaSessionContract, String)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@ThreadSafe
public final class FormulaCacheSupervisor implements CacheSupervisor {
	/** Upper bound on comparison work and retained key shape, in key weight units. */
	private static final int MAX_ADMITTED_KEY_WEIGHT_UNITS = 4_096;
	/** Upper bound on the length of a mismatch description and of each of its parts. */
	private static final int MAX_WITNESS_LENGTH_CHARS = 2_000;
	/** Upper bound on the differing values or tuples a mismatch description lists. */
	private static final int MAX_REPORTED_DIFFERENCES = 16;
	/** Immutable configuration shared by all planning roots. */
	private final CacheOptions options;
	/** Retained size budget unboxed once for admission checks and statistics. */
	private final long budget;
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

	/**
	 * Creates a cache and schedules idle maintenance on the engine's scheduler when expiry is configured.
	 *
	 * @param options retained cache configuration, including the byte budget, expiry and verification sampling
	 * @param scheduler engine scheduler used for periodic cleanup when idle expiry is enabled
	 * @param ticker time source used by the cache to measure entry inactivity
	 */
	public FormulaCacheSupervisor(
		@Nonnull CacheOptions options, @Nonnull Scheduler scheduler, @Nonnull Ticker ticker
	) {
		this.options = options;
		this.budget = options.cacheSizeInBytes();
		final Caffeine<Object, Object> builder = Caffeine.newBuilder()
			.maximumWeight(this.budget).executor(Runnable::run).ticker(ticker).recordStats();
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

	/**
	 * Resolves eligibility independently for a planning root using its session's pinned catalog state. A root refused
	 * only because a transaction is bound to the thread is counted as a transaction bypass.
	 *
	 * @param session session supplying read-only status and pinned catalog state; null disables caching for this root
	 * @param entityType entity type of the planning root; null disables caching for this root
	 * @return this shared supervisor for an eligible root, otherwise {@link NoCacheSupervisor#INSTANCE}
	 */
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

	/**
	 * Returns whether this engine still allows lookup, binding and admission.
	 *
	 * @return true while this supervisor has neither been closed nor disabled by a confirmed mismatch
	 */
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Binds distinct canonical instances without replacing nodes or traversing a shared DAG by path.
	 *
	 * @throws GenericEvitaInternalError when the graph contains a `MutableFormula`: hypothetical facet graphs are
	 *         refused rather than skipped, and nodes the walk visited before it may already be bound
	 */
	@Nonnull
	@Override
	public <T extends Formula> T analyse(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType, @Nonnull T formula
	) {
		if (!isActive()) {
			return formula;
		}
		final Set<Formula> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		final ArrayDeque<Formula> stack = new ArrayDeque<>();
		stack.push(formula);
		while (!stack.isEmpty()) {
			final Formula current = stack.pop();
			if (!visited.add(current)) {
				continue;
			}
			if (current instanceof MutableFormula) {
				throw new GenericEvitaInternalError(
					"Mutable hypothetical formulas cannot be bound to the result cache."
				);
			}
			if (current instanceof AbstractCacheableFormula candidate &&
				candidate.getEstimatedCost() >= this.options.minimalComplexityThreshold() &&
				eligible(candidate.getKey())) {
				candidate.bindFormulaCache(this);
			}
			for (final Formula child : current.getInnerFormulas()) {
				stack.push(child);
			}
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
		if (context.isPrefetchExecution()) {
			this.prefetchBypasses.increment();
		}
		if (!isActive() || !context.isFormulaCacheUsable() ||
			computer.getEstimatedCost() < this.options.minimalComplexityThreshold()) {
			return computer;
		}
		if (!(computer instanceof AttributeHistogramComputer) && !(computer instanceof PriceHistogramComputer)) {
			throw new GenericEvitaInternalError("Unsupported cacheable extra-result computer.");
		}
		final FormulaKey key = computer.getKey();
		return eligible(key) ? () -> computeHistogram(context, computer, key) : computer;
	}

	/**
	 * Rejects query-local keys before lookup or retention.
	 *
	 * @param key detached candidate identity checked for cross-query reuse and bounded comparison work
	 * @return true when the key is not query-local and its retained weight fits the admission limit
	 */
	private static boolean eligible(@Nonnull FormulaKey key) {
		return !key.isQueryLocal() && key.getWeight() <= MAX_ADMITTED_KEY_WEIGHT_UNITS;
	}

	/**
	 * Looks up an eligible key; Caffeine records both hits and misses.
	 *
	 * @param key detached identity under which the caller expects a retained tuple
	 * @return the shared read-only tuple, or null when no entry is present; no computation is triggered
	 */
	@Nullable
	public CachedResult lookup(@Nonnull FormulaKey key) {
		return this.cache.getIfPresent(key);
	}

	/** Records one bound formula computation bypassed because it executes over prefetched entities. */
	public void recordPrefetchBypass() {
		this.prefetchBypasses.increment();
	}

	/**
	 * Selects a hit for verification without shared random state.
	 *
	 * @return true when verification is enabled and this hit is selected with probability one in the configured rate
	 */
	public boolean sampleHit() {
		return this.options.verifyOneHitIn() > 0 &&
			ThreadLocalRandom.current().nextInt(this.options.verifyOneHitIn()) == 0;
	}

	/**
	 * Checks the complete long charge before allocating a retained tuple and records refusals.
	 *
	 * @param charge complete entry charge in bytes, or {@link CachedResultWeigher#UNSIZED} for unsupported storage
	 * @return the admitted positive byte weight, {@link CachedResultWeigher#UNSIZED} for unsupported storage,
	 *         or {@link CachedResultWeigher#OVERSIZED} for a charge below the bookkeeping minimum, above one percent
	 *         of the byte budget or above the integer range
	 */
	public int admittedWeight(long charge) {
		final int weight = CachedResultWeigher.admittedWeight(charge, this.budget);
		if (weight == CachedResultWeigher.UNSIZED) {
			this.refusedUnsized.increment();
		} else if (weight == CachedResultWeigher.OVERSIZED) {
			this.refusedOversized.increment();
		}
		return weight;
	}

	/**
	 * Publishes without a mapping function, allowing children to admit while their parent is computing.
	 *
	 * @param key eligible detached identity retained if this publication wins
	 * @param result fully charged tuple to retain; its referenced contents must remain read-only after publication
	 * @param computeNanos elapsed computation time in nanoseconds, counted as duplicate work if another tuple wins
	 */
	public void admit(@Nonnull FormulaKey key, @Nonnull CachedResult result, long computeNanos) {
		if (!isActive()) {
			return;
		}
		final CachedResult previous = this.cache.asMap().putIfAbsent(key, result);
		if (previous == null) {
			this.admissions.increment();
			if (!isActive()) {
				this.cache.asMap().remove(key, result);
			}
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
	 * Previously injected memos in other in-flight queries are not retracted. Every confirmed mismatch commits a
	 * {@link FormulaCacheMismatchEvent} with the catalog name, entity type and class before the exception is thrown,
	 * also when an earlier mismatch has already switched the cache off.
	 *
	 * @param context execution context supplying catalog and entity identifiers for mismatch reporting
	 * @param key detached identity whose class identifier and hash are included in the diagnostic witness
	 * @param formulaClass fully qualified class name of the formula or histogram computer being verified
	 * @param description computation description, truncated when incorporated into the witness
	 * @param cached shared tuple obtained from the cache, compared without modification
	 * @param recomputed independently computed tuple to compare against the cached contents
	 * @param nanos elapsed verification computation time in nanoseconds, added to the cumulative counter
	 * @throws GenericEvitaInternalError on every confirmed mismatch, describing that mismatch; the first one also
	 *         switches this engine's cache off and becomes the stored witness
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
			final String catalogName = context.getQueryContext().getCatalogSchema().getName();
			final String entityType = context.getQueryContext().getEvitaRequest().getEntityType();
			new FormulaCacheMismatchEvent(catalogName, entityType, formulaClass).commit();
			final String detected = describeMismatch(
				catalogName, entityType, key, formulaClass, description, cached, recomputed
			);
			if (this.active.compareAndSet(true, false)) {
				this.witness = detected;
				this.cache.invalidateAll();
				this.cache.cleanUp();
				log.error("Formula result cache mismatch: {}", this.witness);
			}
			throw new GenericEvitaInternalError(
				detected,
				"The formula cache returned a result differing from its recomputation " +
					"and is disabled for this engine. The request may be repeated."
			);
		}
	}

	/**
	 * Renders the bounded description of one detected mismatch, retaining no payload or query object. A `null`
	 * entity type is rendered as {@code <unknown>}.
	 *
	 * @param catalogName catalog schema name included in the witness
	 * @param entityType entity type included in the witness, or null to render an unknown type
	 * @param key detached identity supplying the class identifier and structural hash
	 * @param formulaClass fully qualified name of the computation whose results differ
	 * @param description computation description, limited to the configured character allowance
	 * @param cached retained tuple summarized as the cached side of the mismatch
	 * @param recomputed independent tuple summarized as the recomputed side of the mismatch
	 * @return a detached timestamped description with bounded computation text, summaries and difference text
	 */
	@Nonnull
	private static String describeMismatch(
		@Nonnull String catalogName, @Nullable String entityType, @Nonnull FormulaKey key,
		@Nonnull String formulaClass, @Nonnull String description, @Nonnull CachedResult cached,
		@Nonnull CachedResult recomputed
	) {
		final String bounded = description.substring(0, Math.min(description.length(), MAX_WITNESS_LENGTH_CHARS));
		return Instant.now() + ": " + catalogName + "/" + (entityType == null ? "<unknown>" : entityType) +
			", class=" + formulaClass + ", classId=" + key.getClassId() +
			", hash64=" + key.getHash64() + ", " + bounded + ", cached=" + summary(cached) +
			", recomputed=" + summary(recomputed) + ", differences=" + differences(cached, recomputed);
	}

	/**
	 * Renders a bounded numeric description without retaining a payload or a query object.
	 *
	 * @param result tuple whose bitmap, price or histogram cardinalities are read without modification
	 * @return a detached numeric summary of the tuple contents
	 */
	@Nonnull
	private static String summary(@Nonnull CachedResult result) {
		if (result instanceof CachedBitmapResult bitmap) {
			return "bitmap size " + bitmap.result().size();
		}
		if (result instanceof CachedPriceRecordsResult prices) {
			return "bitmap size " + prices.result().size() +
				", prices " + prices.priceRecords().getPriceRecords().length +
				", inner prices " + (prices.perInnerRecordPriceRecords() == null ? 0 :
				prices.perInnerRecordPriceRecords().getPriceRecords().length);
		}
		if (result instanceof CachedHistogramResult histogram) {
			return "histogram count " + histogram.histogram().getOverallCount() +
				", buckets " + histogram.histogram().getBuckets().length;
		}
		throw new GenericEvitaInternalError("Unsupported cached result type.");
	}

	/**
	 * Captures at most sixteen differing values or tuples as bounded text without retaining payload references.
	 *
	 * @param cached retained tuple supplying the first side of each comparison
	 * @param recomputed independently computed tuple supplying the second side of each comparison
	 * @return detached difference text limited to the character allowance, or an empty string when none are listed
	 */
	@Nonnull
	private static String differences(@Nonnull CachedResult cached, @Nonnull CachedResult recomputed) {
		final Bitmap left = bitmapOf(cached);
		final Bitmap right = bitmapOf(recomputed);
		final StringBuilder text = new StringBuilder(512);
		final int[] remaining = {MAX_REPORTED_DIFFERENCES};
		if (left != null && right != null) {
			appendBitmapDifferences(text, left, right, remaining);
		}
		if (cached instanceof CachedPriceRecordsResult first && recomputed instanceof CachedPriceRecordsResult second) {
			appendPriceDifferences(text, first, second, remaining);
		} else if (cached instanceof CachedHistogramResult first &&
			recomputed instanceof CachedHistogramResult second) {
			appendHistogramDifferences(text, first.histogram(), second.histogram(), remaining);
		}
		return text.substring(0, Math.min(text.length(), MAX_WITNESS_LENGTH_CHARS));
	}

	/**
	 * Returns the bitmap of a bitmap or price tuple; a histogram tuple has none.
	 *
	 * @param result tuple whose bitmap is requested without computing or copying it
	 * @return the shared bitmap for bitmap and price tuples, or null for a histogram tuple
	 */
	@Nullable
	private static Bitmap bitmapOf(@Nonnull CachedResult result) {
		return switch (result) {
			case CachedBitmapResult bitmap -> bitmap.result();
			case CachedPriceRecordsResult prices -> prices.result();
			case CachedHistogramResult histogram -> null;
		};
	}

	/**
	 * Lists identifiers present on one side only, the cached side first, within the shared allowance.
	 *
	 * @param text mutable witness buffer to append differences to
	 * @param cached cached bitmap whose identifiers are compared without mutation
	 * @param recomputed recomputed bitmap whose identifiers are compared without mutation
	 * @param remaining shared single-element array containing the nonnegative remaining value allowance;
	 *                  decremented for each value or tuple written
	 */
	private static void appendBitmapDifferences(
		@Nonnull StringBuilder text, @Nonnull Bitmap cached, @Nonnull Bitmap recomputed, @Nonnull int[] remaining
	) {
		final OfInt first = cached.iterator();
		while (first.hasNext() && remaining[0] > 0) {
			final int id = first.nextInt();
			if (!recomputed.contains(id)) {
				text.append(" cached-only:").append(id);
				remaining[0]--;
			}
		}
		final OfInt second = recomputed.iterator();
		while (second.hasNext() && remaining[0] > 0) {
			final int id = second.nextInt();
			if (!cached.contains(id)) {
				text.append(" recomputed-only:").append(id);
				remaining[0]--;
			}
		}
	}

	/**
	 * Lists differing selling and per-inner-record price tuples and whether only one side has the latter.
	 *
	 * @param text mutable witness buffer to append differences to
	 * @param cached cached price tuple supplying selling and optional per-inner-record prices
	 * @param recomputed recomputed price tuple supplying the corresponding price arrays
	 * @param remaining shared single-element array containing the nonnegative remaining value allowance;
	 *                  decremented for each value or tuple written
	 */
	private static void appendPriceDifferences(
		@Nonnull StringBuilder text, @Nonnull CachedPriceRecordsResult cached,
		@Nonnull CachedPriceRecordsResult recomputed, @Nonnull int[] remaining
	) {
		appendPriceTuples(text, cached.priceRecords(), recomputed.priceRecords(), remaining);
		final ResolvedFilteredPriceRecords cachedInner = cached.perInnerRecordPriceRecords();
		final ResolvedFilteredPriceRecords recomputedInner = recomputed.perInnerRecordPriceRecords();
		if (cachedInner != null && recomputedInner != null) {
			appendPriceTuples(text, cachedInner, recomputedInner, remaining);
		}
		appendValueDifference(text, "inner array present", cachedInner != null, recomputedInner != null, remaining);
	}

	/**
	 * Lists differing histogram bounds, counts and buckets.
	 *
	 * @param text mutable witness buffer to append differences to
	 * @param cached cached histogram supplying bounds, counts and ordered buckets
	 * @param recomputed recomputed histogram supplying corresponding bounds, counts and buckets
	 * @param remaining shared single-element array containing the nonnegative remaining value allowance;
	 *                  decremented for each value or tuple written
	 */
	private static void appendHistogramDifferences(
		@Nonnull StringBuilder text, @Nonnull CacheableHistogramContract cached,
		@Nonnull CacheableHistogramContract recomputed, @Nonnull int[] remaining
	) {
		appendValueDifference(text, "raw min", cached.getRawMin(), recomputed.getRawMin(), remaining);
		appendValueDifference(text, "raw max", cached.getRawMax(), recomputed.getRawMax(), remaining);
		appendValueDifference(text, "max", cached.getMax(), recomputed.getMax(), remaining);
		appendValueDifference(text, "count", cached.getOverallCount(), recomputed.getOverallCount(), remaining);
		final CacheableHistogramContract.CacheableBucket[] firstBuckets = cached.getBuckets();
		final CacheableHistogramContract.CacheableBucket[] secondBuckets = recomputed.getBuckets();
		for (int i = 0; i < Math.max(firstBuckets.length, secondBuckets.length) && remaining[0] > 0; i++) {
			appendValueDifference(text, "bucket " + i, i < firstBuckets.length ? firstBuckets[i] : null,
				i < secondBuckets.length ? secondBuckets[i] : null, remaining);
		}
	}

	/**
	 * Renders up to sixteen physical price tuples, retaining no array or record references in the witness.
	 *
	 * @param text mutable witness buffer to append differences to
	 * @param cached cached ordered price records, read without copying or mutation
	 * @param recomputed recomputed ordered price records compared at the same array positions
	 * @param remaining shared single-element array containing the nonnegative remaining value allowance;
	 *                  decremented for each value or tuple written
	 */
	private static void appendPriceTuples(
		@Nonnull StringBuilder text,
		@Nonnull ResolvedFilteredPriceRecords cached, @Nonnull ResolvedFilteredPriceRecords recomputed,
		@Nonnull int[] remaining
	) {
		final PriceRecordContract[] first = cached.getPriceRecords();
		final PriceRecordContract[] second = recomputed.getPriceRecords();
		for (
			int i = 0;
			i < Math.max(first.length, second.length) && remaining[0] > 0 && text.length() < MAX_WITNESS_LENGTH_CHARS;
			i++
		) {
			if (i < first.length && i < second.length &&
				CachedResultEquivalence.equivalentRecord(first[i], second[i])) {
				continue;
			}
			if (i < first.length) {
				appendPriceTuple(text.append(" cached:"), first[i], remaining);
			}
			if (i < second.length) {
				appendPriceTuple(text.append(" recomputed:"), second[i], remaining);
			}
		}
	}

	/**
	 * Writes a complete physical tuple or a SUM total with bounded component tuples.
	 *
	 * @param text mutable witness buffer to append the physical tuple or SUM components to
	 * @param record price record read without mutation; SUM components share the same output allowance
	 * @param remaining shared single-element array containing the nonnegative remaining tuple allowance;
	 *                  decremented for this record and any rendered components
	 */
	private static void appendPriceTuple(
		@Nonnull StringBuilder text, @Nonnull PriceRecordContract record, @Nonnull int[] remaining
	) {
		if (remaining[0] == 0) {
			return;
		}
		remaining[0]--;
		text.append(record.getClass().getSimpleName()).append('[').append(record.entityPrimaryKey()).append(',');
		if (record instanceof CumulatedVirtualPriceRecord sum) {
			text.append(sum.price()).append(',').append(sum.priceMode()).append(",components=")
				.append(sum.innerRecordPrices().size());
			for (final IntObjectCursor<PriceRecordContract> component : sum.innerRecordPrices()) {
				if (remaining[0] == 0 || text.length() >= MAX_WITNESS_LENGTH_CHARS) {
					break;
				}
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

	/**
	 * Adds only differing immutable values, sharing the witness's global sixteen-value allowance.
	 *
	 * @param text mutable witness buffer to append labeled differences to
	 * @param label description of the compared property, included only if the values differ
	 * @param cached immutable cached value, or null when the value is absent
	 * @param recomputed immutable recomputed value, or null when the value is absent
	 * @param remaining shared single-element array containing the nonnegative remaining value allowance;
	 *                  decremented separately for each side rendered
	 */
	private static void appendValueDifference(
		@Nonnull StringBuilder text, @Nonnull String label, @Nullable Object cached, @Nullable Object recomputed,
		@Nonnull int[] remaining
	) {
		if (remaining[0] > 0 && !Objects.equals(cached, recomputed)) {
			text.append(' ').append(label).append(" cached:").append(cached);
			remaining[0]--;
			if (remaining[0] > 0) {
				text.append(" recomputed:").append(recomputed);
				remaining[0]--;
			}
		}
	}

	/**
	 * Computes a histogram under the same dynamic suspension and activity rules as a bound formula.
	 *
	 * @param <U> histogram result type produced by the wrapped computer
	 * @param context execution context whose cache eligibility and suspension state govern this computation
	 * @param computer histogram computer invoked on misses, bypasses and sampled verification hits
	 * @param key eligible detached identity used for lookup and possible admission
	 * @return the shared cached histogram on an unsampled hit, otherwise the computer's result, which may be retained
	 */
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
		if (cached != null && !sampleHit()) {
			return (U) ((CachedHistogramResult) cached).histogram();
		}
		final long start = System.nanoTime();
		final boolean previous = cached != null && context.suspendFormulaCache();
		final U computed;
		try {
			computed = computer.compute();
			if (cached != null) {
				verify(context, key, computer.getClass().getName(), computer.toString(), cached,
					new CachedHistogramResult((CacheableHistogramContract) computed, cached.weight()),
					System.nanoTime() - start);
			}
		} finally {
			if (cached != null) {
				context.resumeFormulaCache(previous);
			}
		}
		if (cached == null) {
			final long computeNanos = System.nanoTime() - start;
			final int weight = admittedWeight(CachedResultWeigher.measure(key, (CacheableHistogramContract) computed));
			if (weight > 0) {
				admit(key, new CachedHistogramResult((CacheableHistogramContract) computed, weight), computeNanos);
			}
		}
		return computed;
	}

	/** Runs best-effort maintenance, including reclamation when no query accesses the cache. */
	public void performMaintenance() {
		this.cache.cleanUp();
	}

	/**
	 * Returns weakly consistent counters and gauges used by engine observability.
	 *
	 * @return a fresh detached snapshot of counters and gauges, with a null witness if none has been recorded
	 */
	@Nonnull
	public Statistics getStatistics() {
		final CacheStats stats = this.cache.stats();
		return new Statistics(stats.hitCount(), stats.missCount(), this.admissions.sum(),
			this.duplicateAdmissions.sum(), this.duplicateComputeNanos.sum() / 1_000_000L, this.duplicateBytes.sum(),
			this.refusedOversized.sum(), this.refusedUnsized.sum(), this.prefetchBypasses.sum(),
			this.transactionBypasses.sum(), stats.evictionCount(), stats.evictionWeight(), this.verifiedHits.sum(),
			this.verificationNanos.sum() / 1_000_000L, this.mismatches.sum(), this.cache.estimatedSize(),
			this.cache.policy().eviction().orElseThrow().weightedSize().orElse(0), this.budget,
			isActive(), this.witness);
	}

	/** Stops future use and timer scheduling and discards retained entries. */
	@Override
	public void close() {
		this.active.set(false);
		if (this.maintenanceTask != null) {
			IOUtils.closeQuietly(this.maintenanceTask::close);
		}
		this.cache.invalidateAll();
		this.cache.cleanUp();
	}

	/**
	 * Detached, weakly consistent counters and gauges. Bypasses count roots for transactions and computer analyses
	 * or bound formula computations for prefetch. Durations are cumulative milliseconds; the optional witness
	 * contains only bounded text.
	 *
	 * @param hits successful cache lookups
	 * @param misses cache lookups with no retained tuple
	 * @param admissions successful publications that found no existing entry
	 * @param duplicateAdmissions publications that lost to an existing entry
	 * @param duplicateComputeMillis cumulative computation time of losing publications in milliseconds
	 * @param duplicateBytes cumulative byte charges of losing publications
	 * @param refusedOversized entry charges refused by the per-entry byte or integer limits
	 * @param refusedUnsized entry charges refused because retained storage has no supported size model
	 * @param prefetchBypasses computer analyses or bound formula computations bypassed for prefetch execution
	 * @param transactionBypasses otherwise eligible planning roots bypassed because a transaction was bound
	 * @param evictions entries evicted by the cache, excluding explicit invalidations
	 * @param evictedWeightBytes cumulative byte weight of evicted entries
	 * @param verifiedHits completed sampled hit comparisons, including mismatches
	 * @param verificationMillis cumulative sampled recomputation time in milliseconds
	 * @param mismatches confirmed differing comparisons, including concurrent detections after cache disablement
	 * @param entries estimated number of retained entries at sampling time
	 * @param weightedSizeBytes current retained byte weight reported by the cache policy
	 * @param maximumWeightBytes configured retained byte budget, an eventual target
	 * @param active whether lookup, binding and admission are still enabled for this supervisor
	 * @param witness bounded detached text of the first confirmed mismatch, or null if none has been recorded
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Immutable
	public record Statistics(
		long hits, long misses, long admissions, long duplicateAdmissions, long duplicateComputeMillis,
		long duplicateBytes, long refusedOversized, long refusedUnsized, long prefetchBypasses,
		long transactionBypasses, long evictions, long evictedWeightBytes, long verifiedHits, long verificationMillis,
		long mismatches, long entries, long weightedSizeBytes, long maximumWeightBytes, boolean active,
		@Nullable String witness
	) {
	}
}
