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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData;

import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaSearchableMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/** Declares the input and output of a fulltext searchability replacement. */
public interface SetAssociatedDataSchemaSearchableMutationDescriptor extends AssociatedDataSchemaMutationDescriptor {
	/** Shared fulltext mutation property. */
	PropertyDescriptor SEARCHABLE_IN_SCOPES = SetAttributeSchemaSearchableMutationDescriptor.SEARCHABLE_IN_SCOPES;
	/** Shared fulltext mutation property. */
	PropertyDescriptor ANALYZER = SetAttributeSchemaSearchableMutationDescriptor.ANALYZER;
	/** Shared fulltext mutation property. */
	PropertyDescriptor LENGTH_PIVOT = SetAttributeSchemaSearchableMutationDescriptor.LENGTH_PIVOT;
	/** Shared fulltext mutation property. */
	PropertyDescriptor SEARCH_WEIGHT = SetAttributeSchemaSearchableMutationDescriptor.SEARCH_WEIGHT;
	/**
	 * Raw source of the expression selecting the text of a complex data object value. It is part of the complete
	 * statement, so omission removes an expression declared before.
	 */
	PropertyDescriptor TEXT_EXPRESSION = PropertyDescriptor.builder()
		.name("textExpression")
		.description("""
			Expression selecting the text of a complex data object value, as raw source. It belongs to the complete
			statement together with the scopes: omission means no expression and removes one declared before.
			""")
		.type(nullable(String.class))
		.build();

	/** Fulltext mutation output. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetAssociatedDataSchemaSearchableMutation.class)
		.description("""
			Replaces the fulltext searchability of associated data - its searchable scopes and text expression - and
			optionally updates its analysis and ranking settings.
			""")
		.staticProperty(NAME)
		.staticProperty(SEARCHABLE_IN_SCOPES)
		.staticProperty(ANALYZER)
		.staticProperty(LENGTH_PIVOT)
		.staticProperty(SEARCH_WEIGHT)
		.staticProperty(TEXT_EXPRESSION)
		.build();
	/** Input form of the mutation: the output without its `mutationType` discriminator. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetAssociatedDataSchemaSearchableMutationInput")
		.build();
}
