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

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/**
 * Defines a complete replacement of an attribute's declared normalization chain.
 * Entity, reference and global mutations share the same statement. Empty input removes custom steps, while
 * populated indexes can continue using their separately retained built-with policy until reindexing.
 */
public interface SetAttributeSchemaNormalizedMutationDescriptor extends AttributeSchemaMutationDescriptor {
	/** Ordered output descriptions; omission and an empty list both clear the declaration. */
	PropertyDescriptor NORMALIZATION = PropertyDescriptor.builder()
		.name("normalization")
		.description("""
			Ordered declared normalization steps for String or String-array keys. Omitting the list or supplying an
			empty list removes normalization beyond Unicode NFD. Stored values remain unchanged. Populated indexes
			retain their built-with policy until reindexing; this mutation updates the declaration.
			""")
		.type(nullableListRef(AnalysisStepDescriptor.THIS))
		.build();
	/** Typed installed-provider selectors accepted by mutation inputs. */
	PropertyDescriptor NORMALIZATION_INPUT = PropertyDescriptor.from(NORMALIZATION)
		.type(nullableListRef(AnalysisStepDescriptor.THIS_INPUT))
		.build();
	/** Mutation output shape carrying the attribute name and complete replacement chain. */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetAttributeSchemaNormalizedMutation.class)
		.description("Replaces the declared normalization chain of an entity, reference or global attribute.")
		.staticProperty(NAME)
		.staticProperty(NORMALIZATION)
		.build();
	/** Writable mutation shape using the typed selector rather than the output step object. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetAttributeSchemaNormalizedMutationInput")
		.staticProperty(NORMALIZATION_INPUT)
		.build();
}
