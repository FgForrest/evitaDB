/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for {@link io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract}.
 *
 * Note: this descriptor is static.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SortableAttributeCompoundSchemaDescriptor extends NamedSchemaWithDeprecationDescriptor {

	PropertyDescriptor ATTRIBUTE_ELEMENTS = PropertyDescriptor.builder()
		.name("attributeElements")
		.description("""
			Collection of attribute elements that define the sortable compound. The order of the elements
			is important, as it defines the order of the sorting.
			""")
		.type(nonNullListRef(AttributeElementDescriptor.THIS))
		.build();
	PropertyDescriptor INDEXED = PropertyDescriptor.builder()
		.name("indexed")
		.description("""		
			When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
			This property contains set of all scopes this attribute compound is indexed in.
			""")
		.type(nonNull(Scope[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SortableAttributeCompoundSchema")
		.description("""
			Sortable attribute compounds are used to sort entities or references by multiple attributes at once. evitaDB
			requires a pre-sorted index in order to be able to sort entities or references by particular attribute or
			combination of attributes, so it can deliver the results as fast as possible. Sortable attribute compounds
			are filtered the same way as attributes - using natural ordering constraint.
			""")
		.staticProperty(NAME)
		.staticProperty(NAME_VARIANTS)
		.staticProperty(DESCRIPTION)
		.staticProperty(DEPRECATION_NOTICE)
		.staticProperty(ATTRIBUTE_ELEMENTS)
		.staticProperty(INDEXED)
		.build();
}
