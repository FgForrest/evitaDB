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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The formula is used only in case that the input {@link EvitaRequest} doesn't specify the entity collection it
 * targets. This may be ok situation if the query contains filter to the {@link GlobalAttributeSchema globally unique
 * attributes} - such filter would trigger entity prefetch and the evaluation of the query would be executed upon these
 * rich entity objects instead of indexes. If the request doesn't specify filter on global attributes the formula will
 * ultimately fail with {@link EntityCollectionRequiredException}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityFilteringFormula extends AbstractFormula implements RequirementsDefiner, FilteredPriceRecordAccessor {
	private static final long CLASS_ID = -1887923944737482575L;
	/**
	 * Contains a simple text, that would be used in an error message if the entities are not prefetched and computation
	 * of the result is executed. In such case, the target entity collection needs to be known and in case of this
	 * formula it's never known.
	 */
	private final String reason;
	/**
	 * Contains the alternative computation based on entity contents filtering.
	 */
	private final EntityToBitmapFilter alternative;

	public EntityFilteringFormula(@Nonnull String reason, @Nonnull EntityToBitmapFilter alternative) {
		this.reason = reason;
		this.alternative = alternative;
		this.initFields();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		Assert.isPremiseValid(this.executionContext != null, "The formula hasn't been initialized!");
		Assert.isTrue(
			this.executionContext.getPrefetchedEntities() != null,
			() -> new EntityCollectionRequiredException(this.reason)
		);
		return this.alternative.filter(this.executionContext);
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		Assert.isTrue(context.getPrefetchedEntities() != null, () -> new EntityCollectionRequiredException("matching entities"));
		return this.alternative instanceof FilteredPriceRecordAccessor fpra ?
			fpra.getFilteredPriceRecords(context) :
			new ResolvedFilteredPriceRecords();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Entity filtering formula doesn't support inner formulas, just bitmaps.");
	}

	@Nullable
	@Override
	public EntityFetchRequire getEntityRequire() {
		return this.alternative.getEntityRequire();
	}

	@Override
	public int getEstimatedCardinality() {
		// we can't know upfront - but this formula is used for scenarios where the entities are prefetched
		// so the cost is not relevant
		return Integer.MAX_VALUE;
	}

	@Override
	protected long getEstimatedCostInternal() {
		// we can't know upfront - but this formula is used for scenarios where the entities are prefetched
		// so the cost is not relevant
		return Long.MAX_VALUE;
	}

	@Override
	protected long getCostInternal() {
		if (this.alternative.getEntityRequire() == null) {
			return 0;
		}
		return ((1 + this.alternative.getEntityRequire().getRequirements().length) * 148L) * compute().size();
	}

	@Override
	protected long getCostToPerformanceInternal() {
		return getCost() / Math.max(1, compute().size());
	}

	@Override
	public long getOperationCost() {
		return 1;
	}
}
