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

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of {@link EntitySchema} for schema-based external APIs. It describes what properties of entity schema are
 * supported in API for better field names and docs maintainability.
 *
 * It should copy {@link EntitySchema} closely so that it can be used for altering the schema though external API.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface EntitySchemaDescriptor extends VersionedDescriptor, NamedSchemaWithDeprecationDescriptor, SortableAttributeCompoundsSchemaProviderDescriptor {

	PropertyDescriptor WITH_GENERATED_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("withGeneratedPrimaryKey")
		.description("""
			Contains `true` when primary keys of entities of this type will not be provided by the external systems and Evita
			is responsible for generating unique primary keys for the entity on insertion.
			
			Generated key is guaranteed to be unique, but may not represent continuous ascending series. Generated key
			will be always greater than zero.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor WITH_HIERARCHY = PropertyDescriptor.builder()
		.name("withHierarchy")
		.description("""
			Contains `true` when entities of this type are organized in a tree like structure (hierarchy) where certain entities
			are subordinate of other entities.
			
			Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
			referred by multiple child entities. Hierarchy is always composed of entities of same type.
			Each entity must be part of at most single hierarchy (tree).
			
			Hierarchy can limit returned entities by using filtering constraints. It's also used for
			computation of extra data - such as `hierarchyParentsOfSelf`. It can also invert type of returned entities in case extra result
			`hierarchyOfSelf` is requested.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor HIERARCHY_INDEXED = PropertyDescriptor.builder()
		.name("hierarchyIndexed")
		.description("""
			Contains set of all scopes the entity is indexed in and can be used for filtering entities and computation of
			extra data. If the hierarchy information is not indexed, it is still available on the entity itself (i.e. entity
			can define its parent entity), but it is not possible to work with the hierarchy information in any other way
			(calculating parent chain, children, siblings, etc.).
			""")
		.type(nonNull(Scope[].class))
		.build();

	PropertyDescriptor WITH_PRICE = PropertyDescriptor.builder()
		.name("withPrice")
		.description("""
			Contains `true` when entities of this type holds price information.
			
			Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce
			systems and highly affects performance of the entities filtering and sorting, they deserve first class support
			in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
			customers.
			
			Specifying prices on entity allows usage of `priceValidIn`, `priceInCurrency`
			`priceBetween`, and `priceInPriceLists` filtering constraints and also `priceNatural`,
			ordering of the entities. Additional extra result
			`priceHistogram` and requirement `priceType` can be used in query as well.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor PRICE_INDEXED = PropertyDescriptor.builder()
		.name("priceIndexed")
		.description("""
			Returns set of all scopes the price information is indexed in and can be used for filtering entities and computation
			of extra data. If the price information is not indexed, it is still available on the entity itself (i.e. entity
			can define its price), but it is not possible to work with the price information in any other way (calculating
			price histogram, filtering, sorting by price, etc.).
			
			Prices can be also set as non-indexed individually by setting indexed property on price to false.
			""")
		.type(nonNull(Scope[].class))
		.build();

	PropertyDescriptor INDEXED_PRICE_PLACES = PropertyDescriptor.builder()
		.name("indexedPricePlaces")
		.description("""
			Determines how many fractional places are important when entities are compared during filtering or sorting. It is
			important to know that all prices will be converted to `Int`, so any of the price values
			(either with or without tax) must not ever exceed maximum limits of `Int` type when scaling
			the number by the power of ten using `indexedPricePlaces` as exponent.
			""")
		.type(nonNull(Integer.class))
		.build();

	PropertyDescriptor LOCALES = PropertyDescriptor.builder()
		.name("locales")
		.description("""
			Contains set of all `Locale` that could be used for localized `AttributeSchema` or `AssociatedDataSchema`.
			Enables using `entityLocaleEquals` filtering constraint in query.
			""")
		.type(nonNull(Locale[].class))
		.build();

	PropertyDescriptor CURRENCIES = PropertyDescriptor.builder()
		.name("currencies")
		.description("""
            Contains set of all `Currency` that could be used for `prices` in entities of this type.
            """)
		.type(nonNull(Currency[].class))
		.build();

	PropertyDescriptor EVOLUTION_MODE = PropertyDescriptor.builder()
		.name("evolutionMode")
		.description("""
			Evolution mode allows to specify how strict is evitaDB when unknown information is presented to her for the first
			time. When no evolution mode is set, each violation of the `EntitySchema` is
			reported by an exception. This behaviour can be changed by this evolution mode however.
			""")
		.type(nonNull(String[].class))
		.build();

	PropertyDescriptor ATTRIBUTES = PropertyDescriptor.builder()
		.name("attributes")
		.description("""
			Contains index of all `AttributeSchema` that could be used as attributes of entity of this type.
			
			Entity (global) attributes allows defining set of data that are fetched in bulk along with the entity body.
			Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
			(`AttributeSchema.sortable`). Attributes are not automatically indexed in order not to waste precious
			memory space for data that will never be used in search queries.
			
			Filtering in attributes is executed by using constraints like `and`,
			`not`, `attribute_{name}_equals`, `attribute_{name}_contains`
			and many others. Sorting can be achieved with `attribute_{name}_natural` or others.
			
			Attributes are not recommended for bigger data as they are all loaded at once requested.
			Large data that are occasionally used store in `associatedData`.
			""")
		// type is expected to be a map with attribute names as keys and attribute schemas as values
		.build();

	PropertyDescriptor ALL_ATTRIBUTES = PropertyDescriptor.builder()
		.name("allAttributes")
		.description("""
			Contains index of all `AttributeSchema` that could be used as attributes of entity of this type.
			
			Entity (global) attributes allows defining set of data that are fetched in bulk along with the entity body.
			Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
			(`AttributeSchema.sortable`). Attributes are not automatically indexed in order not to waste precious
			memory space for data that will never be used in search queries.
			
			Filtering in attributes is executed by using constraints like `and`,
			`not`, `attribute_{name}_equals`, `attribute_{name}_contains`
			and many others. Sorting can be achieved with `attribute_{name}_natural` or others.
			
			Attributes are not recommended for bigger data as they are all loaded at once requested.
			Large data that are occasionally used store in `associatedData`.
			""")
		.type(nonNullListRef(AttributeSchemaUnionDescriptor.THIS))
		.build();

	PropertyDescriptor ASSOCIATED_DATA = PropertyDescriptor.builder()
		.name("associatedData")
		.description("""
			Contains index of all `AssociatedDataSchema` that could be used as associated data of entity of this type.
			
			Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
			along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
			in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
			binary arrays representing entire files (i.e. pictures, documents).
			
			The search query must contain specific associated data fields in order
			associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
			""")
		// type is expected to be a map with associated data names as keys and associated data schemas as values
		.build();

	PropertyDescriptor ALL_ASSOCIATED_DATA = PropertyDescriptor.builder()
		.name("allAssociatedData")
		.description("""
			Contains index of all `AssociatedDataSchema` that could be used as associated data of entity of this type.
			
			Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
			along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
			in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
			binary arrays representing entire files (i.e. pictures, documents).
			
			The search query must contain specific associated data fields in order
			associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
			""")
		.type(nonNullListRef(AssociatedDataSchemaDescriptor.THIS))
		.build();

	PropertyDescriptor REFERENCES = PropertyDescriptor.builder()
		.name("references")
		// TODOBEDONE JNO: revise docs, even in javadocs
		.description("""
			Contains index of all `ReferenceSchema` that could be used as references of entity of this type.
			
			References refer to other entities (of same or different entity type).
			Allows entity filtering (but not sorting) of the entities by using `facet_{reference name}_inSet` constraint
			and statistics computation when `facetStatistics` extra result is requested. Reference
			is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and entity type and can be
			part of multiple reference groups, that are also represented by int and entity type.
			
			Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
			of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
			to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
			group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
			Evita.
			
			References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
			The search query must contain specific `referenceContent` requirement in order
			references are fetched along with the entity.
			""")
		// type is expected to be a map with reference names as keys and reference schemas as values
		.build();

	PropertyDescriptor ALL_REFERENCES = PropertyDescriptor.builder()
		.name("allReferences")
		// TODOBEDONE JNO: revise docs, even in javadocs
		.description("""
			Contains index of all `ReferenceSchema` that could be used as references of entity of this type.
			
			References refer to other entities (of same or different entity type).
			Allows entity filtering (but not sorting) of the entities by using `facet_{reference name}_inSet` constraint
			and statistics computation when `facetStatistics` extra result is requested. Reference
			is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and entity type and can be
			part of multiple reference groups, that are also represented by int and entity type.
			
			Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
			of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
			to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
			group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
			Evita.
			
			References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
			The search query must contain specific `referenceContent` requirement in order
			references are fetched along with the entity.
			""")
		.type(nonNullListRef(ReferenceSchemaDescriptor.THIS_GENERIC))
		.build();

	ObjectDescriptor THIS_GENERIC = ObjectDescriptor.builder()
		.name("EntitySchema")
		.description("""
			This is the definition object for entity. Definition objects allow to describe the structure
			of the entity type so that in any time everyone can consult complete structure of the entity type.
			
			Based on our experience we've designed following data model for handling entities in evitaDB. Model is rather complex
			but was designed to limit amount of data fetched from database and minimize an amount of data that are indexed and subject
			to search.
			
			Minimal entity definition consists of:
			- entity type and
			- primary key (even this is optional and may be autogenerated by the database).
			
			Other entity data is purely optional and may not be used at all.
			""")
		.staticProperties(List.of(
			VERSION,
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			WITH_GENERATED_PRIMARY_KEY,
			WITH_HIERARCHY,
			HIERARCHY_INDEXED,
			WITH_PRICE,
			PRICE_INDEXED,
			INDEXED_PRICE_PLACES,
			LOCALES,
			CURRENCIES,
			EVOLUTION_MODE,
			ALL_ATTRIBUTES,
			ALL_SORTABLE_ATTRIBUTE_COMPOUNDS,
			ALL_ASSOCIATED_DATA,
			ALL_REFERENCES
		))
		.build();
	ObjectDescriptor THIS_SPECIFIC = ObjectDescriptor.builder()
		.name("*Schema")
		.description("""
			This is the definition object for entity. Definition objects allow to describe the structure
			of the entity type so that in any time everyone can consult complete structure of the entity type.
			
			Based on our experience we've designed following data model for handling entities in evitaDB. Model is rather complex
			but was designed to limit amount of data fetched from database and minimize an amount of data that are indexed and subject
			to search.
			
			Minimal entity definition consists of:
			- entity type and
			- primary key (even this is optional and may be autogenerated by the database).
			
			Other entity data is purely optional and may not be used at all.
			""")
		.staticProperties(List.of(
			VERSION,
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			WITH_GENERATED_PRIMARY_KEY,
			WITH_HIERARCHY,
			HIERARCHY_INDEXED,
			WITH_PRICE,
			PRICE_INDEXED,
			INDEXED_PRICE_PLACES,
			LOCALES,
			CURRENCIES,
			EVOLUTION_MODE
		))
		.build();
}
