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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextLanguageSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the catalog fulltext language mutation input and capture output. */
public interface ModifyFulltextLanguageSchemaMutationDescriptor extends MutationDescriptor {
	/** Shares the description of {@link FulltextLanguageSchemaDescriptor#LANGUAGE}. */
	PropertyDescriptor LANGUAGE = FulltextLanguageSchemaDescriptor.LANGUAGE;
	/** Shares the description of {@link FulltextLanguageSchemaDescriptor#WORD_SPLITTING}. */
	PropertyDescriptor WORD_SPLITTING = FulltextLanguageSchemaDescriptor.WORD_SPLITTING;
	/** Capture body of the replacing mutation; the input form below is derived from it. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(ModifyFulltextLanguageSchemaMutation.class)
		.description("""
			Replaces the whole word splitting of a declared language. The language must already be declared.
			""")
		.staticProperty(LANGUAGE)
		.staticProperty(WORD_SPLITTING)
		.build();
	/**
	 * Mutation input; an omitted `wordSplitting` is read as an empty chain, which the schema refuses for lacking a
	 * tokenizer.
	 */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("ModifyFulltextLanguageSchemaMutationInput")
		.staticProperty(PropertyDescriptor.from(WORD_SPLITTING).type(nullableListRef(
			AnalysisStepInputDescriptors.getInputDescriptor(AnalysisStepInputDescriptors.Context.WORD_SPLITTING)))
			.build())
		.build();
}
