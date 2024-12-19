/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.algebra.prefetch;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Prefetch factory is used to create an implementation that might prefetch entities for particular query context if
 * it looks like it would be beneficial. The prefetching is not always beneficial and it is not always possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface PrefetcherFactory {
	/**
	 * Implementation that always returns empty optional (avoids prefetching).
	 */
	PrefetcherFactory NO_OP = Optional::empty;

	/**
	 * Method will prefetch the entities identified in {@link PrefetchFormulaVisitor} but only in case the prefetch
	 * is possible and would "pay off". In case the possible prefetching would be more costly than executing the standard
	 * filtering logic, the prefetch is not executed.
	 */
	@Nonnull
	Optional<PrefetchOrder> createPrefetcherIfNeededOrWorthwhile();

}
