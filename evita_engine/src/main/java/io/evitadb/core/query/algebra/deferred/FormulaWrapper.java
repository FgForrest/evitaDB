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

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import javax.annotation.Nullable;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import java.util.function.BiFunction;

/**
 * Memoizes a wrapped formula computation. The transparent constructor adds nested-query execution telemetry;
 * {@link #custom(Formula, BiFunction)} captures result-changing computation with instance-unique identity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class FormulaWrapper implements BitmapSupplier {
	/** Stable bitmap supplier discriminator. */
	private static final long CLASS_ID = -4241517363456225853L;

	/**
	 * The delegate formula whose computation is wrapped by this supplier.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final Formula formula;
	/**
	 * Optional custom result computation; absent when the wrapper supplies telemetry around the delegate.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Nullable private final BiFunction<QueryExecutionContext, Formula, Bitmap> customComputation;
	/**
	 * Execution context from initialization phase.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private QueryExecutionContext executionContext;
	/**
	 * Memoized bitmap result, `null` until first access.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Bitmap computed;

	/** Description supplied only when execution telemetry is collected. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private final Supplier<String> stepDescription;

	/** Memoized detached holder key. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private FormulaKey memoizedKey;

	/** Wraps an unchanged computation with nested-query execution telemetry. */
	public FormulaWrapper(@Nonnull Formula formula, @Nonnull Supplier<String> stepDescription) {
		this.formula = formula;
		this.stepDescription = stepDescription;
		this.customComputation = null;
	}

	/** Captures custom result logic whose identity is bound to the wrapper instance. */
	private FormulaWrapper(
		@Nonnull Formula formula,
		@Nonnull BiFunction<QueryExecutionContext, Formula, Bitmap> computation
	) {
		this.formula = formula;
		this.stepDescription = null;
		this.customComputation = computation;
	}

	/** Builds an instance-unique wrapper for computation that may change the delegate's result. */
	@Nonnull
	public static FormulaWrapper custom(
		@Nonnull Formula formula,
		@Nonnull BiFunction<QueryExecutionContext, Formula, Bitmap> computation
	) {
		return new FormulaWrapper(formula, computation);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.executionContext = executionContext;
		this.formula.initialize(executionContext);
	}

	@Override
	public int getEstimatedCardinality() {
		return this.formula.getEstimatedCardinality();
	}

	@Override
	public long getHash() {
		return this.formula.getHash();
	}

	@Override
	public long getTransactionalIdHash() {
		return this.formula.getTransactionalIdHash();
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return this.formula.gatherTransactionalIds();
	}

	@Override
	public long getEstimatedCost() {
		return this.formula.getEstimatedCost();
	}

	@Override
	public long getCost() {
		return this.formula.getCost();
	}

	@Override
	public long getOperationCost() {
		return this.formula.getOperationCost();
	}

	@Override
	public Bitmap get() {
		if (this.computed == null) {
			Assert.isPremiseValid(this.executionContext != null, "FormulaWrapper must be initialized prior to calling get().");
			if (this.customComputation == null) {
				try {
					this.executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, this.stepDescription);
					this.computed = this.formula.compute();
				} finally {
					this.executionContext.popStep();
				}
			} else {
				this.computed = this.customComputation.apply(this.executionContext, this.formula);
			}
		}
		return this.computed;
	}

	@Override
	public String toString() {
		return this.formula.toString();
	}

	/** Returns detached inputs without computing the bitmap. */
	@Nonnull
	public IdentityRecord identity() {
		return new Identity(this.formula.getKey(), this.customComputation == null ? null : IdentityToken.OPAQUE);
	}

	/** Returns the memoized detached key of this holder. */
	@Nonnull
	@Override
	public FormulaKey getKey() {
		if (this.memoizedKey == null) {
			this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		return this.memoizedKey;
	}

	/** Returns the stable holder discriminator. */
	public long getClassId() {
		return CLASS_ID;
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param formula key of the delegate computation
	 * @param customComputation opaque marker for custom result logic, absent for transparent telemetry
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull FormulaKey formula,
		@Nullable IdentityToken customComputation) implements IdentityRecord {
		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every input in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.formula);
			accumulator.add(this.customComputation);
		}
	}
}
