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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceSearchModeDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.math.BigDecimal;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/** Declares replacement composition modes and an optional shared ranking weight. */
public interface SetReferenceSchemaSearchableThroughMutationDescriptor extends ReferenceSchemaMutationDescriptor {
	/** Null keeps the declaration; an empty list removes all composition modes. */
	PropertyDescriptor SEARCHABLE_THROUGH_IN_SCOPES = PropertyDescriptor.builder()
		.name("searchableThroughInScopes")
		.description("""
			Complete replacement of the composition modes: a scope not listed is not searched through. Omission keeps
			the current modes and an empty list removes them all.
			""")
		.type(nullableListRef(ScopedReferenceSearchModeDescriptor.THIS))
		.build();
	/** Input variant of {@link #SEARCHABLE_THROUGH_IN_SCOPES}, built from the writable scoped declaration. */
	PropertyDescriptor SEARCHABLE_THROUGH_IN_SCOPES_INPUT = PropertyDescriptor.from(SEARCHABLE_THROUGH_IN_SCOPES)
		.type(nullableListRef(ScopedReferenceSearchModeDescriptor.THIS_INPUT))
		.build();
	/**
	 * Ranking strength of text composed through the reference, shared by all scopes; null keeps the current weight,
	 * so zero must stay distinct from omission.
	 */
	PropertyDescriptor WEIGHT = PropertyDescriptor.builder()
		.name("weight")
		.description("""
			Ranking strength of text composed through the reference, shared by all scopes; it must be non-negative,
			have at most 100 significant digits and a stripped scale between -100 and 100, and fit the engine float
			range. Zero keeps matches but adds no ranking strength. Omission keeps the current weight.
			""")
		.type(nullable(BigDecimal.class))
		.build();
	/** Mutation output. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetReferenceSchemaSearchableThroughMutation.class)
		.description("Changes reference fulltext composition modes and their shared ranking weight.")
		.staticProperty(NAME)
		.staticProperty(SEARCHABLE_THROUGH_IN_SCOPES)
		.staticProperty(WEIGHT)
		.build();
	/** Optional input settings preserve existing declarations when omitted. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetReferenceSchemaSearchableThroughMutationInput")
		.staticProperty(SEARCHABLE_THROUGH_IN_SCOPES_INPUT)
		.build();
}
