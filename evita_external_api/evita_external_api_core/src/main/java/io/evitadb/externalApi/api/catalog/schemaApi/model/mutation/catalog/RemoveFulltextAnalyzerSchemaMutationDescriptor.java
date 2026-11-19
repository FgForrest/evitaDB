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

import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;


/** Describes the catalog fulltext analyzer mutation input and capture output. */
public interface RemoveFulltextAnalyzerSchemaMutationDescriptor extends MutationDescriptor {
	/** Shares the description of {@link FulltextAnalyzerSchemaDescriptor#NAME}. */
	PropertyDescriptor NAME = FulltextAnalyzerSchemaDescriptor.NAME;
	/** Capture body of the removing mutation; the input form below is derived from it. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(RemoveFulltextAnalyzerSchemaMutation.class)
		.description("""
			Removes a declared analyzer. Removing one that is not declared changes nothing. References to the
			analyzer are checked on the schema the whole update produces, so one update may re-point them and remove
			the analyzer together.
			""")
		.staticProperty(NAME)
		.build();
	/** Mutation input naming the analyzer to remove. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("RemoveFulltextAnalyzerSchemaMutationInput")
		.build();
}
