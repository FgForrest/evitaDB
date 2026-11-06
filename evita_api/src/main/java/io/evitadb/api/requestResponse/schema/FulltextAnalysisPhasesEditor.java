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


package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.analysis.TextAnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.WordsAnalysisStep;

import javax.annotation.Nonnull;

/**
 * Defines the phases of a fulltext analyzer for every language or for one, inside
 * {@link FulltextAnalyzerSchemaEditor#allLanguages(java.util.function.Consumer)} and
 * {@link FulltextAnalyzerSchemaEditor#language(String, java.util.function.Consumer)}. Each call replaces the whole
 * phase; a phase never called stays undefined and is inherited (see {@link FulltextAnalysisPhases}).
 */
public interface FulltextAnalysisPhasesEditor {

	/**
	 * Replaces the text phase - the character changes made before the text is split into words.
	 *
	 * @param steps the steps in execution order; none defines the phase as empty
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalysisPhasesEditor text(@Nonnull TextAnalysisStep... steps);

	/**
	 * Replaces the words phase - the changes made to each word after splitting.
	 *
	 * @param steps the steps in execution order; none defines the phase as empty
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalysisPhasesEditor words(@Nonnull WordsAnalysisStep... steps);

}
