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

package io.evitadb.api.statistics;

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * The catalog-level half of the {@link CatalogStatisticsComponent#INDEX_CARDINALITY} component - how many distinct
 * values each of the catalog's *global unique indexes* holds.
 *
 * A catalog index exists per {@link Scope} and holds one global unique index per globally-unique attribute the catalog
 * schema declares (per locale, for an attribute that is unique globally only *within* a locale). These indexes are what
 * back `getEntityByUniqueAttribute`-style lookups that name no entity type: they map one attribute value to the single
 * entity carrying it, across every collection at once.
 *
 * **Why this is catalog-level while the per-collection form is not**
 *
 * The reason {@link CollectionIndexCardinality} is expensive does not transfer here, and it is worth being precise
 * about why, because the two halves of one component having different cost classes looks like an inconsistency:
 *
 * - **Bounded by a small constant, not by the data volume.** The number of global unique indexes is
 *   (globally-unique attributes × locales in use). A catalog with a million products has exactly as many as one with
 *   ten. The locale half of that product is data-influenced rather than declared - the schema names the attributes but
 *   not the locales they end up written in - so this is a small constant, not a schema-derived bound.
 * - **Every reading is `O(1)`.** A global unique index's size is a counter maintained incrementally by its backing
 *   tree, not a walk - unlike a filter index's covered-record count, which sums per-bucket counts and is the one
 *   reading that makes the collection-level form expensive.
 *
 * So this half can be assembled for the whole catalog on every request without the cost growing with anything the
 * catalog contains, which is exactly what the collection-level form cannot promise.
 *
 * It is answerable for every catalog at once as well. Being cheap to compute settles time but not payload, and this
 * is a *listing* the instance-wide call multiplies by the number of catalogs - so it was weighed on that axis too. It
 * was admitted because the listing stays in the same size class as the collection inventory of
 * {@link CatalogStatisticsComponent#COLLECTIONS}, which that call already carries for every catalog.
 *
 * **Distinct values and records covered are the same number here**, because a globally-unique value identifies exactly
 * one record by definition. Only one of them is therefore reported: reporting both would invite a reader to compare
 * them for selectivity, and their ratio is fixed at 1 by construction.
 *
 * @param globalUniqueIndexes one entry per global unique index the catalog holds, in no guaranteed order; empty when
 *                            the schema declares no globally-unique attribute, or when none has been written to yet
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CollectionIndexCardinality
 * @see CatalogStatisticsComponent#INDEX_CARDINALITY
 */
public record CatalogIndexCardinality(
	@Nonnull GlobalUniqueIndexCardinality[] globalUniqueIndexes
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final CatalogIndexCardinality that = (CatalogIndexCardinality) o;
		return Arrays.equals(this.globalUniqueIndexes, that.globalUniqueIndexes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.globalUniqueIndexes);
	}

	@Nonnull
	@Override
	public String toString() {
		return "CatalogIndexCardinality{globalUniqueIndexes=" + Arrays.toString(this.globalUniqueIndexes) + '}';
	}

	/**
	 * The cardinality reading of one global unique index.
	 *
	 * @param attributeName      name of the globally-unique attribute this index covers
	 * @param locale             locale this index is bound to, or null when the attribute is unique globally across
	 *                           every locale; a non-null locale means the catalog holds one such index per locale
	 * @param scope              scope of the catalog index holding this global unique index
	 * @param distinctValueCount how many distinct values the index holds - equivalently, how many records it covers,
	 *                           since a globally-unique value identifies exactly one record
	 */
	public record GlobalUniqueIndexCardinality(
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull Scope scope,
		int distinctValueCount
	) {

		public GlobalUniqueIndexCardinality {
			Objects.requireNonNull(attributeName, "Attribute name must not be null!");
			Objects.requireNonNull(scope, "Scope must not be null!");
		}

	}

}
