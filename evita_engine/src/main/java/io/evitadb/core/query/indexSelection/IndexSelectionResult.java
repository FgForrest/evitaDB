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

package io.evitadb.core.query.indexSelection;

import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * This simple DTO object encapsulates set of {@link TargetIndexes}.
 *
 * @param targetIndexes Collection contains all alternative {@link TargetIndexes} sets that might already contain precalculated information
 *                      related to {@link EntityIndex} that can be used to partially resolve input filter although the target index set
 *                      is not used to resolve entire query filter.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record IndexSelectionResult<T extends Index<?>>(
	@Nonnull List<TargetIndexes<T>> targetIndexes
) {
	/**
	 * Returns true if this DTO contains NO reference to the target indexes.
	 */
	public boolean isEmpty() {
		return this.targetIndexes.isEmpty() || this.targetIndexes.stream().anyMatch(TargetIndexes::isEmpty);
	}

}
