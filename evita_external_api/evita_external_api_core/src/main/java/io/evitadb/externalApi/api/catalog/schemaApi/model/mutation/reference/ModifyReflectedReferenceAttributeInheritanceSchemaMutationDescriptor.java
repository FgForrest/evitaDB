/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReflectedReferenceAttributeInheritanceSchemaMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link ModifyReflectedReferenceAttributeInheritanceSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor ATTRIBUTE_INHERITANCE_BEHAVIOR = PropertyDescriptor.builder()
		.name("attributeInheritanceBehavior")
		.description("""
			Specifies the inheritance behavior for attributes in the reflected schema. There are two options:
			
			- INHERIT_ALL_EXCEPT: All attributes are inherited by default, except those listed in the `attributeInheritanceFilter`.
			- INHERIT_ONLY_SPECIFIED: No attributes are inherited by default, only those explicitly listed in the `attributeInheritanceFilter`.
			""")
		.type(nonNull(AttributeInheritanceBehavior.class))
		.build();
	PropertyDescriptor ATTRIBUTE_INHERITANCE_FILTER = PropertyDescriptor.builder()
		.name("attributeInheritanceFilter")
		.description("""
			Returns the array of attribute names that filtered in the way driven by the `attributeInheritanceBehavior` property:
			
			- INHERIT_ALL_EXCEPT: inherits all attributes defined on original reference except those listed in this filter
			- INHERIT_ONLY_SPECIFIED: inherits only attributes that are listed in this filter
			""")
		.type(nullable(String[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyReflectedReferenceAttributeInheritanceSchemaMutation")
		.description("""
			Mutation is responsible for setting value to a `ReflectedReferenceSchema.attributesInheritanceBehavior` and
			`ReflectedReferenceSchema.attributeInheritanceFilter` in `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, ATTRIBUTE_INHERITANCE_BEHAVIOR, ATTRIBUTE_INHERITANCE_FILTER))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("ModifyReflectedReferenceAttributeInheritanceSchemaMutationInput")
		.staticProperties(List.of(NAME, ATTRIBUTE_INHERITANCE_BEHAVIOR, ATTRIBUTE_INHERITANCE_FILTER))
		.build();

}
