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

import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Projects the catalog's own indexes into the same {@link BrowsedIndex} rows and {@link IndexDetail} responses an
 * entity collection's indexes are projected into.
 *
 * The catalog holds one {@link CatalogIndex} per {@link Scope}, so this is the counterpart of the collection-level
 * projections with the scale inverted: those walk a map of up to hundreds of thousands of entries and are shaped
 * entirely by that fact, this one walks at most two. That is why the priority-queue top-N machinery and the long
 * arithmetic guarding a client-supplied page window are absent here rather than shared - none of it can do anything
 * over two rows, and copying it would be code no test could ever distinguish from a plain slice.
 *
 * **What *is* shared is the vocabulary, which is the whole point.** The rows carry the same fields, answer to the same
 * {@link IndexBrowseCriteria}, and drill down into the same detail record, so a client browsing a catalog's indexes and
 * a collection's runs one code path over both.
 *
 * **The handle is derived from the scope, not assigned.** A collection numbers its indexes from a forward-only
 * sequence; a catalog index has no such number - {@link io.evitadb.index.CatalogIndexKey} is a bare scope, because
 * there is exactly one index per scope and nothing to tell apart. {@link #toIndexPrimaryKey} therefore *is* the
 * identity, and its numbers are a published wire contract: they travel to clients, so they may never be renumbered,
 * only extended. It is written as an explicit switch rather than `Scope#ordinal()` for exactly that reason - an
 * ordinal silently renumbers the moment a constant is inserted, whereas adding one here fails to compile until
 * somebody chooses its number.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see IndexDetail
 */
final class CatalogIndexProjection {

	/**
	 * Orders browse rows by their handle, which is the only total order over catalog indexes that exists.
	 *
	 * Both {@link IndexBrowseOrdering} values collapse to this one. {@link IndexBrowseOrdering#MAP_ORDER} has no map to
	 * follow - the indexes are addressed by scope, so their handles *are* their natural order - and
	 * {@link IndexBrowseOrdering#BY_ENTITY_COUNT_DESC} has nothing to discriminate on, because every catalog index
	 * reports an absent entity count. Applied explicitly rather than left to the order the indexes happened to be
	 * collected in: an unstable order silently corrupts pagination, repeating some rows across pages while omitting
	 * others.
	 */
	private static final Comparator<BrowsedIndex> BY_HANDLE =
		Comparator.comparingInt(BrowsedIndex::indexPrimaryKey);

	private CatalogIndexProjection() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Returns the opaque handle a client addresses the catalog index of the given scope by.
	 *
	 * These numbers are a wire contract - they are handed to clients in a browse and handed back in a drill-down - so
	 * an existing one may never change its meaning. A new {@link Scope} constant makes this switch non-exhaustive and
	 * therefore fails the build, which is the intended forcing function: the number is a decision, not a side effect of
	 * declaration order.
	 *
	 * @param scope scope of the catalog index
	 * @return the handle identifying that index within the catalog
	 */
	static int toIndexPrimaryKey(@Nonnull Scope scope) {
		return switch (scope) {
			case LIVE -> 0;
			case ARCHIVED -> 1;
		};
	}

	/**
	 * Resolves a handle back to the scope whose catalog index it addresses.
	 *
	 * Derived from {@link #toIndexPrimaryKey} rather than written out a second time, so the two cannot disagree; the
	 * loop runs over {@link Scope#values()}, which is the small constant this whole class is scaled to.
	 *
	 * @param indexPrimaryKey handle obtained from a browse
	 * @return the scope it addresses, or null when it addresses no catalog index at all
	 */
	@Nullable
	static Scope toScope(int indexPrimaryKey) {
		for (final Scope scope : Scope.values()) {
			if (toIndexPrimaryKey(scope) == indexPrimaryKey) {
				return scope;
			}
		}
		return null;
	}

	/**
	 * Selects, orders and pages the catalog's own indexes.
	 *
	 * @param catalogIndexes the catalog indexes that have actually been created, one per scope at most
	 * @param criteria       which indexes to select, in what order, and which page to return
	 * @param catalogVersion version of the catalog the indexes were read at, reported so a caller can tell that two
	 *                       pages of one browse describe two different index sets
	 * @return the requested page and the total number of matches behind it
	 */
	@Nonnull
	static IndexBrowseResult browse(
		@Nonnull List<CatalogIndex> catalogIndexes,
		@Nonnull IndexBrowseCriteria criteria,
		long catalogVersion
	) {
		final List<BrowsedIndex> matched = new ArrayList<>(catalogIndexes.size());
		for (final CatalogIndex catalogIndex : catalogIndexes) {
			final Scope scope = catalogIndex.getIndexKey().scope();
			if (matches(scope, criteria)) {
				matched.add(describeRow(scope));
			}
		}
		matched.sort(BY_HANDLE);

		// the offset is computed in long arithmetic for the same reason the collection-level browse does it - both
		// factors are client-supplied and their product overflows int long before it reaches any bound - even though
		// the list it is applied to holds at most one row per scope
		final long offset = (long) (criteria.pageNumber() - 1) * criteria.pageSize();
		final int from = (int) Math.min(offset, matched.size());
		final int to = (int) Math.min(offset + criteria.pageSize(), matched.size());
		return new IndexBrowseResult(
			catalogVersion,
			criteria.pageNumber(),
			criteria.pageSize(),
			matched.size(),
			matched.subList(from, to).toArray(BrowsedIndex[]::new)
		);
	}

	/**
	 * Describes one catalog index in full - what it occupies, and how well each of its global unique indexes
	 * discriminates.
	 *
	 * Every reading beyond the heap estimate is an `O(1)` counter, and the number of them is bounded by
	 * (globally-unique attributes x locales in use), so the cost of this call is the heap walk and nothing else - the
	 * invariant {@link IndexDetail} rests on.
	 *
	 * @param catalogIndex the index to describe, already resolved by the caller from its handle
	 * @return the full description of that one index
	 */
	@Nonnull
	static IndexDetail describe(@Nonnull CatalogIndex catalogIndex) {
		final Scope scope = catalogIndex.getIndexKey().scope();
		final List<AttributeCardinality> attributes = new ArrayList<>(16);
		// `forEach`, never `entrySet()`: asking a map for a view parks it on the map for the lifetime of the index -
		// see `documentation/developer/heap-size-testing.md`, trap 6
		catalogIndex.getGlobalUniqueIndexes().forEach((attributeKey, globalUniqueIndex) ->
			attributes.add(
				new AttributeCardinality(
					attributeKey.attributeName(),
					// a globally unique attribute is declared on the catalog schema and carried by the entity itself,
					// so it is never a reference attribute
					null,
					attributeKey.locale(),
					AttributeIndexType.UNIQUE,
					globalUniqueIndex.size(),
					globalUniqueIndex.getRecordCount()
				)
			)
		);
		return new IndexDetail(
			null,
			toIndexPrimaryKey(scope),
			catalogIndex.getHeapSizeInBytes(),
			new IndexCardinality(
				// a catalog index has no entity-index kind, no sibling to be discriminated from, no primary-key bitmap
				// to count entities off, and no reference dimension - all four absences are the index's shape rather
				// than a reading that could not be taken
				null, scope, null, null, null,
				attributes.toArray(AttributeCardinality[]::new)
			)
		);
	}

	/**
	 * Tells whether the catalog index of the given scope passes every filter of the criteria.
	 *
	 * Two of the three categories can only ever exclude it, and that is the accurate reading rather than an omission: a
	 * catalog index carries no entity-index kind, so naming any kind selects none of them, and it is bound to no
	 * reference, so naming any reference selects none either.
	 *
	 * **The reference names are deliberately not validated here**, where a collection browse rejects a name its entity
	 * schema does not declare. There is no entity schema to validate against - the filter addresses a dimension catalog
	 * indexes do not have at all - so there is no typo to protect the caller from, and an empty page is the honest
	 * answer.
	 *
	 * @param scope    scope of the catalog index to test
	 * @param criteria the filters to apply
	 * @return true when the index belongs in the answer
	 */
	private static boolean matches(@Nonnull Scope scope, @Nonnull IndexBrowseCriteria criteria) {
		if (!criteria.indexTypes().isEmpty() || !criteria.referenceNames().isEmpty()) {
			return false;
		}
		final Set<Scope> scopes = criteria.scopes();
		return scopes.isEmpty() || scopes.contains(scope);
	}

	/**
	 * Renders the catalog index of one scope into its browse row.
	 *
	 * @param scope scope of the index
	 * @return the descriptor
	 */
	@Nonnull
	private static BrowsedIndex describeRow(@Nonnull Scope scope) {
		return new BrowsedIndex(
			// no entity type - the catalog holds this index itself - and with it no kind, no discriminator in any of
			// its three renderings, and no entity count; see `BrowsedIndex`
			null,
			toIndexPrimaryKey(scope),
			null,
			scope,
			null,
			null,
			null,
			null
		);
	}

}
