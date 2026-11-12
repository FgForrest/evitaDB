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

package io.evitadb.spike.mock;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.index.bitmap.Bitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class MockEntityIdsFormula extends AbstractFormula implements FilteredPriceRecordAccessor {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -8786720221479629134L;
	/**
	 * Pre-built bitmap of entity primary keys returned directly by {@link #computeInternal()}.
	 */
	private final Bitmap entityIds;
	/**
	 * Pre-built filtered price records returned by {@link #getFilteredPriceRecords(QueryExecutionContext)}.
	 */
	private final FilteredPriceRecords priceRecords;

	/**
	 * Creates an initialized leaf exposing pre-built entity IDs and their price records.
	 *
	 * @param entityIds pre-built bitmap returned by computation
	 * @param priceRecords pre-built filtered price records
	 */
	public MockEntityIdsFormula(@Nonnull Bitmap entityIds, @Nonnull FilteredPriceRecords priceRecords) {
		this.entityIds = entityIds;
		this.priceRecords = priceRecords;
		initFields();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException();
	}

	/** Returns the same unit cost as a constant formula exposing a pre-built bitmap. */
	@Override
	public long getOperationCost() {
		return 1L;
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		return this.priceRecords;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return this.entityIds;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	public int getEstimatedCardinality() {
		return this.entityIds.size();
	}
}
