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

package io.evitadb.api.query.require;

import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.EnumSet;

/**
 * The `debug` require constraint activates one or more internal debug modes that alter or augment query execution for
 * testing and verification purposes. It is an **internal constraint** and is intentionally not part of the public
 * evitaDB API surface — it should never appear in production queries issued by end clients.
 *
 * The constraint accepts one or more {@link DebugMode} enum values. Multiple modes can be combined; they are
 * collected into an {@link java.util.EnumSet} via {@link #getDebugMode()}.
 *
 * Supported modes and their effects are described in {@link DebugMode}. In summary:
 *
 * - {@link DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} — forces the engine to evaluate all alternative index paths
 *   and asserts they produce identical results; significantly increases query execution time.
 * - {@link DebugMode#VERIFY_POSSIBLE_CACHING_TREES} — forces evaluation of all cacheable sub-tree variants and
 *   verifies that cached and non-cached paths agree on their results.
 * - {@link DebugMode#PREFER_PREFETCHING} — always selects the prefetch strategy when the query allows it, bypassing
 *   the cost-based strategy selector; used to exercise the prefetch code path in integration tests.
 *
 * This class has no `@ConstraintDefinition` annotation because it is excluded from schema-driven API generation.
 *
 * @see DebugMode
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class Debug extends AbstractRequireConstraintLeaf {
	@Serial private static final long serialVersionUID = 5631043212500743575L;

	private Debug(@Nonnull Serializable[] arguments) {
		super(arguments);
	}

	public Debug(@Nonnull DebugMode... debugMode) {
		super(debugMode);
	}

	/**
	 * Returns requested debug modes.
	 */
	@Nonnull
	public EnumSet<DebugMode> getDebugMode() {
		final EnumSet<DebugMode> result = EnumSet.noneOf(DebugMode.class);
		for (Serializable argument : getArguments()) {
			if (argument instanceof DebugMode debugMode) {
				result.add(debugMode);
			}
		}
		return result;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Debug(newArguments);
	}

}
