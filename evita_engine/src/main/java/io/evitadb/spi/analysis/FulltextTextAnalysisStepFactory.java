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
 * A step of an analyzer's text phase: it acts on the whole text before the text is split into words, as markup
 * stripping does. The compiler builds the step into a chain only for the sides in {@link #getSides()}.
 */
public non-sealed interface FulltextTextAnalysisStepFactory extends FulltextAnalysisStepFactory {

	/**
	 * Returns the sides the step acts on, at least one. On any other side the step is left out of the chain.
	 *
	 * @return the sides the step acts on
	 */
	@Nonnull
	Set<FulltextAnalysisSide> getSides();

	/**
	 * Creates the operation for validated parameters.
	 *
	 * @param parameters the step's parameters, already validated by {@link #validate(Map)}
	 * @param context    the locale the chain is built for and the resources the parameters may name
	 * @return an immutable operation, shared by every chain compiled from the same definition
	 */
	@Nonnull
	FulltextTextAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	);

}
