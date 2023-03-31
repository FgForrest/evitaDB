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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * This bitmap supplier just delegates the call to the formula but when the computation (result memoization) occurs,
 * it executes the computation within {@link #firstInvocation} lambda. The wrapper is mentioned to be used for
 * generating a query telemetry around the formula result computation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class FormulaWrapper implements BitmapSupplier {
	private final Formula formula;
	private final Function<Formula, Bitmap> firstInvocation;
	private boolean computed;

	@Override
	public int getEstimatedCardinality() {
		return formula.getEstimatedCardinality();
	}

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return formula.computeHash(hashFunction);
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		return formula.computeTransactionalIdHash(hashFunction);
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return formula.gatherTransactionalIds();
	}

	@Override
	public long getEstimatedCost() {
		return formula.getEstimatedCost();
	}

	@Override
	public long getCost() {
		return formula.getCost();
	}

	@Override
	public long getOperationCost() {
		return formula.getOperationCost();
	}

	@Override
	public long getCostToPerformanceRatio() {
		return formula.getCostToPerformanceRatio();
	}

	@Override
	public Bitmap get() {
		if (computed) {
			return formula.compute();
		} else {
			computed = true;
			return firstInvocation.apply(formula);
		}
	}
}
