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

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * This formula is placeholder for empty bitmap with no records whatsoever.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EmptyFormula extends AbstractFormula {
	/**
	 * Singleton instance representing an empty result set with no matching records.
	 */
	public static final EmptyFormula INSTANCE = new EmptyFormula();
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 7864590390866911682L;

	private EmptyFormula() {
		this.initFields();
	}

	/**
	 * {@link #INSTANCE} is a shared, application-wide singleton. {@link AbstractFormula#initialize}
	 * stores the per-query {@link QueryExecutionContext} in a field - doing so on this shared instance
	 * would pin that query's execution context (and the whole {@link io.evitadb.core.catalog.Catalog}
	 * snapshot reachable from it) in a static field for the entire JVM lifetime, leaking catalog
	 * versions. This formula never reads the execution context ({@link #computeInternal()} returns
	 * {@link EmptyBitmap#INSTANCE}), so initialization is intentionally a no-op.
	 */
	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// intentionally empty - see JavaDoc
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Constant formula cannot have inner formulas!");
	}

	@Override
	public long getOperationCost() {
		return 0;
	}

	@Override
	public int getEstimatedCardinality() {
		return 0;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return EmptyBitmap.INSTANCE;
	}

	@Override
	public String toString() {
		return "EMPTY";
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
