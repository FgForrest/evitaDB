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

import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextResourceSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the catalog fulltext resource mutation input and capture output. */
public interface CreateFulltextResourceSchemaMutationDescriptor extends MutationDescriptor {
	/** Shares the description of {@link FulltextResourceSchemaDescriptor#NAME}. */
	PropertyDescriptor NAME = FulltextResourceSchemaDescriptor.NAME;
	/** Shares the description of {@link FulltextResourceSchemaDescriptor#CONTENT}. */
	PropertyDescriptor CONTENT = FulltextResourceSchemaDescriptor.CONTENT;
	/** Capture body of the declaring mutation; the input form below is derived from it. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(CreateFulltextResourceSchemaMutation.class)
		.description("""
			Declares a named word list or synonym list that analysis steps read. Applying it again with the same
			content changes nothing; a resource already declared with different content is refused - modify it
			instead.
			""")
		.staticProperty(NAME)
		.staticProperty(CONTENT)
		.build();
	/** Mutation input; the converter refuses an input without `content` although the input type lets it be omitted. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("CreateFulltextResourceSchemaMutationInput")
		.staticProperty(PropertyDescriptor.from(CONTENT)
			.type(nullableRef(FulltextResourceContentDescriptor.THIS_INPUT)).build())
		.build();
}
