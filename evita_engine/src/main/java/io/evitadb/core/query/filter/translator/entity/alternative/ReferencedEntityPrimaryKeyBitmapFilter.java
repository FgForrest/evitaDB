/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.filter.translator.entity.alternative;

import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.referenceContent;

/**
 * Evaluates an {@link EntityPrimaryKeyInSet} that sits inside a {@link ReferenceHaving} body against prefetched
 * entities rather than against indexes.
 *
 * Inside such a body the constraint speaks about the **referenced** entity, so an owner matches when it holds at
 * least one reference row of the enclosing reference whose target is among the requested primary keys. That is the
 * row-scoped reading, and it is the same question the index-side adapter answers by emitting the owner sets of the
 * matching reduced indexes.
 *
 * Without this alternative the constraint has no prefetched form at all: the index-side formula is built from the
 * reduced-index family, which a prefetching plan never populates, so the whole conjunction collapses to empty. The
 * constraint used to be suppressed in this position, which hid the gap - a suppressed body falls back to the super
 * set and therefore returned every owner holding any row of the reference, non-empty and wrong.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferencedEntityPrimaryKeyBitmapFilter implements EntityToBitmapFilter {
	/**
	 * Name of the reference whose rows are examined - the one the enclosing `referenceHaving` names.
	 */
	private final String referenceName;
	/**
	 * Requested referenced entity primary keys, sorted so membership is a binary search.
	 */
	private final int[] requestedPrimaryKeys;
	/**
	 * The memoized result of the filter.
	 */
	private Bitmap memoizedResult;

	public ReferencedEntityPrimaryKeyBitmapFilter(
		@Nonnull String referenceName,
		@Nonnull int[] requestedPrimaryKeys
	) {
		this.referenceName = referenceName;
		this.requestedPrimaryKeys = Arrays.copyOf(requestedPrimaryKeys, requestedPrimaryKeys.length);
		Arrays.sort(this.requestedPrimaryKeys);
	}

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull QueryExecutionContext context) {
		if (this.memoizedResult == null) {
			final List<ServerEntityDecorator> entities = context.getPrefetchedEntities();
			if (entities == null) {
				this.memoizedResult = EmptyBitmap.INSTANCE;
			} else {
				this.memoizedResult = new BaseBitmap(
					entities.stream()
						.filter(this::hasRowTargetingRequestedEntity)
						.mapToInt(context::translateEntity)
						.toArray()
				);
			}
		}
		return this.memoizedResult;
	}

	@Nonnull
	@Override
	public EntityFetchRequire getEntityRequire() {
		return entityFetch(referenceContent(this.referenceName));
	}

	/**
	 * Returns true when the entity holds at least one row of the examined reference pointing at a requested entity.
	 *
	 * @param entity prefetched entity to examine
	 * @return true when at least one row matches
	 */
	private boolean hasRowTargetingRequestedEntity(@Nonnull ServerEntityDecorator entity) {
		for (final ReferenceContract reference : entity.getReferences(this.referenceName)) {
			if (Arrays.binarySearch(this.requestedPrimaryKeys, reference.getReferencedPrimaryKey()) >= 0) {
				return true;
			}
		}
		return false;
	}

}
