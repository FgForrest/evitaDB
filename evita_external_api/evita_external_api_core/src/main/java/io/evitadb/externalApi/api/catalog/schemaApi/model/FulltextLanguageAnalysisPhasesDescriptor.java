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

/** Describes the declared FulltextLanguageAnalysisPhases value without exposing compiled analysis policies. */
public interface FulltextLanguageAnalysisPhasesDescriptor {
	/** ISO 639 code of the language these phases override. */
	PropertyDescriptor LANGUAGE = PropertyDescriptor.builder()
		.name("language")
		.description("ISO 639 code, such as `en`, of the language these phases override.")
		.type(nonNull(String.class))
		.build();
	/** Declared overrides for this language. */
	PropertyDescriptor PHASES = PropertyDescriptor.builder()
		.name("phases")
		.description("Declared overrides for this language.")
		.type(nonNullRef(FulltextAnalysisPhasesDescriptor.THIS))
		.build();
	/** Phase overrides an analyzer declares for one language. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FulltextLanguageAnalysisPhases")
		.description("""
			Phases an analyzer declares for one language. Each phase declared here replaces the analyzer's
			all-languages phase for that language; a `null` phase leaves the all-languages one in effect.
			""")
		.staticProperties(List.of(LANGUAGE, PHASES))
		.build();
	/**
	 * Input form of one language override. The converter refuses an entry without `language` although the input type
	 * lets it be omitted; omitted `phases` declare neither phase.
	 */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputFulltextLanguageAnalysisPhases")
		.staticProperty(PropertyDescriptor.from(LANGUAGE).type(nullable(String.class)).build())
		.staticProperty(PropertyDescriptor.from(PHASES)
			.type(nullableRef(FulltextAnalysisPhasesDescriptor.THIS_INPUT)).build())
		.build();
}
