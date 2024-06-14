/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.function.BiFunction;

/**
 * This bitmap supplier just delegates the call to the this.formula but when the computation (result memoization) occurs,
 * it executes the computation within {@link #firstInvocation} lambda. The wrapper is mentioned to be used for
 * generating a query telemetry around the this.formula result computation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class FormulaWrapper implements BitmapSupplier {
	private final Formula formula;
	private final BiFunction<QueryExecutionContext, Formula, Bitmap> firstInvocation;
	private QueryExecutionContext executionContext;
	private Bitmap computed;

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		this.executionContext = calculationContext.getExecutionContext();
		this.formula.initialize(calculationContext);
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
	public long getCostToPerformanceRatio() {
		return this.formula.getCostToPerformanceRatio();
	}

	@Override
	public Bitmap get() {
		if (this.computed == null) {
			Assert.isPremiseValid(this.executionContext != null, "FormulaWrapper must be initialized prior to calling get().");
			this.computed = this.firstInvocation.apply(this.executionContext, this.formula);
		}
		return this.computed;
	}

	@Override
	public String toString() {
		return this.formula.toString();
	}
}
