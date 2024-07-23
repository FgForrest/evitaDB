/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Base object for holding context when traversing constraint tree to create another form from it (e.g. schema generation,
 * conversion, ...).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ConstraintTraverseContext<T extends ConstraintTraverseContext<T>> {

	/**
	 * {@link #dataLocator()} that was used by parent constraint as main data locator. Useful for logic that is based
	 * on difference between current and parent data locator.
	 */
	@Nullable
	DataLocator parentDataLocator();

	/**
	 * Specifies how to get data based on target domain for building constraint tree at current level.
	 */
	@Nonnull
	DataLocator dataLocator();

	/**
	 * Whether building context is currently at the root of constraint and thus doesn't have any parent constraints.
	 */
	default boolean isAtRoot() {
		return parentDataLocator() == null;
	}

	/**
	 * Creates copy of this context with new child data locator and current data locator as parent data locator.
	 */
	@Nonnull
	T switchToChildContext(@Nonnull DataLocator childDataLocator);
}
