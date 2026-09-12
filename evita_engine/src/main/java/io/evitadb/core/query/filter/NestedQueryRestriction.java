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

package io.evitadb.core.query.filter;

import io.evitadb.api.query.FilterConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Narrowing that a caller installs on the {@link FilterByVisitor.ProcessingScope} so that a nested query planned for
 * an {@link io.evitadb.api.query.filter.EntityHaving} constraint is evaluated over the handful of keys the owners
 * actually reference instead of the whole target collection.
 *
 * The restriction is a set of primary keys, and primary keys only mean anything inside one collection. It therefore
 * carries the two names that say which nested query it belongs to, and
 * {@link #applyTo(String, String, FilterConstraint)} is the only way to reach the narrowing - a nested query planned
 * for anything else gets its filter back untouched. Both names matter: `groupHaving` plans its nested query against
 * the reference's *group* collection, whose primary keys live in an unrelated universe, and a nested
 * {@link io.evitadb.api.query.filter.ReferenceHaving} switches the reference in scope while the restriction stays
 * installed, so two references pointing at the same collection must still be told apart.
 *
 * @param referenceName    name of the reference whose referenced keys the narrowing was built from
 * @param targetEntityType entity type the narrowing's keys belong to
 * @param narrowing        transformation to apply to a matching nested query's filter
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record NestedQueryRestriction(
	@Nonnull String referenceName,
	@Nonnull String targetEntityType,
	@Nonnull Function<FilterConstraint, FilterConstraint> narrowing
) {

	/**
	 * Applies the narrowing when the nested query being planned is the one this restriction was built for, and
	 * returns the filter unchanged otherwise. Passing it through is the pre-existing behaviour of an unrestricted
	 * plan, so a mismatch is a plain no-op rather than an error.
	 *
	 * @param referenceName    reference currently in scope, NULL when the nested query is not planned for a reference
	 * @param targetEntityType entity type the nested query will be planned against
	 * @param nestedFilter     filter of the nested query
	 * @return the narrowed filter, or `nestedFilter` when this restriction does not apply
	 */
	@Nonnull
	public FilterConstraint applyTo(
		@Nullable String referenceName,
		@Nonnull String targetEntityType,
		@Nonnull FilterConstraint nestedFilter
	) {
		return this.referenceName.equals(referenceName) && this.targetEntityType.equals(targetEntityType) ?
			this.narrowing.apply(nestedFilter) : nestedFilter;
	}

}
