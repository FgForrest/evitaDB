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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * This is the definition object for {@link Reference} that is stored along with
 * {@link Entity}. Definition objects allow to describe the structure of the entity type so that
 * in any time everyone can consult complete structure of the entity type. Definition object is similar to Java reflection
 * process where you can also at any moment see which fields and methods are available for the class.
 *
 * The references refer to other entities (of same or different entity type).
 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
 *
 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
 * Evita.
 *
 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
 * The search query must contain specific {@link ReferenceContent} requirement in order references are fetched along with
 * the entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ReferenceSchemaContract extends
	NamedSchemaWithDeprecationContract,
	SortableAttributeCompoundSchemaProvider<AttributeSchemaContract, SortableAttributeCompoundSchemaContract>
{

	/**
	 * Name of the reference is propagated to the client API schemas and distinguish multiple different relations that
	 * may target the very same {@link #getReferencedEntityType()}. The name is mandatory for the relation.
	 *
	 * As the example of such reference that targets the same entity type but has different meaning can be the reference
	 * `alternativeProducts` that links products that can replace current product, and `variantProducts` that are only
	 * different configurations of the very same product. We usually need to express multiple similar relations for
	 * main entities and proper naming allows us to separate those and make them more comprehensible.
	 */
	@Nonnull
	@Override
	String getName();

	/**
	 * Method returns the name of the referenced type in specified naming convention. The names are kept in the schema
	 * because the translation is computational expensive and also there is no guaranteed way that the name converted
	 * from original to a version in specific naming convention could be reverted to the original - some information is
	 * lost during the conversion. We also need to ensure that the name in all conventions stays unique.
	 *
	 * @param namingConvention    to get name variant for
	 * @param entitySchemaFetcher function that allows fetching another entity schema from the catalog
	 * @return attribute {@link #getName()} in specified naming convention
	 */
	@Nonnull
	String getReferencedEntityTypeNameVariant(
		@Nonnull NamingConvention namingConvention,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher
	);

	/**
	 * Method returns the name of the referenced group type in specified naming convention. The names are kept in the schema
	 * because the translation is computational expensive and also there is no guaranteed way that the name converted
	 * from original to a version in specific naming convention could be reverted to the original - some information is
	 * lost during the conversion. We also need to ensure that the name in all conventions stays unique.
	 *
	 * @param namingConvention    to get name variant for
	 * @param entitySchemaFetcher function that allows fetching another entity schema from the catalog
	 * @return attribute {@link #getName()} in specified naming convention
	 */
	@Nullable
	String getReferencedGroupTypeNameVariant(
		@Nonnull NamingConvention namingConvention,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher
	);

	/**
	 * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
	 * relationship from the perspective of the entity. We stick to the ERD modelling
	 * <a href="https://www.gleek.io/blog/crows-foot-notation.html">standards</a> here. Cardinality affect the design
	 * of the client API (returning only single reference or collections) and also help us to protect the consistency
	 * of the data so that conforms to the creator mental model.
	 */
	@Nonnull
	Cardinality getCardinality();

	/**
	 * Reference to {@link EntitySchemaContract#getName()} of the referenced entity. Might be also any {@link String}
	 * that identifies type some external resource not maintained by Evita.
	 */
	@Nonnull
	String getReferencedEntityType();

	/**
	 * Map contains the {@link #getReferencedEntityType()} name variants in different {@link NamingConvention naming conventions}.
	 * The name is guaranteed to be unique among other references in same convention. These names are used to quickly
	 * translate to / from names used in different protocols. Each API protocol prefers names in different naming
	 * conventions.
	 *
	 * These entity type variants are available only when {@link #isReferencedEntityTypeManaged()} is set to FALSE.
	 */
	@Nonnull
	Map<NamingConvention, String> getEntityTypeNameVariants(
		@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher
	);

	/**
	 * Contains TRUE if {@link #getReferencedEntityType()} refers to any existing {@link EntitySchemaContract#getName()} that is
	 * maintained by Evita.
	 */
	boolean isReferencedEntityTypeManaged();

	/**
	 * Reference to {@link EntitySchemaContract#getName()} of the referenced group entity. Might be also {@link String}
	 * that identifies type some external resource not maintained by Evita.
	 */
	@Nullable
	String getReferencedGroupType();

	/**
	 * Map contains the {@link #getReferencedGroupType()} name variants in different {@link NamingConvention naming conventions}.
	 * The name is guaranteed to be unique among other references in same convention. These names are used to quickly
	 * translate to / from names used in different protocols. Each API protocol prefers names in different naming
	 * conventions.
	 *
	 * These entity type variants are available only when {@link #isReferencedGroupTypeManaged()} is set to FALSE.
	 */
	@Nonnull
	Map<NamingConvention, String> getGroupTypeNameVariants(
		@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher
	);

	/**
	 * Contains TRUE if {@link #getReferencedGroupType()} refers to any existing {@link EntitySchemaContract#getName()} that is
	 * maintained by Evita.
	 */
	boolean isReferencedGroupTypeManaged();

	/**
	 * Returns TRUE if the index in any scope for this reference should be created and maintained allowing to
	 * filter by {@link ReferenceHaving} filtering and sorted by {@link ReferenceProperty} constraints. Index is also
	 * required when reference is {@link #isFaceted() faceted} - but it has to be indexed in the same scope as faceted.
	 *
	 * Do not mark reference as indexed unless you know that you'll need to filter / sort entities by this reference.
	 * Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
	 * the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded
	 * alongside other references and is available by calling {@link SealedEntity#getReferences()} method.
	 *
	 * @return true if reference is indexed in any scope
	 */
	default boolean isIndexedInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isIndexedInScope);
	}

	/**
	 * Returns TRUE if the index in {@link Scope#DEFAULT_SCOPE} for this reference should be created and maintained allowing to
	 * filter by {@link ReferenceHaving} filtering and sorted by {@link ReferenceProperty} constraints. Index is also
	 * required when reference is {@link #isFaceted() faceted} - but it has to be indexed in the same scope as faceted.
	 *
	 * Do not mark reference as indexed unless you know that you'll need to filter / sort entities by this reference.
	 * Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
	 * the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded
	 * alongside other references and is available by calling {@link SealedEntity#getReferences()} method.
	 *
	 * @return true if reference is indexed in {@link Scope#DEFAULT_SCOPE} scope
	 */
	default boolean isIndexed() {
		return isIndexedInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Returns TRUE if the index in particular {@link Scope} for this reference should be created and maintained
	 * allowing to filter by {@link ReferenceHaving} filtering and sorted by {@link ReferenceProperty} constraints.
	 * Index is also required when reference is {@link #isFaceted() faceted}.
	 *
	 * Do not mark reference as indexed unless you know that you'll need to filter / sort entities by this reference.
	 * Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
	 * the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded
	 * alongside other references and is available by calling {@link SealedEntity#getReferences()} method.
	 *
	 * @param scope to check reference is indexed in
	 * @return true if reference is indexed in particular scope
	 */
	boolean isIndexedInScope(@Nonnull Scope scope);

	/**
	 * Returns set of all scopes the reference information is indexed in and can be used for filtering entities and computation
	 * of extra data. If the reference information is not indexed, it is still available on the entity itself (i.e. entity
	 * can define its references of this type), but it is not possible to work with the reference information in any
	 * other way (filtering by {@link ReferenceHaving}, sorting by {@link EntityProperty}, or calculating {@link FacetSummary}).
	 *
	 * Beware - non indexed references are automatically non-faceted.
	 *
	 * @return set of all scopes the reference information is indexed in
	 */
	@Nonnull
	Set<Scope> getIndexedInScopes();

	/**
	 * Returns the type of index that should be created and maintained for this reference in the {@link Scope#DEFAULT_SCOPE}.
	 * The index type determines the level of indexing optimization applied to improve query performance when filtering
	 * by {@link ReferenceHaving} constraints.
	 *
	 * The index type affects both memory/disk usage and query performance. Maintaining partitioned indexes provides
	 * better query performance at the cost of increased storage requirements and maintenance overhead.
	 *
	 * @return the reference index type for the default scope
	 */
	@Nonnull
	default ReferenceIndexType getIndexType() {
		return getReferenceIndexType(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Returns the type of index that should be created and maintained for this reference in the specified {@link Scope}.
	 * The index type determines the level of indexing optimization applied to improve query performance when filtering
	 * by {@link ReferenceHaving} constraints within that particular scope.
	 *
	 * The index type affects both memory/disk usage and query performance. Maintaining partitioned indexes provides
	 * better query performance at the cost of increased storage requirements and maintenance overhead.
	 *
	 * @param scope the scope to get the reference index type for
	 * @return the reference index type for the specified scope
	 */
	@Nonnull
	ReferenceIndexType getReferenceIndexType(@Nonnull Scope scope);

	/**
	 * Returns a map of all scopes and their corresponding reference index types that are configured for this reference.
	 * The index type determines the level of indexing optimization applied to improve query performance when filtering
	 * by {@link ReferenceHaving} constraints within each particular scope.
	 *
	 * The index type affects both memory/disk usage and query performance. Maintaining partitioned indexes provides
	 * better query performance at the cost of increased storage requirements and maintenance overhead.
	 *
	 * @return map where keys are scopes and values are the corresponding reference index types
	 */
	@Nonnull
	Map<Scope, ReferenceIndexType> getReferenceIndexTypeInScopes();

	/**
	 * Returns TRUE if the statistics data in any scope for this reference should be maintained and this
	 * allowing to get {@link FacetSummary} for this reference or use {@link FacetHaving} filtering query.
	 *
	 * Do not mark reference as faceted unless you want it among {@link FacetStatistics}. Each faceted reference
	 * occupies (memory/disk) space in the form of index.
	 *
	 * Reference that was marked as faceted is called Facet.
	 *
	 * @return true if reference is faceted in any of the scopes
	 */
	default boolean isFacetedInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isFacetedInScope);
	}

	/**
	 * Returns TRUE if the statistics data in {@link Scope#DEFAULT_SCOPE} for this reference should be maintained and this
	 * allowing to get {@link FacetSummary} for this reference or use {@link FacetHaving} filtering query.
	 *
	 * Do not mark reference as faceted unless you want it among {@link FacetStatistics}. Each faceted reference
	 * occupies (memory/disk) space in the form of index.
	 *
	 * Reference that was marked as faceted is called Facet.
	 *
	 * @return true if reference is faceted in {@link Scope#DEFAULT_SCOPE}
	 */
	default boolean isFaceted() {
		return isFacetedInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Returns TRUE if the statistics data in particular {@link Scope} for this reference should be maintained and this
	 * allowing to get {@link FacetSummary} for this reference or use {@link FacetHaving} filtering query.
	 *
	 * Do not mark reference as faceted unless you want it among {@link FacetStatistics}. Each faceted reference
	 * occupies (memory/disk) space in the form of index.
	 *
	 * Reference that was marked as faceted is called Facet.
	 *
	 * @param scope to check reference is faceted in
	 * @return true if reference is faceted in particular scope
	 */
	boolean isFacetedInScope(@Nonnull Scope scope);

	/**
	 * Returns set of all scopes the facet information is indexed in and can be used for filtering entities and computation
	 * of extra data.
	 *
	 * @return set of all scopes the facet information is indexed in
	 */
	@Nonnull
	Set<Scope> getFacetedInScopes();

	/**
	 * Validates current reference schema for invalid settings using the information from current catalog schema.
	 *
	 * @param catalogSchema current catalog schema providing access to other entity schemas in it
	 * @param entitySchema current entity schema where the reference schema is defined
	 * @throws SchemaAlteringException if there is an error in current schema
	 */
	void validate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchema entitySchema) throws SchemaAlteringException;

}
