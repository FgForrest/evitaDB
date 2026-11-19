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

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the declared FulltextAnalysisPhases value without exposing compiled analysis policies. */
public interface FulltextAnalysisPhasesDescriptor {
	/** Text preprocessing; absent inherits, empty overrides with no steps. */
	PropertyDescriptor TEXT = PropertyDescriptor.builder()
		.name("text")
		.description("Text preprocessing; absent inherits, empty overrides with no steps.")
		.type(nullableListRef(AnalysisStepDescriptor.THIS))
		.build();
	/** Word transformations; absent inherits, empty overrides with no steps. */
	PropertyDescriptor WORDS = PropertyDescriptor.builder()
		.name("words")
		.description("Word transformations; absent inherits, empty overrides with no steps.")
		.type(nullableListRef(AnalysisStepDescriptor.THIS))
		.build();
	/** The two phases one analyzer declares, for every language or for one. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FulltextAnalysisPhases")
		.description("""
			The two phases of an analyzer as it declares them, for every language or for one language. The text phase
			changes characters before the text is split into words, the words phase changes each word after splitting.
			A `null` phase is not declared here and is inherited; an empty list declares the phase with no steps.
			""")
		.staticProperties(List.of(TEXT, WORDS))
		.build();
	/** Input form of the phases; an omitted phase is read as `null`, so it stays undeclared and is inherited. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputFulltextAnalysisPhases")
		.staticProperty(PropertyDescriptor.from(TEXT).type(nullableListRef(
			AnalysisStepInputDescriptors.getInputDescriptor(AnalysisStepInputDescriptors.Context.TEXT))).build())
		.staticProperty(PropertyDescriptor.from(WORDS).type(nullableListRef(
			AnalysisStepInputDescriptors.getInputDescriptor(AnalysisStepInputDescriptors.Context.WORDS))).build())
		.build();
}
