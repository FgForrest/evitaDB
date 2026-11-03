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

package io.evitadb.api.query;

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;

/**
 * The `inScope` container of any kind - {@link io.evitadb.api.query.filter.FilterInScope},
 * {@link io.evitadb.api.query.order.OrderInScope} or {@link io.evitadb.api.query.require.RequireInScope}. Its children
 * apply only when entities of {@link #getScope()} are processed.
 *
 * The interface lets the rules shared by all three kinds be checked in one place - see
 * {@link QueryUtils#assertNoNestedScopeContainers(Query)}. The kind of the container is its {@link #getType()}.
 *
 * @param <T> the kind of constraint the container belongs to
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface InScopeContainer<T extends Constraint<T>> extends Constraint<T> {

	/**
	 * Returns the scope in which the children of this container apply.
	 *
	 * @return the scope of the container
	 */
	@Nonnull
	Scope getScope();

}
