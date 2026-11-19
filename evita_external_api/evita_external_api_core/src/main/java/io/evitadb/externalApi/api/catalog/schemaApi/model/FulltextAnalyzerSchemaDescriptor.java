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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.*;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the declared FulltextAnalyzerSchema value without exposing compiled analysis policies. */
public interface FulltextAnalyzerSchemaDescriptor {
	/** Name searchable fields refer to the analyzer by; built-in names are reserved. */
	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			Name searchable fields refer to the analyzer by. The names of the built-in analyzers, `text` and
			`html-text`, are reserved.
			""")
		.type(nonNull(String.class))
		.build();
	/** Analyzer supplying every phase this one leaves undefined, or `null` when it is based on none. */
	PropertyDescriptor BASED_ON = PropertyDescriptor.builder()
		.name("basedOn")
		.description("""
			Analyzer supplying every phase this one leaves undefined: a built-in one (`text`, `html-text`) or one
			the catalog declares. `null` when the analyzer is based on none; its undefined phases then have no steps.
			""")
		.type(nullable(String.class))
		.build();
	/** Declared phases for every language before inheritance. */
	PropertyDescriptor ALL_LANGUAGES = PropertyDescriptor.builder()
		.name("allLanguages")
		.description("Declared phases for every language before inheritance.")
		.type(nonNullRef(FulltextAnalysisPhasesDescriptor.THIS))
		.build();
	/** Declared per-language phase overrides. */
	PropertyDescriptor LANGUAGES = PropertyDescriptor.builder()
		.name("languages")
		.description("Declared per-language phase overrides.")
		.type(nonNullListRef(FulltextLanguageAnalysisPhasesDescriptor.THIS))
		.build();
	/** Author samples of this declaration, without inheritance from the base analyzer. */
	PropertyDescriptor SAMPLES = PropertyDescriptor.builder()
		.name("samples")
		.description("Author samples in declaration order, independent of the base analyzer; at most 100 distinct, " +
			"non-blank texts of at most 1,000 UTF-16 code units each. Empty when none were supplied.")
		.type(nonNull(String[].class))
		.build();
	/** A named analyzer as the catalog declares it, before inheritance is resolved. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FulltextAnalyzerSchema")
		.description("""
			A named analyzer as the catalog declares it, before inheritance is resolved: the phases it declares for
			every language, its per-language overrides, the analyzer it is based on and its author samples.
			""")
		.staticProperties(List.of(NAME, BASED_ON, ALL_LANGUAGES, LANGUAGES, SAMPLES))
		.build();
}
