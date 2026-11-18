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

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the catalog fulltext analyzer mutation input and capture output. */
public interface ModifyFulltextAnalyzerSchemaMutationDescriptor extends MutationDescriptor {
	/** Shares the description of {@link FulltextAnalyzerSchemaDescriptor#NAME}. */
	PropertyDescriptor NAME = FulltextAnalyzerSchemaDescriptor.NAME;
	/** Shares the description of {@link FulltextAnalyzerSchemaDescriptor#BASED_ON}. */
	PropertyDescriptor BASED_ON = FulltextAnalyzerSchemaDescriptor.BASED_ON;
	/** Shares the description of {@link FulltextAnalyzerSchemaDescriptor#ALL_LANGUAGES}. */
	PropertyDescriptor ALL_LANGUAGES = FulltextAnalyzerSchemaDescriptor.ALL_LANGUAGES;
	/** Shares the description of {@link FulltextAnalyzerSchemaDescriptor#LANGUAGES}. */
	PropertyDescriptor LANGUAGES = FulltextAnalyzerSchemaDescriptor.LANGUAGES;
	/** Shares the description of {@link FulltextAnalyzerSchemaDescriptor#SAMPLES}. */
	PropertyDescriptor SAMPLES = FulltextAnalyzerSchemaDescriptor.SAMPLES;
	/** Capture body of the replacing mutation; the input form below is derived from it. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(ModifyFulltextAnalyzerSchemaMutation.class)
		.description("""
			Replaces the whole definition of a declared analyzer: its base analyzer, its phases for every language, its
			per-language overrides and its author samples. The analyzer must already be declared.
			""")
		.staticProperty(NAME)
		.staticProperty(BASED_ON)
		.staticProperty(ALL_LANGUAGES)
		.staticProperty(LANGUAGES)
		.staticProperty(SAMPLES)
		.build();
	/**
	 * Mutation input; an omitted `allLanguages` leaves both phases undeclared and omitted `languages` declare no
	 * override. Omitted `samples` supplies no author samples.
	 */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("ModifyFulltextAnalyzerSchemaMutationInput")
		.staticProperty(PropertyDescriptor.from(ALL_LANGUAGES)
			.type(nullableRef(FulltextAnalysisPhasesDescriptor.THIS_INPUT)).build())
		.staticProperty(PropertyDescriptor.from(LANGUAGES)
			.type(nullableListRef(FulltextLanguageAnalysisPhasesDescriptor.THIS_INPUT)).build())
		.staticProperty(PropertyDescriptor.from(SAMPLES).type(nullable(String[].class)).build())
		.build();
}
