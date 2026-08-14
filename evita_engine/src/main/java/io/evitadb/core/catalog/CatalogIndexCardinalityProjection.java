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

package io.evitadb.core.catalog;

import io.evitadb.api.statistics.CatalogIndexCardinality;
import io.evitadb.api.statistics.CatalogIndexCardinality.GlobalUniqueIndexCardinality;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects the catalog's per-scope {@link CatalogIndex} instances into the catalog-level half of the
 * {@link io.evitadb.api.statistics.CatalogStatisticsComponent#INDEX_CARDINALITY} component.
 *
 * Unlike the collection-level projection this one walks the index map outright, and that is safe for a reason worth
 * stating precisely: a catalog index holds one global unique index per globally-unique attribute per locale, so the
 * map's size is bounded by (globally-unique attributes × locales in use). That is *not* a purely schema-derived
 * bound - the schema declares the attributes but not the locales, which is exactly why this projection enumerates the
 * map instead of driving off the schema - but it is a small constant that does not grow with entity count: a catalog
 * with a million products has exactly as many entries as one with ten. Every reading taken here is an `O(1)` counter
 * maintained by the index's backing tree, so the whole projection is bounded by that constant in both time and
 * response size.
 *
 * **Visibility differs from the collection-level index counts, deliberately.** Those are a plain `int[]` handed
 * forward at each catalog version boundary, so they can only ever report published state. The readings taken here go
 * through the index tree's `TransactionalReference`, which consults the calling thread's transactional layer when one
 * is bound - so an embedded caller asking for statistics from *inside* a write block sees its own uncommitted
 * insertions, where the collection counts would still show the pre-transaction figure. Neither is wrong, but they are
 * not the same rule, and no caller should assume one from the other. Every remote caller is unaffected: the API
 * handler thread never carries a transactional layer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogIndexCardinality
 */
final class CatalogIndexCardinalityProjection {

	private CatalogIndexCardinalityProjection() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Describes the cardinality of every global unique index the passed catalog indexes hold.
	 *
	 * @param catalogIndexes the catalog indexes to describe, one per scope that has actually been created; a scope
	 *                       whose index has never been created contributes nothing rather than an empty entry
	 * @return the catalog-level half of the
	 *         {@link io.evitadb.api.statistics.CatalogStatisticsComponent#INDEX_CARDINALITY} component
	 */
	@Nonnull
	static CatalogIndexCardinality describe(@Nonnull List<CatalogIndex> catalogIndexes) {
		final List<GlobalUniqueIndexCardinality> described = new ArrayList<>(16);
		for (final CatalogIndex catalogIndex : catalogIndexes) {
			final Scope scope = catalogIndex.getIndexKey().scope();
			// `forEach`, never `entrySet()`: asking a map for a view parks it on the map for the lifetime of the index -
			// see `documentation/developer/heap-size-testing.md`, trap 6
			catalogIndex.getGlobalUniqueIndexes().forEach((attributeKey, globalUniqueIndex) ->
				described.add(
					new GlobalUniqueIndexCardinality(
						attributeKey.attributeName(),
						attributeKey.locale(),
						scope,
						// distinct values, which is not always the covered-record count - a localized globally-unique
						// attribute has one locale-less key covering every locale, so one record can own several values
						// in it. The covered-record count comes from `GlobalUniqueIndex#getRecordCount` and is reported
						// by the per-index detail call, which reaches one catalog index rather than all of them
						globalUniqueIndex.size()
					)
				)
			);
		}
		return new CatalogIndexCardinality(described.toArray(GlobalUniqueIndexCardinality[]::new));
	}

}
