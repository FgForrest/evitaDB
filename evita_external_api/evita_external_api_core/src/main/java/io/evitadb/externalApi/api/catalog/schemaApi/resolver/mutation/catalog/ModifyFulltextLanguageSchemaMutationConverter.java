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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.analysis.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextLanguageSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors.Context;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.AnalysisStepConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.FulltextSchemaConverter;
import io.evitadb.externalApi.api.resolver.mutation.*;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts the mutation that replaces a catalog fulltext language between API input and capture output.
 *
 * An omitted `wordSplitting` is read as an empty chain, which the schema then refuses because a language needs at
 * least its tokenizer. The capture output pre-sets the steps in the shared `step`/`parameters` shape, so the
 * reflective serialization of the remaining properties never reaches the step objects.
 */
public class ModifyFulltextLanguageSchemaMutationConverter
	extends LocalCatalogSchemaMutationConverter<ModifyFulltextLanguageSchemaMutation> {
	/**
	 * Uses the enclosing API's object mapping and diagnostics.
	 *
	 * @param objectParser mapping between transport objects and ordinary values
	 * @param exceptionFactory API-specific mutation errors
	 */
	public ModifyFulltextLanguageSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser, @Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<ModifyFulltextLanguageSchemaMutation> getMutationClass() {
		return ModifyFulltextLanguageSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected ModifyFulltextLanguageSchemaMutation convertFromInput(@Nonnull Input input) {
		final AnalysisStep[] steps = FulltextSchemaConverter.readSteps(
			input, "wordSplitting", Context.WORD_SPLITTING, getExceptionFactory());
		return new ModifyFulltextLanguageSchemaMutation(input.getRequiredProperty("language", String.class),
			steps == null ? new AnalysisStep[0] : steps);
	}

	@Override
	protected void convertToOutput(@Nonnull ModifyFulltextLanguageSchemaMutation mutation, @Nonnull Output output) {
		output.setProperty("wordSplitting", AnalysisStepConverter.toOutput(Arrays.asList(mutation.getWordSplitting())));
		super.convertToOutput(mutation, output);
	}
}
