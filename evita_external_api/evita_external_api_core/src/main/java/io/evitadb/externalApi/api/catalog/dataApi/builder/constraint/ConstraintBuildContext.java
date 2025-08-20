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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintTraverseContext;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Local context for constraint building. It is passed down the constraint tree. Each node can create new
 * context for its children if received context from parent is not relevant
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Builder(access = AccessLevel.PRIVATE, toBuilder = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@ToString
@EqualsAndHashCode
public class ConstraintBuildContext implements ConstraintTraverseContext<ConstraintBuildContext> {

	@Nullable private final DataLocator parentDataLocator;
	@Nonnull private final DataLocator dataLocator;

	public ConstraintBuildContext(@Nonnull DataLocator dataLocator) {
		this(null, dataLocator);
	}

	@Nonnull
	public ConstraintBuildContext switchToChildContext(@Nonnull DataLocator childDataLocator) {
		return toBuilder()
			.parentDataLocator(dataLocator())
			.dataLocator(childDataLocator)
			.build();
	}

	/**
	 * Whether building context is currently at the root of constraint and thus doesn't have any parent constraints.
	 */
	public boolean isAtRoot() {
		return this.parentDataLocator == null;
	}

	/**
	 * {@link #dataLocator()} that was used by parent constraint as main data locator. Useful for logic that is based
	 * on difference between current and parent data locator.
	 */
	@Nullable
	public DataLocator parentDataLocator() {
		return this.parentDataLocator;
	}

	/**
	 * Specifies how to get data based on target domain for building constraint tree at current level.
	 */
	@Nonnull
	public DataLocator dataLocator() {
		return this.dataLocator;
	}
}
