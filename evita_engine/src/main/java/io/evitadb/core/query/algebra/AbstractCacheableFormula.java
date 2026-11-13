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
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Formula whose computed tuple may be retained across queries. Binding preserves the node and all its children.
 * Clones start unbound; clearing a mutable ancestor's result also drops its binding because its key remains memoized.
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

	/** Allows this instance to consult the supervisor when initialized for an eligible execution. */
	public final void bindFormulaCache(@Nonnull FormulaCacheSupervisor cache) {
		this.boundCache = cache;
	}

	/** Returns the memo first, otherwise computes or restores one complete tuple under the execution gate. */
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
		if (cached != null) {
			if (cache.sampleHit()) {
				final long start = System.nanoTime();
				final boolean previous = this.executionContext.suspendFormulaCache();
				try {
					this.memoizedResult = computeInternal();
					cache.verify(this.executionContext, key, getClass().getName(), toString(), cached,
						toCachedResult(this.memoizedResult, cached.weight()), System.nanoTime() - start);
				} finally {
					this.executionContext.resumeFormulaCache(previous);
				}
			} else {
				restoreFromCache(cached);
			}
		} else {
			final long start = System.nanoTime();
			this.memoizedResult = computeInternal();
			final long computeNanos = System.nanoTime() - start;
			final int weight = cache.admittedWeight(measurePayload(this.memoizedResult));
			if (weight > 0) {
				cache.admit(key, toCachedResult(this.memoizedResult, weight), computeNanos);
			}
		}
		return this.memoizedResult;
	}

	/** Freezes side outputs, if any, and measures the complete retained graph in long arithmetic. */
	protected long measurePayload(@Nonnull Bitmap computed) {
		return CachedResultWeigher.measure(getKey(), computed);
	}

	/** Builds the complete tuple only after sizing and the per-entry cap have succeeded. */
	@Nonnull
	protected CachedResult toCachedResult(@Nonnull Bitmap computed, int weight) {
		return new CachedBitmapResult(computed, weight);
	}

	/** Restores the tuple and sets this node's own paid cost to zero. */
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
