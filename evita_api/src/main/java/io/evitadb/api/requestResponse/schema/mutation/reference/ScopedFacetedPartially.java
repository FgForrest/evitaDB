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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Associates a {@link Scope} with a nullable {@link Expression} that narrows
 * which entities participate in faceting for that scope. Only meaningful
 * for scopes where the reference is marked as faceted.
 *
 * When the expression is null, it means the facetedPartially constraint
 * is being cleared for the given scope.
 *
 * @param scope      the scope this partial-faceting expression applies to
 * @param expression the expression narrowing facet participation, or null to clear
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ScopedFacetedPartially(
	@Nonnull Scope scope,
	@Nullable Expression expression
) implements Serializable {
	/**
	 * Reusable empty array constant to avoid repeated zero-length array allocations.
	 */
	public static final ScopedFacetedPartially[] EMPTY = new ScopedFacetedPartially[0];

	/**
	 * Compact constructor that validates the scope is not null.
	 */
	public ScopedFacetedPartially {
		Assert.notNull(scope, "Scope must not be null");
	}

}
