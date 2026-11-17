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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.math.BigDecimal;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/** Declares the input and output of a fulltext searchability replacement. */
public interface SetAttributeSchemaSearchableMutationDescriptor extends AttributeSchemaMutationDescriptor {
	/**
	 * Complete statement of the searchable scopes. Omitted and empty mean the same - searchable nowhere - because the
	 * mutation turns a missing scope array into an empty one.
	 */
	PropertyDescriptor SEARCHABLE_IN_SCOPES = PropertyDescriptor.builder()
		.name("searchableInScopes")
		.description("""
			Complete statement of the scopes the value is searchable in: a scope not listed ends up not searchable,
			and an omitted or empty list means searchable nowhere.
			""")
		.type(nullable(Scope[].class))
		.build();
	/** Name of the fulltext analyzer; omission keeps the current analyzer. */
	PropertyDescriptor ANALYZER = PropertyDescriptor.builder()
		.name("analyzer")
		.description("""
			Name of the fulltext analyzer that turns the text of the field into index terms - a built-in one (`text`,
			`html-text`) or one declared in the catalog schema; omission keeps the current analyzer.
			""")
		.type(nullable(String.class))
		.build();
	/** Number of words a value of normal length has; omission keeps the current pivot. */
	PropertyDescriptor LENGTH_PIVOT = PropertyDescriptor.builder()
		.name("lengthPivot")
		.description("""
			Number of words a value of normal length has: a word found in a shorter value weighs more, in a longer
			one less. Omission keeps the current length pivot.
			""")
		.type(nullable(Integer.class))
		.build();
	/**
	 * Decimal ranking weight of the field, shared by all scopes.
	 */
	PropertyDescriptor SEARCH_WEIGHT = PropertyDescriptor.builder()
		.name("searchWeight")
		.description("""
			Ranking weight of the field in every scope; it must be non-negative, have at most 100 significant digits
			and a stripped scale between -100 and 100, and fit the engine float range. Zero keeps matches but adds no
			ranking strength. Omission keeps the current weight.
			""")
		.type(nullable(BigDecimal.class))
		.build();

	/** Fulltext mutation output. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetAttributeSchemaSearchableMutation.class)
		.description("Replaces fulltext searchability and optionally updates analysis and ranking settings.")
		.staticProperty(NAME)
		.staticProperty(SEARCHABLE_IN_SCOPES)
		.staticProperty(ANALYZER)
		.staticProperty(LENGTH_PIVOT)
		.staticProperty(SEARCH_WEIGHT)
		.build();
	/** Input form of the mutation: the output without its `mutationType` discriminator. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetAttributeSchemaSearchableMutationInput")
		.build();
}
