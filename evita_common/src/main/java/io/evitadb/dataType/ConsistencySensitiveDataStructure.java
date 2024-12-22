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

package io.evitadb.dataType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * All data structures that are sensitive to data consistency should implement this interface. The interface provides
 * a method to check the consistency of the data structure and return a report about it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ConsistencySensitiveDataStructure {

	/**
	 * Returns up-to-date consistency report of the index instance.
	 *
	 * @return consistency report of the index
	 */
	@Nonnull
	ConsistencyReport getConsistencyReport();

	/**
	 * Describes the consistency state of the index.
	 */
	enum ConsistencyState {
		/**
		 * All data in the index are in consistent state.
		 */
		CONSISTENT,
		/**
		 * Index is operating ok, but some data are not consistent from the user prospective. This state is usually
		 * temporary and should be eventually corrected by new data arriving to the index.
		 */
		INCONSISTENT,
		/**
		 * Index internal state is broken. This state may never happen and if it does, it usually means error in
		 * evitaDB implementation. The only solution is to rebuild the index from scratch.
		 */
		BROKEN
	}

	/**
	 * Describes the consistency state of the data structure.
	 *
	 * @param state  enum signaling the state of the data structure
	 * @param report textual description of the state in English / Markdown format
	 */
	record ConsistencyReport(
		@Nonnull ConsistencyState state,
		@Nullable String report
	) {
	}

}
