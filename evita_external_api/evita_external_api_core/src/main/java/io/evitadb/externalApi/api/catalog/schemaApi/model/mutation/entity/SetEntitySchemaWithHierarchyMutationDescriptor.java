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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity;

import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetEntitySchemaWithHierarchyMutationDescriptor extends EntitySchemaMutationDescriptor {

	PropertyDescriptor WITH_HIERARCHY = PropertyDescriptor.builder()
		.name("withHierarchy")
		.description("""
			Whether entities of this type are organized in a tree like structure (hierarchy) where certain entities
			are subordinate of other entities.
			
			Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
			referred by multiple child entities. Hierarchy is always composed of entities of same type.
			Each entity must be part of at most single hierarchy (tree).
			
			Hierarchy can limit returned entities by using filtering constraints `hierarchy_{reference name}_within`. It's also used for
			computation of extra data - such as `hierarchyParents`.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor INDEXED_IN_SCOPES = PropertyDescriptor.builder()
		.name("indexedInScopes")
		.description("""
			Specifies set of all scopes the entity is indexed in and can be used for filtering entities and computation of
			extra data. If the hierarchy information is not indexed, it is still available on the entity itself (i.e. entity
			can define its parent entity), but it is not possible to work with the hierarchy information in any other way
			(calculating parent chain, children, siblings, etc.).
			""")
		.type(nullable(Scope[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetEntitySchemaWithHierarchyMutation")
		.description("""
			Mutation is responsible for setting a `EntitySchema.withHierarchy`
			in `EntitySchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, WITH_HIERARCHY, INDEXED_IN_SCOPES))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("SetEntitySchemaWithHierarchyMutationInput")
		.staticProperties(List.of(WITH_HIERARCHY, INDEXED_IN_SCOPES))
		.build();
}
