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

package io.evitadb.core.query.fetch;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The filter of the fetched references (`referenceContent(..., filterBy(...))`) compiled into a test of a single
 * reference row of a single owner entity, built by {@link ReferenceRowFilterCompiler} once per fetch and reference.
 *
 * The filter judges every row by itself: the verdict depends on the owner's scope and locales, the row, and the sets
 * the nested `entityHaving` / `groupHaving` filters select - never on the other owners fetched along, and never on an
 * index of the reference. A filter without a condition on the row - none at all, or one consisting of a `scope(...)`
 * only - accepts every row.
 *
 * The filter does not decide whether the row exists or whether its target does - the caller tests only existing
 * rows whose target passed the existence check of the fetch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReferenceRowFilter {
	/**
	 * The filter accepting every row.
	 */
	static final ReferenceRowFilter ACCEPT_ALL = new ReferenceRowFilter(null);
	/**
	 * The root of the compiled tree, NULL when the filter has no condition on the row.
	 */
	@Nullable private final RowPredicate root;

	/**
	 * Creates the filter over the compiled tree.
	 *
	 * @param root the root of the compiled tree, NULL when the filter has no condition on the row
	 */
	ReferenceRowFilter(@Nullable RowPredicate root) {
		this.root = root;
	}

	/**
	 * Tests one reference row of an owner entity.
	 *
	 * @param ownerScope the scope of the owner entity
	 * @param owner      the owner entity - the raw entity, never a decorator hiding part of its references or their
	 *                   attributes
	 * @param row        the existing reference row of the owner entity
	 * @return true when the filter admits the row
	 */
	boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
		return this.root == null || this.root.test(ownerScope, owner, row);
	}

}
