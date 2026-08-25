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

package io.evitadb.index.fulltext.analysis;

import javax.annotation.Nonnull;

/**
 * The three places an analysis chain can be plugged into. See {@link AnalyzerAssignment} for why the phrase slot
 * is separated from the query slot even though nothing distinguishes them yet.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum AnalyzerSlot {

	/**
	 * Analyses a stored attribute value on the write path.
	 */
	INDEX(AnalysisMode.INDEX_TIME),
	/**
	 * Analyses the text of a query.
	 */
	SEARCH(AnalysisMode.SEARCH_TIME),
	/**
	 * Analyses the text of a phrase query — a chain that drops stop words is not usable here.
	 */
	PHRASE(AnalysisMode.SEARCH_TIME);

	/**
	 * Mode an analyzer has to be compatible with to be usable in this slot.
	 */
	@Nonnull private final AnalysisMode requiredMode;

	AnalyzerSlot(@Nonnull AnalysisMode requiredMode) {
		this.requiredMode = requiredMode;
	}

	/**
	 * Returns the mode an analyzer has to be compatible with to be usable in this slot.
	 *
	 * @return required analysis mode
	 */
	@Nonnull
	public AnalysisMode getRequiredMode() {
		return this.requiredMode;
	}

}
