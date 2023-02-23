/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface CreateReferenceSchemaMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor DESCRIPTION = PropertyDescriptor.builder()
		.name("description")
		.description("""
			Contains description of the model is optional but helps authors of the schema / client API to better
			explain the original purpose of the model to the consumers.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor DEPRECATION_NOTICE = PropertyDescriptor.builder()
		.name("deprecationNotice")
		.description("""
			Deprecation notice contains information about planned removal of this schema from the model / client API.
			This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor CARDINALITY = PropertyDescriptor.builder()
		.name("cardinality")
		.description("""
			Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
			relationship from the perspective of the entity. We stick to the ERD modelling
			[standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
			of the client API (returning only single reference or collections) and also help us to protect the consistency
			of the data so that conforms to the creator mental model.
			""")
		.type(nullable(Cardinality.class))
		.build();
	PropertyDescriptor REFERENCED_ENTITY_TYPE = PropertyDescriptor.builder()
		.name("referencedEntityType")
		.description("""
			Reference to `EntitySchema.name` of the referenced entity. Might be also any `String`
			that identifies type some external resource not maintained by Evita.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor REFERENCED_ENTITY_TYPE_MANAGED = PropertyDescriptor.builder()
		.name("referencedEntityTypeManaged")
		.description("""
			Whether `referencedEntityType` refers to any existing `EntitySchema.name` that is
			maintained by Evita.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor REFERENCED_GROUP_TYPE = PropertyDescriptor.builder()
		.name("referencedGroupType")
		.description("""
			Reference to `EntitySchema.name` of the referenced group entity. Might be also any `String`
			that identifies type some external resource not maintained by Evita.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor REFERENCED_GROUP_TYPE_MANAGED = PropertyDescriptor.builder()
		.name("referencedGroupTypeManaged")
		.description("""
			Whether `referencedGroupType` refers to any existing `EntitySchema.name` that is
			maintained by Evita.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor FILTERABLE = PropertyDescriptor.builder()
		.name("filterable")
		.description("""
			Whether the index for this reference should be created and maintained allowing to filter by
			`reference_{reference name}_having` filtering constraints. Index is also required when reference is
			`faceted`.
			
			Do not mark reference as faceted unless you know that you'll need to filter entities by this reference. Each
			indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed, the entity
			cannot be looked up by reference attributes or relation existence itself, but the data is loaded alongside
			other references if requested.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor FACETED = PropertyDescriptor.builder()
		.name("faceted")
		.description("""
			Whether the statistics data for this reference should be maintained and this allowing to get
			`facetSummary` for this reference or use `facet_{reference name}_inSet`
			filtering query.
			
			Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
			occupies (memory/disk) space in the form of index.
			Reference that was marked as faceted is called Facet.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("CreateReferenceSchemaMutation")
		.description("""
			Mutation is responsible for setting up a new `ReferenceSchema` in the `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticFields(List.of(
			NAME,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			CARDINALITY,
			REFERENCED_ENTITY_TYPE,
			REFERENCED_ENTITY_TYPE_MANAGED,
			REFERENCED_GROUP_TYPE,
			REFERENCED_GROUP_TYPE_MANAGED,
			FILTERABLE,
			FACETED
		))
		.build();
}
