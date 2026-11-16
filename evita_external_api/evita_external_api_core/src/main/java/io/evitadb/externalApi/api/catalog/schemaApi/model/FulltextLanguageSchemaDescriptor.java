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

/** Describes the declared FulltextLanguageSchema value without exposing compiled analysis policies. */
public interface FulltextLanguageSchemaDescriptor {
	/** ISO 639 code of the language whose text the splitting applies to. */
	PropertyDescriptor LANGUAGE = PropertyDescriptor.builder()
		.name("language")
		.description("ISO 639 code, such as `en`, of the language whose text this splitting applies to.")
		.type(nonNull(String.class))
		.build();
	/** Tokenizer followed by further splitting steps in execution order. */
	PropertyDescriptor WORD_SPLITTING = PropertyDescriptor.builder()
		.name("wordSplitting")
		.description("Tokenizer followed by further splitting steps in execution order.")
		.type(nonNullListRef(AnalysisStepDescriptor.THIS))
		.build();
	/** How text in one language is split into words. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FulltextLanguageSchema")
		.description("""
			How text in one language is split into words: a tokenizer followed by further splitting steps. Text in a
			language the catalog declares nothing for is split by the `standard` tokenizer alone.
			""")
		.staticProperties(List.of(LANGUAGE, WORD_SPLITTING))
		.build();
}
