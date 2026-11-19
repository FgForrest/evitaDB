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

import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextResourceSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.FulltextSchemaConverter;
import io.evitadb.externalApi.api.resolver.mutation.*;

import javax.annotation.Nonnull;

/**
 * Converts the mutation that declares a catalog fulltext resource between API input and capture output.
 *
 * The capture output pre-sets the content as its `kind` plus the matching `words` or `groups`, so the reflective
 * serialization of the remaining properties never reaches the sealed content records.
 */
public class CreateFulltextResourceSchemaMutationConverter
	extends LocalCatalogSchemaMutationConverter<CreateFulltextResourceSchemaMutation> {
	/**
	 * Uses the enclosing API's object mapping and diagnostics.
	 *
	 * @param objectParser mapping between transport objects and ordinary values
	 * @param exceptionFactory API-specific mutation errors
	 */
	public CreateFulltextResourceSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser, @Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<CreateFulltextResourceSchemaMutation> getMutationClass() {
		return CreateFulltextResourceSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected CreateFulltextResourceSchemaMutation convertFromInput(@Nonnull Input input) {
		return new CreateFulltextResourceSchemaMutation(input.getRequiredProperty("name", String.class),
			input.getRequiredProperty("content", new PropertyObjectMapper<>(
				getMutationName(), getExceptionFactory(), "content",
				nested -> FulltextSchemaConverter.readContent(nested, getExceptionFactory()))));
	}

	@Override
	protected void convertToOutput(@Nonnull CreateFulltextResourceSchemaMutation mutation, @Nonnull Output output) {
		output.setProperty("content", FulltextSchemaConverter.content(mutation.getContent()));
		super.convertToOutput(mutation, output);
	}
}
