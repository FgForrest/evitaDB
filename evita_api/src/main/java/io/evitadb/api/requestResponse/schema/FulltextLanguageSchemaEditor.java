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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;

import javax.annotation.Nonnull;

/**
 * Defines how one language splits text into words, inside
 * {@link CatalogSchemaEditor#withFulltextLanguage(String, java.util.function.Consumer)}. The definition starts from
 * {@link AnalysisSteps#standardTokenizer()} with no further splitting; each call changes it and the result replaces
 * the language's previous definition as a whole.
 */
public interface FulltextLanguageSchemaEditor {

	/**
	 * Replaces the tokenizer, the first splitting step.
	 *
	 * @param tokenizer description of the tokenizer step, such as {@link AnalysisSteps#standardTokenizer()}
	 * @return this editor
	 */
	@Nonnull
	FulltextLanguageSchemaEditor tokenizer(@Nonnull AnalysisStep tokenizer);

	/**
	 * Adds {@link AnalysisSteps#wordNumberSplit()} after the tokenizer: each word is also split at its letter/digit
	 * boundaries, keeping the original word. Adding it twice has no further effect.
	 *
	 * @return this editor
	 */
	@Nonnull
	FulltextLanguageSchemaEditor wordNumberSplit();

}
