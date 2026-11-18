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
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.FulltextSchemaConverter;
import io.evitadb.externalApi.api.resolver.mutation.*;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Converts the mutation that declares a catalog fulltext analyzer between API input and capture output.
 *
 * The phases are read and written as declared, before inheritance from the base analyzer is resolved: an omitted
 * phase stays `null` and is inherited, an empty list declares it with no steps. The capture output pre-sets the
 * phases in the shared step shape, so the reflective serialization of the remaining properties never reaches them.
 */
public class CreateFulltextAnalyzerSchemaMutationConverter
	extends LocalCatalogSchemaMutationConverter<CreateFulltextAnalyzerSchemaMutation> {
	/**
	 * Uses the enclosing API's object mapping and diagnostics.
	 *
	 * @param objectParser mapping between transport objects and ordinary values
	 * @param exceptionFactory API-specific mutation errors
	 */
	public CreateFulltextAnalyzerSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser, @Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<CreateFulltextAnalyzerSchemaMutation> getMutationClass() {
		return CreateFulltextAnalyzerSchemaMutation.class;
	}

	/**
	 * Reads the complete declaration. An omitted or `null` sample list means no samples. A `null` entry is refused
	 * here as an invalid argument, because the generic array conversion would report it as an internal error; the
	 * mutation constructor then refuses blank, too long and too many samples.
	 *
	 * @param input the API mutation input
	 * @return the mutation with validated, ordered samples
	 */
	@Nonnull
	@Override
	protected CreateFulltextAnalyzerSchemaMutation convertFromInput(@Nonnull Input input) {
		final Object rawSamples = input.getOptionalProperty("samples");
		if (rawSamples instanceof final Collection<?> samples && samples.stream().anyMatch(Objects::isNull)) {
			throw getExceptionFactory().createInvalidArgumentException(
				"Fulltext analyzer samples must not contain null entries."
			);
		}
		return new CreateFulltextAnalyzerSchemaMutation(input.getRequiredProperty("name", String.class),
			input.getOptionalProperty("basedOn", String.class),
			input.getOptionalProperty("allLanguages", new PropertyObjectMapper<>(
				getMutationName(), getExceptionFactory(), "allLanguages",
				nested -> FulltextSchemaConverter.readPhases(nested, getExceptionFactory())),
				FulltextAnalysisPhases.UNDEFINED),
			FulltextSchemaConverter.readLanguages(input, getExceptionFactory()),
			Arrays.asList(input.getOptionalProperty("samples", String[].class, new String[0])));
	}

	/**
	 * Writes phases and author samples before reflective mutation serialization.
	 *
	 * @param mutation the Java declaration carried by the capture
	 * @param output the transport output receiving the declaration
	 */
	@Override
	protected void convertToOutput(@Nonnull CreateFulltextAnalyzerSchemaMutation mutation, @Nonnull Output output) {
		output.setProperty("allLanguages", FulltextSchemaConverter.phases(mutation.getAllLanguages()));
		output.setProperty("languages", FulltextSchemaConverter.languages(mutation.getLanguages()));
		output.setProperty("samples", mutation.getSamples());
		super.convertToOutput(mutation, output);
	}
}
