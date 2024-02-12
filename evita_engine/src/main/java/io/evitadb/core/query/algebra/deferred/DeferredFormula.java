/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Deferred formula serves as "lazy computation" container for logic encapsulated in passed {@link Supplier}
 * implementation. This allows to postpone the calculation and also to memoize the computation result so that it is
 * computed only once.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DeferredFormula extends AbstractFormula {
	private static final long CLASS_ID = 8831456737770154017L;
	protected final BitmapSupplier retrieveLambda;

	public DeferredFormula(@Nonnull BitmapSupplier retrieveLambda) {
		super();
		this.retrieveLambda = retrieveLambda;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Deferred formula is a terminal formula and cannot have children!");
	}

	@Override
	protected long getEstimatedCostInternal(@Nonnull CalculationContext calculationContext) {
		return retrieveLambda.getEstimatedCost(calculationContext);
	}

	@Override
	protected long getCostInternal(@Nonnull CalculationContext calculationContext) {
		return retrieveLambda.getCost(calculationContext);
	}

	@Override
	protected long getCostToPerformanceInternal(@Nonnull CalculationContext calculationContext) {
		return retrieveLambda.getCost(calculationContext) / Math.max(1, retrieveLambda.get().size());
	}

	@Override
	public long getOperationCost() {
		return 60;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return retrieveLambda.get();
	}

	@Override
	public int getEstimatedCardinality() {
		return retrieveLambda.getEstimatedCardinality();
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		return retrieveLambda.gatherTransactionalIds();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			new long[] {
				CLASS_ID,
				retrieveLambda.computeHash(hashFunction)
			}
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	public String toString() {
		return "DEFERRED CALCULATION: " + retrieveLambda.toString();
	}
}
