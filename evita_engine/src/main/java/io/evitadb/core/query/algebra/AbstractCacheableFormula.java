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

package io.evitadb.core.query.algebra;

import io.evitadb.core.cache.*;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Formula whose computed tuple may be retained across queries. Binding preserves the node and all its children.
 * Clones start unbound, and {@link #clearMemory()} drops the binding together with the result: the memoized key may
 * describe a `MutableFormula` delegate that has since been replaced below this node.
 *
 * A formula that sets side outputs while computing overrides {@link #measurePayload(Bitmap)},
 * {@link #toCachedResult(Bitmap, int)} and {@link #restoreFromCache(CachedResult)} together, so that a hit restores
 * the complete tuple. A formula whose side outputs are not a supported payload extends {@link AbstractFormula} instead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class AbstractCacheableFormula extends AbstractFormula {
	/** Supervisor selected after deduplication; never copied by clone paths. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private FormulaCacheSupervisor boundCache;

	/** Creates an unbound formula. */
	protected AbstractCacheableFormula() {
	}

	/**
	 * Allows this instance to consult the supervisor when initialized for an eligible execution.
	 *
	 * @param cache the supervisor retained by this instance for eligible executions until its memory is cleared
	 */
	public final void bindFormulaCache(@Nonnull FormulaCacheSupervisor cache) {
		this.boundCache = cache;
	}

	/**
	 * Returns the memo first, otherwise computes or restores one complete tuple under the execution gate.
	 *
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a sampled recomputation differs from the cached
	 *         tuple; the memo then holds the recomputed result
	 */
	@Nonnull
	@Override
	public Bitmap compute() {
		if (this.memoizedResult != null) {
			return this.memoizedResult;
		}
		final FormulaCacheSupervisor cache = this.boundCache;
		if (cache == null) {
			this.memoizedResult = computeInternal();
			return this.memoizedResult;
		}
		if (this.executionContext == null || !this.executionContext.isFormulaCacheUsable() || !cache.isActive()) {
			if (this.executionContext != null && this.executionContext.isPrefetchExecution()) {
				cache.recordPrefetchBypass();
			}
			this.memoizedResult = computeInternal();
			return this.memoizedResult;
		}
		final FormulaKey key = getKey();
		final CachedResult cached = cache.lookup(key);
		if (cached == null) {
			computeAndAdmit(cache, key);
		} else if (cache.sampleHit()) {
			recomputeAndVerify(this.executionContext, cache, key, cached);
		} else {
			restoreFromCache(cached);
		}
		return this.memoizedResult;
	}

	/**
	 * Computes a miss and publishes its complete tuple when the per-entry cap accepts the charge.
	 *
	 * @param cache the bound supervisor that checks the charge and receives an admissible tuple
	 * @param key the detached key under which this node's computed tuple is published
	 */
	private void computeAndAdmit(@Nonnull FormulaCacheSupervisor cache, @Nonnull FormulaKey key) {
		final long start = System.nanoTime();
		this.memoizedResult = computeInternal();
		final long computeNanos = System.nanoTime() - start;
		final int weight = cache.admittedWeight(measurePayload(this.memoizedResult));
		if (weight > 0) {
			cache.admit(key, toCachedResult(this.memoizedResult, weight), computeNanos);
		}
	}

	/**
	 * Recomputes a sampled hit with the cache suspended for the whole query and compares it with the stored tuple.
	 *
	 * @param context the execution whose query-wide cache suspension is restored after recomputation
	 * @param cache the supervisor that compares the stored and recomputed tuples and records verification
	 * @param key the detached identity of the sampled computation used for verification diagnostics
	 * @param cached the stored tuple to compare against, whose accepted weight is reused for the recomputed wrapper
	 */
	private void recomputeAndVerify(
		@Nonnull QueryExecutionContext context, @Nonnull FormulaCacheSupervisor cache, @Nonnull FormulaKey key,
		@Nonnull CachedResult cached
	) {
		final long start = System.nanoTime();
		final boolean previous = context.suspendFormulaCache();
		try {
			this.memoizedResult = computeInternal();
			cache.verify(context, key, getClass().getName(), toString(), cached,
				toCachedResult(this.memoizedResult, cached.weight()), System.nanoTime() - start);
		} finally {
			context.resumeFormulaCache(previous);
		}
	}

	/**
	 * Returns the complete charge of the tuple this computation would retain, in long arithmetic. The bitmap is
	 * charged here; a formula with side outputs charges them too and freezes their order before publication.
	 *
	 * @param computed the bitmap computed by this node, measured together with any supported side outputs
	 * @return the complete entry charge in bytes, or {@link CachedResultWeigher#UNSIZED} for unsupported storage
	 */
	protected long measurePayload(@Nonnull Bitmap computed) {
		return CachedResultWeigher.measure(getKey(), computed);
	}

	/**
	 * Builds the complete tuple either for admission, after sizing and the per-entry cap have succeeded, or for
	 * sampled-hit verification, which reuses the weight of the cached tuple.
	 *
	 * @param computed the computed bitmap retained in the tuple without copying
	 * @param weight the positive byte charge accepted at admission; verification passes the cached tuple's weight
	 * @return a fresh wrapper retaining the computed bitmap and the accepted weight
	 */
	@Nonnull
	protected CachedResult toCachedResult(@Nonnull Bitmap computed, int weight) {
		return new CachedBitmapResult(computed, weight);
	}

	/**
	 * Restores the tuple and sets this node's own paid cost to zero.
	 *
	 * @param cached the complete stored tuple compatible with this formula; its bitmap is reused without copying
	 */
	protected void restoreFromCache(@Nonnull CachedResult cached) {
		restoreComputedResult(((CachedBitmapResult) cached).result());
	}

	/** Drops the binding as well as the result; the retained key may describe a previous mutable delegate. */
	@Override
	public void clearMemory() {
		super.clearMemory();
		this.boundCache = null;
	}
}
