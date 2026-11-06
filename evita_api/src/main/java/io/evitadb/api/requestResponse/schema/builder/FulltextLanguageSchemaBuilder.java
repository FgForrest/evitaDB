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


package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.requestResponse.schema.FulltextLanguageSchemaEditor;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.TokenizerAnalysisStep;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Collects the word splitting of one language for
 * {@link InternalCatalogSchemaBuilder#withFulltextLanguage(String, java.util.function.Consumer)}. It starts from
 * {@link AnalysisSteps#standardTokenizer()} with no further splitting.
 */
@NotThreadSafe
final class FulltextLanguageSchemaBuilder implements FulltextLanguageSchemaEditor {
	/**
	 * The tokenizer, the first splitting step.
	 */
	@Nonnull private TokenizerAnalysisStep tokenizer = AnalysisSteps.standardTokenizer();
	/**
	 * Whether {@link AnalysisSteps#wordNumberSplit()} follows the tokenizer.
	 */
	private boolean wordNumberSplit;

	@Nonnull
	@Override
	public FulltextLanguageSchemaEditor tokenizer(@Nonnull TokenizerAnalysisStep tokenizer) {
		Assert.notNull(tokenizer, "Tokenizer must not be null.");
		this.tokenizer = tokenizer;
		return this;
	}

	@Nonnull
	@Override
	public FulltextLanguageSchemaEditor wordNumberSplit() {
		this.wordNumberSplit = true;
		return this;
	}

	/**
	 * Returns the collected splitting steps.
	 *
	 * @return the splitting steps in execution order, the tokenizer first
	 */
	@Nonnull
	AnalysisStep[] toWordSplitting() {
		return this.wordNumberSplit ?
			new AnalysisStep[]{this.tokenizer, AnalysisSteps.wordNumberSplit()} :
			new AnalysisStep[]{this.tokenizer};
	}

}
