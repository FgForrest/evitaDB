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

package io.evitadb.core.query.filter.translator.entity.alternative;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.entityFetch;

/**
 * Implementation of {@link EntityToBitmapFilter} that verifies that the entity has the appropriate locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class LocaleEntityToBitmapFilter implements EntityToBitmapFilter {
	/** Unique identifier of this entity filter. */
	private static final long CLASS_ID = 1344332402758057590L;
	/**
	 * The locale to be used for filtering entities.
	 */
	private final Locale locale;
	/**
	 * The memoized result of the filter.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Bitmap memoizedResult;

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
						.filter(it -> it.getLocales().contains(this.locale))
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
		return entityFetch(dataInLocales(this.locale));
	}
	/** Captures the detached semantic inputs without evaluating the holder. */
	@Nonnull
	@Override
	public IdentityRecord identity() {
		return new Identity(this.locale);
	}

	/** Returns the filter's class identifier. */
	@Override
	public long getClassId() {
		return CLASS_ID;
	}

	/**
	 * Detached semantic inputs of the holder.
	 *
	 * @param locale required locale
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull Locale locale) implements IdentityRecord {
		/** Returns the holder's class identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every semantic input in the structural hash and retained weight. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.locale);
		}
	}

}
