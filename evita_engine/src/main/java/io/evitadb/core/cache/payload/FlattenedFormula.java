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

package io.evitadb.core.cache.payload;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Flattened formula represents a memoized form of original formula that contains already computed bitmap of results.
 * This variant of flattened formula keeps computed bitmap of integers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class FlattenedFormula extends CachePayloadHeader implements Formula {
	@Serial private static final long serialVersionUID = -1183017816058041094L;
	private static final Formula[] EMPTY_FORMULAS = Formula.EMPTY_FORMULA_ARRAY;
	/**
	 * Memoized result of the original formula.
	 */
	protected final Bitmap memoizedResult;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(@Nonnull long[] transactionalIds, @Nonnull Bitmap memoizedResult) {
		return CachePayloadHeader.estimateSize(transactionalIds) +
			RoaringBitmapBackedBitmap.getRoaringBitmap(memoizedResult).getSizeInBytes();
	}

	public FlattenedFormula(long formulaHash, long transactionalIdHash, @Nonnull long[] originalBitmapIds, @Nonnull Bitmap memoizedResult) {
		super(formulaHash, transactionalIdHash, originalBitmapIds);
		this.memoizedResult = memoizedResult;
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {

	}

	@Override
	public void clearMemory() {

	}

	@Override
	public long getHash() {
		return this.recordHash;
	}

	@Override
	public long getTransactionalIdHash() {
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return this.transactionalDataIds;
	}

	@Override
	public void accept(@Nonnull FormulaVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public long getEstimatedCost() {
		return 0;
	}

	@Override
	public long getCost() {
		return 0;
	}

	@Override
	public int getEstimatedCardinality() {
		return this.memoizedResult.size();
	}

	@Override
	public long getCostToPerformanceRatio() {
		return Integer.MAX_VALUE;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("FlattenedFormula represents cached result, cannot be cloned!");
	}

	@Override
	public long getOperationCost() {
		return 0;
	}

	@Nonnull
	@Override
	public Formula[] getInnerFormulas() {
		return EMPTY_FORMULAS;
	}

	@Nonnull
	@Override
	public Bitmap compute() {
		return this.memoizedResult;
	}

	@Override
	public String toString() {
		return "FLATTENED: " + this.memoizedResult.size() + " primary keys";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return "FLATTENED: " + this.memoizedResult.toString();
	}

	@Nonnull
	@Override
	public String prettyPrint() {
		return toString();
	}

}
