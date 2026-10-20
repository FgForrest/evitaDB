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

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Implementation of {@link EntityToBitmapFilter} that keeps the prefetched entities living in one scope. A query naming
 * no collection searches the catalog index, which holds no set of all the entities of a scope, so the entities of
 * the scope are told apart on the bodies of the entities its lookups found.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ScopeEntityToBitmapFilter implements EntityToBitmapFilter {
	/**
	 * The bare entity bodies, which carry the scope of the entity.
	 */
	private static final EntityFetch ENTITY_BODY = new EntityFetch();
	/**
	 * The scope the entities must live in.
	 */
	private final Scope scope;
	/**
	 * The memoized result of the filter.
	 */
	private Bitmap memoizedResult;

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull QueryExecutionContext context) {
		if (this.memoizedResult == null) {
			final List<ServerEntityDecorator> entities = context.getPrefetchedEntities();
			if (entities == null) {
				this.memoizedResult = EmptyBitmap.INSTANCE;
			} else {
				final BaseBitmap result = new BaseBitmap();
				for (final ServerEntityDecorator entity : entities) {
					if (entity.getScope() == this.scope) {
						result.add(context.translateEntity(entity));
					}
				}
				this.memoizedResult = result;
			}
		}
		return this.memoizedResult;
	}

	@Nonnull
	@Override
	public EntityFetchRequire getEntityRequire() {
		return ENTITY_BODY;
	}
}
