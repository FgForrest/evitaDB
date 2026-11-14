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


package io.evitadb.spi.analysis;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * A step of an analyzer's words phase: it acts on the words one at a time - lowercases, removes, stems or expands
 * them - and keeps their positions, so that word *i* means the same in every field searched together. The compiler
 * builds the step into a chain only for the sides in {@link #getSides()}.
 */
public non-sealed interface FulltextWordsAnalysisStepFactory extends FulltextAnalysisStepFactory {

	/**
	 * Returns the sides the step acts on, at least one. On any other side the step is left out of the chain.
	 *
	 * @return the sides the step acts on
	 */
	@Nonnull
	Set<FulltextAnalysisSide> getSides();

	/**
	 * Declares whether this step participates in preparation of an unfinished query fragment. The fragment is the
	 * typed last word, normalized up to the stemmer's declared surface normalization, before the completion-length
	 * guard and prefix lookup. Stop filters must not remove that fragment while the user is still typing a longer
	 * word (fulltext typo algorithm §6.3), and synonym expansion must not lengthen it and open the guard for a shorter
	 * typed word. Providers implementing those semantics return false. Other normalization steps participate.
	 *
	 * The compiler consults this declaration before {@link #create(Map, FulltextAnalysisContext)}, so an omitted
	 * operation and its resource preparations are never constructed for a fragment. Ordinary index, word and surface
	 * chains remain governed by {@link #getSides()}. The default preserves existing extension-provider behavior.
	 *
	 * @return true to include the step in fragment preparation, false to omit it before creating its operation
	 */
	default boolean participatesInQueryFragment() {
		return true;
	}

	/**
	 * Creates the operation for validated parameters.
	 *
	 * @param parameters the step's parameters, already validated by {@link #validate(Map)}
	 * @param context    the locale the chain is built for and the resources the parameters may name
	 * @return an immutable operation, shared by every chain compiled from the same definition
	 */
	@Nonnull
	FulltextWordsAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	);

}
