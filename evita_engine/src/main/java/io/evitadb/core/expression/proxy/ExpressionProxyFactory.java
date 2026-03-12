/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.query.expression.visitor.AccessedDataFinder;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.core.expression.proxy.PathToPartialMapper.MappingResult;
import io.evitadb.dataType.expression.ExpressionNode;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Static utility that orchestrates the full pipeline from an expression tree to an {@link ExpressionProxyDescriptor}.
 *
 * The factory performs two steps:
 *
 * 1. **Path extraction** — delegates to {@link AccessedDataFinder#findAccessedPaths(ExpressionNode)} to statically
 *    analyze which entity/reference data the expression accesses
 * 2. **Partial mapping** — delegates to {@link PathToPartialMapper#map(List)} to translate the accessed paths into
 *    composable proxy partials and a storage part fetch recipe
 *
 * The resulting {@link ExpressionProxyDescriptor} is an immutable schema-load-time artifact that can be cached and
 * reused across all entity evaluations sharing the same expression.
 */
public final class ExpressionProxyFactory {

	/**
	 * Builds a proxy descriptor from the given expression tree. The descriptor contains pre-composed partial arrays
	 * and a storage part recipe suitable for trigger-time proxy instantiation.
	 *
	 * @param expression the expression tree to analyze
	 * @return an immutable descriptor with partial arrays and recipe
	 */
	@Nonnull
	public static ExpressionProxyDescriptor buildDescriptor(@Nonnull ExpressionNode expression) {
		final List<List<PathItem>> paths = AccessedDataFinder.findAccessedPaths(expression);
		final MappingResult result = PathToPartialMapper.map(paths);
		return new ExpressionProxyDescriptor(
			result.entityPartials(),
			result.referencePartials(),
			result.entityRecipe(),
			result.needsReferencedEntityProxy(),
			result.needsGroupEntityProxy(),
			result.referencedEntityPartials(),
			result.groupEntityPartials(),
			result.referencedEntityRecipe(),
			result.groupEntityRecipe()
		);
	}

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private ExpressionProxyFactory() {
		// utility class
	}
}
