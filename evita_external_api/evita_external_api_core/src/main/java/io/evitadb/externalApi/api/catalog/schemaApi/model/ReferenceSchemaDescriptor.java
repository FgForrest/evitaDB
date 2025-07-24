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

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Ancestor for representing {@link ReferenceSchema}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ReferenceSchemaDescriptor extends NamedSchemaWithDeprecationDescriptor, SortableAttributeCompoundsSchemaProviderDescriptor {

	PropertyDescriptor CARDINALITY = PropertyDescriptor.builder()
		.name("cardinality")
		.description("""
			Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
			relationship from the perspective of the entity. We stick to the ERD modelling
			[standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
			of the client API (returning only single reference or collections) and also help us to protect the consistency
			of the data so that conforms to the creator mental model.
			""")
		.type(nonNull(Cardinality.class))
		.build();

	PropertyDescriptor REFERENCED_ENTITY_TYPE = PropertyDescriptor.builder()
		.name("referencedEntityType")
		.description("""
			Reference to `Entity.type` of the referenced entity. Might be also any `String`
			that identifies type some external resource not maintained by Evita.
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor ENTITY_TYPE_NAME_VARIANTS = PropertyDescriptor.builder()
		.name("entityTypeNameVariants")
		.description("""
			Map contains the `entityType` name variants in different naming conventions. The name
			is guaranteed to be unique among other references in same convention. These names are used to quickly
			translate to / from names used in different protocols. Each API protocol prefers names in different naming
			conventions.
			""")
		.type(nonNullRef(NameVariantsDescriptor.THIS))
		.build();

	PropertyDescriptor REFERENCED_ENTITY_TYPE_MANAGED = PropertyDescriptor.builder()
		.name("referencedEntityTypeManaged")
		.description("""
			Contains `true` if `entityType` refers to any existing entity that is maintained
			by Evita.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor REFERENCED_GROUP_TYPE = PropertyDescriptor.builder()
		.name("referencedGroupType")
		.description("""
			Reference to `Entity.type` of the referenced entity. Might be also `String`
			that identifies type some external resource not maintained by Evita.
			""")
		.type(nullable(String.class))
		.build();

	PropertyDescriptor GROUP_TYPE_NAME_VARIANTS = PropertyDescriptor.builder()
		.name("groupTypeNameVariants")
		.description("""
			Map contains the `groupType` name variants in different naming conventions. The name
			is guaranteed to be unique among other references in same convention. These names are used to quickly
			translate to / from names used in different protocols. Each API protocol prefers names in different naming
			conventions.
			""")
		.type(nullableRef(NameVariantsDescriptor.THIS))
		.build();

	PropertyDescriptor REFERENCED_GROUP_TYPE_MANAGED = PropertyDescriptor.builder()
		.name("referencedGroupTypeManaged")
		.description("""
			Contains `true` if `groupType` refers to any existing entity that is maintained
			by Evita.
			""")
		.type(nullable(Boolean.class))
		.build();

	PropertyDescriptor INDEXED = PropertyDescriptor.builder()
		.name("indexed")
		.description("""
			Contains information about scopes and index types for this reference that should be created and maintained
			allowing to filter by `reference_{reference name}_having` filtering constraints and sorted by
			`reference_{reference name}_property` constraints. Index is also required when reference is `faceted` -
			but it has to be indexed in the same scope as faceted.
						
			Do not mark reference as indexed unless you know that you'll need to filter/sort entities by this reference.
			Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
			the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded
			alongside other references and is available by calling entity reference methods.
			
			The index type determines the level of indexing optimization applied to improve query performance when
			filtering by reference constraints. The index type affects both memory/disk usage and query performance.
			Maintaining partitioned indexes provides better query performance at the cost of increased storage
			requirements and maintenance overhead.
			
			Returns array of scopes and their corresponding reference index types in which this reference is indexed.
			""")
		.type(nonNullListRef(ScopedReferenceIndexTypeDescriptor.THIS))
		.build();

	PropertyDescriptor FACETED = PropertyDescriptor.builder()
		.name("faceted")
		.description("""
			Contains `true` if the statistics data for this reference should be maintained and this allowing to get
			`facetStatistics` for this reference or use `facet_{reference name}_inSet`
			filtering constraint.
						
			Do not mark reference as faceted unless you want it among `facetStatistics`. Each faceted reference
			occupies (memory/disk) space in the form of index.
						
			Reference that was marked as faceted is called Facet.
			
			Returns array of scope in which this reference is faceted.
			""")
		.type(nonNull(Scope[].class))
		.build();

	PropertyDescriptor ATTRIBUTES = PropertyDescriptor.builder()
		.name("attributes")
		.description("""
			Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
			Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
			(`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
			memory space for data that will never be used in search queries.
						
			Filtering in attributes is executed by using constraints like `and`,
			`not`, `attribute_{name}_equals`, `attribute_{name}_contains`
			and many others. Sorting can be achieved with `attribute_{name}_natural` or others.
						
			Attributes are not recommended for bigger data as they are all loaded at once.
			""")
		// type is expected to be a map with attribute names as keys and attribute schemas as values
		.build();

	PropertyDescriptor ALL_ATTRIBUTES = PropertyDescriptor.builder()
		.name("allAttributes")
		.description("""
			Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
			Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
			(`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
			memory space for data that will never be used in search queries.
						
			Filtering in attributes is executed by using constraints like `and`,
			`not`, `attribute_{name}_equals`, `attribute_{name}_contains`
			and many others. Sorting can be achieved with `attribute_{name}_natural` or others.
						
			Attributes are not recommended for bigger data as they are all loaded at once.
			""")
		.type(nonNullListRef(AttributeSchemaDescriptor.THIS))
		.build();

	ObjectDescriptor THIS_SPECIFIC = ObjectDescriptor.builder()
		.name("*ReferenceSchema")
		.staticFields(List.of(
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			CARDINALITY,
			REFERENCED_ENTITY_TYPE,
			REFERENCED_ENTITY_TYPE_MANAGED,
			REFERENCED_GROUP_TYPE,
			REFERENCED_GROUP_TYPE_MANAGED,
			INDEXED,
			FACETED,
			ENTITY_TYPE_NAME_VARIANTS,
			GROUP_TYPE_NAME_VARIANTS
		))
		.build();

	ObjectDescriptor THIS_GENERIC = ObjectDescriptor.builder()
		.name("ReferenceSchema")
		.description("""
			This is the definition object for reference that is stored along with
			entity. Definition objects allow to describe the structure of the entity type so that
			in any time everyone can consult complete structure of the entity type.
			
			The references refer to other entities (of same or different entity type).
			Allows entity filtering (but not sorting) of the entities by using `facet_{name}_inSet` query
			and statistics computation if when requested. Reference
			is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and entity type and can be
			part of multiple reference groups, that are also represented by int and entity type.
			
			Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
			of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
			to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
			group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
			Evita.
			
			References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
			""")
		.staticFields(List.of(
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			CARDINALITY,
			REFERENCED_ENTITY_TYPE,
			ENTITY_TYPE_NAME_VARIANTS,
			REFERENCED_ENTITY_TYPE_MANAGED,
			REFERENCED_GROUP_TYPE,
			GROUP_TYPE_NAME_VARIANTS,
			REFERENCED_GROUP_TYPE_MANAGED,
			INDEXED,
			FACETED,
			ALL_ATTRIBUTES,
			ALL_SORTABLE_ATTRIBUTE_COMPOUNDS
		))
		.build();
}
