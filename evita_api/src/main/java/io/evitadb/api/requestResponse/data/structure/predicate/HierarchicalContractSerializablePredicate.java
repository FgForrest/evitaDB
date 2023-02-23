/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This predicate allows limiting hierarchical placement to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchicalContractSerializablePredicate implements SerializablePredicate<HierarchicalPlacementContract> {
	public static final HierarchicalContractSerializablePredicate DEFAULT_INSTANCE = new HierarchicalContractSerializablePredicate();
	@Serial private static final long serialVersionUID = 4001952278899420860L;

	@Override
	public boolean test(@Nonnull HierarchicalPlacementContract hierarchicalPlacementContract) {
		return hierarchicalPlacementContract.exists();
	}

}
