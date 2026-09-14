/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation.conflict;


import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Catalog-level conflict key for serializing concurrent engine mutations.
 *
 * The mutation conflict resolver uses this key to group all mutations that target the same
 * catalog name, ensuring such mutations are not processed concurrently. Because this record
 * contains only {@link #catalogName}, the automatically generated {@code equals} and
 * {@code hashCode} methods compare keys solely by the catalog name.
 *
 * Summary:
 * - Scope: catalog-wide
 * - Equality/hashCode: based only on {@code catalogName}
 *
 * @see ConflictKey
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record CatalogConflictKey(
	@Nonnull String catalogName
) implements ConflictKey {

	/**
	 * {@inheritDoc}
	 *
	 * @return {@link ConflictScope#CATALOG}
	 */
	@Nonnull
	@Override
	public ConflictScope conflictScope() {
		return ConflictScope.CATALOG;
	}

	/**
	 * Returns the keys a mutation must claim when it *introduces* a catalog name - the raw name, plus the name in
	 * every naming convention.
	 *
	 * Catalog names are unique convention-wide, not literally: `CatalogSchema#checkCatalogNameIsAvailable` refuses
	 * a name that matches an existing one in any convention, so `reportsArchive` and `reports_archive` name the
	 * same catalog as far as uniqueness is concerned. A single literal key cannot express that. Two mutations
	 * introducing names that collide only by convention would emit disjoint keys, never be serialised against each
	 * other, and both pass a uniqueness check that neither could yet see the other's result - leaving two catalogs
	 * whose names collide, durably.
	 *
	 * **The raw name is included deliberately, and it is not redundant.**
	 * `ClassifierUtils#validateClassifierFormat` admits names no convention reproduces - `Reports_Archive` is
	 * legal and generates none of itself - so a variant-only key set would stop intersecting with the keys of
	 * every catalog-scoped mutation, which claim the literal name. Dropping it trades one race for another.
	 *
	 * **Only for names being introduced.** A name a mutation merely *acts on*, or one it gives up, stays a literal
	 * key: it is already unique by construction, so widening it would serialise the mutation against unrelated
	 * catalogs for no benefit.
	 *
	 * Note the claim is marginally wider than the uniqueness rule rather than identical to it. Uniqueness compares
	 * two names within *one* convention, whereas intersecting key sets also match a variant of one against a
	 * different convention's variant of the other. No pair of names is believed to satisfy the second without the
	 * first, because the conventions render into mutually exclusive shapes - but it is a superset, so do not read
	 * this as licence to answer the uniqueness question with a key lookup.
	 *
	 * @param catalogName name the mutation introduces
	 * @return keys claiming every name that catalog would occupy, without duplicates
	 */
	@Nonnull
	public static Stream<ConflictKey> forIntroducedCatalogName(@Nonnull String catalogName) {
		final Map<NamingConvention, String> variants = NamingConvention.generate(catalogName);
		final Set<String> claimed = CollectionUtils.createLinkedHashSet(variants.size() + 1);
		claimed.add(catalogName);
		claimed.addAll(variants.values());
		return claimed.stream().map(CatalogConflictKey::new);
	}

	/**
	 * Returns a concise, human-readable representation of this conflict key.
	 *
	 * @return non-null string representation
	 */
	@Nonnull
	@Override
	public String toString() {
		return "catalog `" + this.catalogName + '`';
	}

}
