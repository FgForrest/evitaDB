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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A small catalog whose products own reference partitions of every shape the virtual reference partitions care about:
 *
 * - `categories` - indexed for filtering in both scopes, faceted, with a sortable reference attribute and a reference
 *   histogram. Category `n` is referenced by product `n` alone (a single-owner partition) and
 *   {@link #SHARED_CATEGORY} by the first three products (a partition with several owners);
 * - `gallery` - indexed for filtering in both scopes, allowing duplicates told apart by the representative attribute
 *   `role`. Each product shows its own media `n` in two roles (two single-owner partitions differing in their tuple)
 *   and {@link #SHARED_MEDIA} as a thumbnail (a partition every product owns);
 * - `brand` - indexed for filtering and partitioning, so it is never shadowed by the registry;
 * - optionally (see {@link #setUpWithReflectedProducts(EvitaSessionContract)}) `products` on the category - the
 *   reflection of `categories`, indexed for filtering in both scopes and inheriting its attributes. Product `n`'s
 *   partition there is owned by category `n` alone (a single-owner partition maintained by the reflected mutations a
 *   product mutation spawns), and by the shared category too for the first three products.
 *
 * Products also carry a unique `code` and a numeric, filterable and sortable `rank`.
 *
 * {@link #setUpLocalizedSingleOwners(EvitaSessionContract)} sets up another shape of the same entity types and
 * references, in which every partition has a single owner: products in two locales, each the only owner of its own
 * category and media, with localized, chained and compound sort values on both references.
 *
 * {@link #setUpWithEveryReadRoute(EvitaSessionContract)} extends the fixture so that every query route of the
 * virtual reading has something to read in the query lines of {@link CatalogDescriber#describeQueriesOf}: owner
 * locales, a hierarchical and grouped `sections` reference with a sortable and a chained attribute, and enough
 * products in the shared category for the bidirectional rewrite to answer `categories`' reflection from its
 * counterpart.
 *
 * The accessors of this class read the committed state of the catalog {@link #CATALOG} in an engine - its
 * collections, the primary keys of its partitions and its registries of single-owner partitions - so a test reading
 * through them sets its fixture up in that catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SingleOwnerPartitionFixture {
	/**
	 * Name of the catalog the accessors of this class read.
	 */
	static final String CATALOG = "singleOwnerPartitions";
	public static final String ENTITY_PRODUCT = "product";
	public static final String ENTITY_CATEGORY = "category";
	static final String ENTITY_MEDIA = "media";
	static final String ENTITY_BRAND = "brand";
	/**
	 * Every entity type of the fixture.
	 */
	public static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_MEDIA, ENTITY_BRAND);
	static final String ENTITY_SECTION = "section";
	/**
	 * Every entity type of the fixture extended by {@link #setUpWithEveryReadRoute(EvitaSessionContract)}.
	 */
	static final Set<String> EVERY_ROUTE_ENTITY_TYPES = Set.of(
		ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_MEDIA, ENTITY_BRAND, ENTITY_SECTION
	);
	static final String REFERENCE_SECTIONS = "sections";
	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_PRIORITY = "priority";
	static final String ATTRIBUTE_POSITION = "position";
	static final String ATTRIBUTE_SEQUENCE = "sequence";
	/**
	 * The section the first three products share; the sections of the products are its children and the children of
	 * {@link #SECOND_SECTION_ROOT}.
	 */
	static final int SHARED_SECTION = 10;
	/**
	 * The root of the hierarchy of sections holding the shared section and the sections of products 1 to 3.
	 */
	static final int FIRST_SECTION_ROOT = 20;
	/**
	 * The root of the hierarchy of sections holding the sections of products 4 to 6.
	 */
	static final int SECOND_SECTION_ROOT = 21;
	/**
	 * The primary key of the first product that belongs to the shared category only.
	 */
	static final int FIRST_FILLER_PRODUCT = 201;
	/**
	 * How many products belong to the shared category only - enough for the counterpart of `categories` to hold four
	 * times fewer partitions than the reflection, which is when the bidirectional rewrite answers the reflection.
	 */
	static final int FILLER_PRODUCT_COUNT = 40;
	public static final String REFERENCE_CATEGORIES = "categories";
	static final String REFERENCE_GALLERY = "gallery";
	static final String REFERENCE_BRAND = "brand";
	static final String REFERENCE_PRODUCTS = "products";
	static final String ATTRIBUTE_CODE = "code";
	static final String ATTRIBUTE_RANK = "rank";
	public static final String ATTRIBUTE_ORDER = "orderInCategory";
	static final String ATTRIBUTE_ROLE = "role";
	static final String HISTOGRAM_ORDER = "orderInCategoryHistogram";
	static final String ROLE_GALLERY = "gallery";
	static final String ROLE_DETAIL = "detail";
	static final String ROLE_THUMBNAIL = "thumbnail";
	static final String ATTRIBUTE_LABEL = "label";
	static final String ATTRIBUTE_WEIGHT = "weight";
	static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	static final String ATTRIBUTE_CAPTION = "caption";
	static final String COMPOUND_CAPTION_AND_WEIGHT = "captionAndWeight";
	/**
	 * The category the first three products share.
	 */
	static final int SHARED_CATEGORY = 100;
	/**
	 * The media every product shows as a thumbnail.
	 */
	static final int SHARED_MEDIA = 100;
	/**
	 * The brand every product belongs to.
	 */
	static final int BRAND_PK = 1;
	/**
	 * How many products the fixture starts with.
	 */
	static final int PRODUCT_COUNT = 6;
	/**
	 * How many categories and media exist besides the shared ones - enough for products added later.
	 */
	static final int REFERENCED_ENTITY_COUNT = 20;

	private SingleOwnerPartitionFixture() {
	}

	/**
	 * Defines the schema, the referenced entities and the initial products.
	 *
	 * @param session the session to write in
	 */
	static void setUp(@Nonnull EvitaSessionContract session) {
		setUpExtendingCategories(session, categories -> { });
	}

	/**
	 * Defines the schema with the category reference extended as the caller needs, the referenced entities and the
	 * initial products. The extension is part of the schema before the first product is written, so every index the
	 * extension asks for is maintained from the first write on.
	 *
	 * @param session    the session to write in
	 * @param categories extends the definition of the category reference
	 */
	static void setUpExtendingCategories(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReferenceSchemaBuilder> categories
	) {
		setUp(session, categories, false, ReferenceIndexType.FOR_FILTERING);
	}

	/**
	 * Defines the schema with the reflected `products` reference on the category, the referenced entities and the
	 * initial products.
	 *
	 * @param session the session to write in
	 */
	public static void setUpWithReflectedProducts(@Nonnull EvitaSessionContract session) {
		setUpWithReflectedProductsIndexedAs(session, ReferenceIndexType.FOR_FILTERING);
	}

	/**
	 * Same as {@link #setUpWithReflectedProducts(EvitaSessionContract)}, with every reference the fixture indexes for
	 * filtering - `categories`, `gallery` and the reflected `products` - indexed in both scopes by the given index
	 * type instead. Indexed for partitioning, every partition of them is an index object, the single-owner ones
	 * included: the shape a catalog written by 2026.2 holds once those references are changed to filtering only.
	 *
	 * @param session   the session to write in
	 * @param indexType the index type of the references the fixture otherwise indexes for filtering
	 */
	public static void setUpWithReflectedProductsIndexedAs(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceIndexType indexType
	) {
		setUp(session, categories -> { }, true, indexType);
	}

	/**
	 * Defines the schema with the reflected `products` reference on the category and everything the query lines of
	 * {@link CatalogDescriber#describeQueriesOf} need to exercise every query route of the virtual reading, and the
	 * data:
	 *
	 * - products carry a localized `name` - every product in English, the even ones in German too;
	 * - `sections` references the hierarchical `section` entity, indexed for filtering in both scopes, with a sortable
	 *   `priority`, a `position` chaining the products of a section and a `sequence` chaining the sections of a product,
	 *   and grouped by brand. Product `n` alone is in section `n` of brand
	 *   `1 + n % 2`, a child of root {@link #FIRST_SECTION_ROOT} for products 1 to 3 and of root
	 *   {@link #SECOND_SECTION_ROOT} for the others, and products 1 to 3 share {@link #SHARED_SECTION} of brand 1,
	 *   chained in that order and each sequenced after its own section;
	 * - {@link #FILLER_PRODUCT_COUNT} products from {@link #FIRST_FILLER_PRODUCT} on belong to the shared category
	 *   only.
	 *
	 * @param session the session to write in
	 */
	public static void setUpWithEveryReadRoute(@Nonnull EvitaSessionContract session) {
		setUpWithEveryReadRouteIndexedAs(session, ReferenceIndexType.FOR_FILTERING);
	}

	/**
	 * Same as {@link #setUpWithEveryReadRoute(EvitaSessionContract)}, with every reference the fixture indexes for
	 * filtering - `categories`, `gallery`, `sections` and the reflected `products` - indexed in both scopes by the
	 * given index type instead (see
	 * {@link #setUpWithReflectedProductsIndexedAs(EvitaSessionContract, ReferenceIndexType)}).
	 *
	 * @param session   the session to write in
	 * @param indexType the index type of the references the fixture otherwise indexes for filtering
	 */
	public static void setUpWithEveryReadRouteIndexedAs(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceIndexType indexType
	) {
		setUpWithReflectedProductsIndexedAs(session, indexType);
		session.defineEntitySchema(ENTITY_SECTION)
			.withHierarchyIndexedInScope(Scope.LIVE, Scope.ARCHIVED)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_SECTIONS, ENTITY_SECTION, Cardinality.ZERO_OR_MORE,
				whichIs -> indexedInBothScopes(whichIs, indexType)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.indexedWithComponentsInScope(
						Scope.ARCHIVED,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withGroupTypeRelatedToEntity(ENTITY_BRAND)
					.withAttribute(
						ATTRIBUTE_PRIORITY, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_POSITION, Predecessor.class,
						thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_SEQUENCE, ReferencedEntityPredecessor.class,
						thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK + 1));
		session.upsertEntity(session.createNewEntity(ENTITY_SECTION, FIRST_SECTION_ROOT));
		session.upsertEntity(session.createNewEntity(ENTITY_SECTION, SECOND_SECTION_ROOT));
		session.upsertEntity(session.createNewEntity(ENTITY_SECTION, SHARED_SECTION).setParent(FIRST_SECTION_ROOT));
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			session.upsertEntity(
				session.createNewEntity(ENTITY_SECTION, pk)
					.setParent(pk <= 3 ? FIRST_SECTION_ROOT : SECOND_SECTION_ROOT)
			);
		}
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			final int product = pk;
			updateProduct(
				product,
				it -> {
					it.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product " + product);
					if (product % 2 == 0) {
						it.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt " + product);
					}
					it.setReference(
						REFERENCE_SECTIONS, product,
						ref -> ref.setGroup(BRAND_PK + product % 2)
							.setAttribute(ATTRIBUTE_PRIORITY, product)
							.setAttribute(ATTRIBUTE_POSITION, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_SEQUENCE, ReferencedEntityPredecessor.HEAD)
					);
					if (product <= 3) {
						it.setReference(
							REFERENCE_SECTIONS, SHARED_SECTION,
							ref -> ref.setGroup(BRAND_PK)
								.setAttribute(ATTRIBUTE_PRIORITY, 10 + product)
								.setAttribute(
									ATTRIBUTE_POSITION, product == 1 ? Predecessor.HEAD : new Predecessor(product - 1)
								)
								.setAttribute(ATTRIBUTE_SEQUENCE, new ReferencedEntityPredecessor(product))
						);
					}
				}
			).accept(session);
		}
		for (int pk = FIRST_FILLER_PRODUCT; pk < FIRST_FILLER_PRODUCT + FILLER_PRODUCT_COUNT; pk++) {
			final int product = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, product)
					.setAttribute(ATTRIBUTE_CODE, "F" + product)
					.setAttribute(ATTRIBUTE_RANK, product)
					.setReference(
						REFERENCE_CATEGORIES, SHARED_CATEGORY,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, product)
					)
			);
		}
	}

	/**
	 * Defines the schema in which every partition has a single owner, the referenced entities and the initial products
	 * - see {@link #ENTITY_TYPES} and the reference constants:
	 *
	 * - `categories` - indexed for filtering in both scopes, faceted, with a sortable attribute, a localized sortable
	 *   one, a chained one and a localized compound of the first two;
	 * - `gallery` - indexed for filtering in both scopes, allowing duplicates told apart by the representative `role`,
	 *   with a sortable `weight`, a localized sortable `caption` and their compound;
	 * - `brand` - indexed for partitioning - its partitions are always index objects, shared by every product;
	 * - `products` on the category - the reflection of `categories`, indexed for filtering in both scopes and
	 *   inheriting its order.
	 *
	 * Product `n` (1 to {@link #PRODUCT_COUNT}) is in category `n` and shows media `n` in the gallery role; the even
	 * products have a German name and a German label of their category reference besides the English ones.
	 *
	 * @param session the session to write in
	 */
	static void setUpLocalizedSingleOwners(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_MEDIA).updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.faceted()
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_LABEL, String.class,
						thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_POSITION, Predecessor.class,
						thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_ORDER,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
						},
						thatIs -> thatIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
					)
			)
			.withReferenceToEntity(
				REFERENCE_GALLERY, ENTITY_MEDIA, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_ROLE, String.class, thatIs -> thatIs.filterable().representative())
					.withAttribute(
						ATTRIBUTE_WEIGHT, Integer.class,
						thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_CAPTION, String.class,
						thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_CAPTION_AND_WEIGHT,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_CAPTION, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_WEIGHT, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						},
						thatIs -> thatIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
					)
			)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited(ATTRIBUTE_ORDER)
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		for (int pk = 1; pk <= REFERENCED_ENTITY_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
			session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			newProduct(session, pk, pk, pk % 2 == 0).upsertVia(session);
		}
	}

	/**
	 * Creates a product of {@link #setUpLocalizedSingleOwners(EvitaSessionContract)} in category `target`, showing
	 * media `target` in the gallery role, of the fixture's brand.
	 *
	 * @param session    the session to create the entity in
	 * @param primaryKey the product primary key
	 * @param target     the category and the media the product references
	 * @param german     TRUE to give the product a German name and its category reference a German label too
	 * @return the entity builder
	 */
	@Nonnull
	static EntityBuilder newProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int target,
		boolean german
	) {
		final EntityBuilder product = session
			.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "P" + primaryKey)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product " + primaryKey)
			.setReference(
				REFERENCE_CATEGORIES, target,
				whichIs -> {
					whichIs.setAttribute(ATTRIBUTE_ORDER, target)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + target)
						.setAttribute(ATTRIBUTE_POSITION, Predecessor.HEAD);
					if (german) {
						whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett " + target);
					}
				}
			)
			.setOrUpdateReference(
				REFERENCE_GALLERY, target,
				ref -> ROLE_GALLERY.equals(ref.getAttribute(ATTRIBUTE_ROLE)),
				whichIs -> whichIs.setAttribute(ATTRIBUTE_ROLE, ROLE_GALLERY).setAttribute(ATTRIBUTE_WEIGHT, target)
			)
			.setReference(REFERENCE_BRAND, BRAND_PK);
		if (german) {
			product.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt " + primaryKey);
		}
		return product;
	}

	/**
	 * Defines the schema, the referenced entities and the initial products.
	 *
	 * @param session           the session to write in
	 * @param categories        extends the definition of the category reference
	 * @param reflectedProducts TRUE to define the reflected `products` reference on the category
	 * @param indexType         the index type of the references indexed for filtering otherwise
	 */
	private static void setUp(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReferenceSchemaBuilder> categories,
		boolean reflectedProducts,
		@Nonnull ReferenceIndexType indexType
	) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_MEDIA).updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
			.withAttribute(ATTRIBUTE_RANK, Integer.class, whichIs -> whichIs.filterable().sortable().nullable())
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> categories.accept(
					indexedInBothScopes(whichIs, indexType)
						.faceted()
						.withAttribute(
							ATTRIBUTE_ORDER, Integer.class,
							thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
						)
						.bucketed(
							HISTOGRAM_ORDER,
							ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_ORDER + "']")
						)
				)
			)
			.withReferenceToEntity(
				REFERENCE_GALLERY, ENTITY_MEDIA, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> indexedInBothScopes(whichIs, indexType)
					.withAttribute(ATTRIBUTE_ROLE, String.class, thatIs -> thatIs.filterable().representative())
			)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		if (reflectedProducts) {
			session.defineEntitySchema(ENTITY_CATEGORY)
				.withReflectedReferenceToEntity(
					REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
					whichIs -> indexedInBothScopes(
						whichIs.withCardinality(Cardinality.ZERO_OR_MORE).withAttributesInherited(), indexType
					)
				)
				.updateVia(session);
		}
		for (int pk = 1; pk <= REFERENCED_ENTITY_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
			session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY));
		session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, SHARED_MEDIA));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			session.upsertEntity(newProduct(session, pk));
		}
	}

	/**
	 * Indexes a reference in both scopes by the given index type - for filtering, or for filtering and partitioning.
	 *
	 * @param reference the builder of the reference
	 * @param indexType the index type
	 * @param <T>       the type of the builder
	 * @return the builder
	 */
	@Nonnull
	static <T extends ReferenceSchemaEditor<T>> T indexedInBothScopes(
		@Nonnull T reference,
		@Nonnull ReferenceIndexType indexType
	) {
		return switch (indexType) {
			case FOR_FILTERING -> reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED);
			case FOR_FILTERING_AND_PARTITIONING ->
				reference.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED);
			case NONE -> throw new IllegalArgumentException("The fixture indexes every reference it defines.");
		};
	}

	/**
	 * Creates product `primaryKey`: in category `primaryKey` (and the shared one, for the first three products),
	 * showing media `primaryKey` in two roles and the shared media as a thumbnail, of the fixture's brand.
	 *
	 * @param session    the session to create the entity in
	 * @param primaryKey the product primary key, which also picks its own category and media
	 * @return the entity builder
	 */
	@Nonnull
	public static EntityBuilder newProduct(@Nonnull EvitaSessionContract session, int primaryKey) {
		final EntityBuilder product = session
			.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "P" + primaryKey)
			.setAttribute(ATTRIBUTE_RANK, primaryKey * 10)
			.setReference(
				REFERENCE_CATEGORIES, primaryKey,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, primaryKey)
			)
			.setReference(REFERENCE_BRAND, BRAND_PK);
		if (primaryKey <= 3) {
			product.setReference(
				REFERENCE_CATEGORIES, SHARED_CATEGORY,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, 100 + primaryKey)
			);
		}
		withMedia(product, primaryKey, ROLE_GALLERY);
		withMedia(product, primaryKey, ROLE_DETAIL);
		withMedia(product, SHARED_MEDIA, ROLE_THUMBNAIL);
		return product;
	}

	/**
	 * Creates a step updating one product.
	 *
	 * @param primaryKey the product primary key
	 * @param change     the change to make
	 * @return the step
	 */
	@Nonnull
	public static Consumer<EvitaSessionContract> updateProduct(
		int primaryKey,
		@Nonnull Consumer<EntityBuilder> change
	) {
		return session -> {
			final EntityBuilder builder = session
				.getEntity(ENTITY_PRODUCT, primaryKey, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			change.accept(builder);
			builder.upsertVia(session);
		};
	}

	/**
	 * Defines the schema and the data of {@link #setUpLocalizedSingleOwners(EvitaSessionContract)}, then makes the
	 * brand reference mandatory - every product of the fixture has its brand - so that a mutation can be made to fail
	 * at its very end (see {@link #withoutTheMandatoryBrand(Function)}).
	 *
	 * @param session the session to write in
	 */
	static void setUpLocalizedSingleOwnersWithMandatoryBrand(@Nonnull EvitaSessionContract session) {
		setUpLocalizedSingleOwners(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.EXACTLY_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
	}

	/**
	 * Creates a step that upserts the change of a product the builder describes without the product's brand, which
	 * {@link #setUpLocalizedSingleOwnersWithMandatoryBrand(EvitaSessionContract)} makes mandatory. The consistency
	 * check of the root mutation refuses the missing brand once the mutation has written every reference - the
	 * reflected ones included - so the step fails at its very end with a `ReferenceCardinalityViolatedException`. The
	 * check precedes the finalization of the root mutation - the locale changes of its owners, the demotions of the
	 * partitions it left with one owner and the sweep of emptied indexes - which a failing step never reaches.
	 *
	 * @param change builds the change of the product in the session
	 * @return the step
	 */
	@Nonnull
	static Consumer<EvitaSessionContract> withoutTheMandatoryBrand(
		@Nonnull Function<EvitaSessionContract, EntityBuilder> change
	) {
		return session -> change.apply(session).removeReference(REFERENCE_BRAND, BRAND_PK).upsertVia(session);
	}

	/**
	 * Opens an existing product for writing in the session.
	 *
	 * @param session    the session
	 * @param primaryKey the product primary key
	 * @return the builder of the product
	 */
	@Nonnull
	static EntityBuilder openProductForWrite(@Nonnull EvitaSessionContract session, int primaryKey) {
		return session.getEntity(ENTITY_PRODUCT, primaryKey, entityFetchAllContent()).orElseThrow().openForWrite();
	}

	/**
	 * Adds a gallery reference of the given role, unless the product already shows the media in that role.
	 *
	 * @param product the product
	 * @param media   the media primary key
	 * @param role    the role
	 * @return the product
	 */
	@Nonnull
	static EntityBuilder withMedia(@Nonnull EntityBuilder product, int media, @Nonnull String role) {
		return product.setOrUpdateReference(
			REFERENCE_GALLERY, media,
			ref -> role.equals(ref.getAttribute(ATTRIBUTE_ROLE)),
			ref -> ref.setAttribute(ATTRIBUTE_ROLE, role)
		);
	}

	/**
	 * Creates the key of a category partition.
	 *
	 * @param category the category
	 * @return the key
	 */
	@Nonnull
	static RepresentativeReferenceKey category(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category));
	}

	/**
	 * Creates the key of a reflected product partition of the category.
	 *
	 * @param product the product
	 * @return the key
	 */
	@Nonnull
	static RepresentativeReferenceKey product(int product) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_PRODUCTS, product));
	}

	/**
	 * Returns a collection of the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	static EntityCollection collectionOf(@Nonnull Evita evita, @Nonnull String entityType) {
		return (EntityCollection) evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Returns a collection of the committed catalog of an engine, if the catalog holds one of the type.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type
	 * @return the collection, `null` when the catalog holds none of the type
	 */
	@Nullable
	static EntityCollection findCollectionOf(@Nonnull Evita evita, @Nonnull String entityType) {
		return (EntityCollection) evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElse(null);
	}

	/**
	 * Returns the primary key of a partition in the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the partition primary key, or `-1` when there is no such partition
	 */
	static int partitionOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityIndex partition = collectionOf(evita, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key)
		);
		return partition == null ? -1 : partition.getPrimaryKey();
	}

	/**
	 * Returns the registry of a reference in the committed catalog of an engine.
	 *
	 * @param evita         the engine
	 * @param entityType    the entity type owning the reference
	 * @param scope         the scope
	 * @param referenceName the reference
	 * @return the registry, `null` when the reference has no referenced-type entity index in the scope
	 */
	@Nullable
	static VirtualReferencePartitionIndex registryOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndex typeIndex = collectionOf(evita, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
		return typeIndex == null ?
			null : assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
	}

	/**
	 * Describes the owner rows and tuples of a reference in both scopes of the committed catalog of an engine.
	 *
	 * @param evita         the engine
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @return the description
	 */
	@Nonnull
	static String describeRows(@Nonnull Evita evita, @Nonnull String entityType, @Nonnull String referenceName) {
		final StringBuilder description = new StringBuilder(256);
		for (final Scope scope : Scope.values()) {
			final VirtualReferencePartitionIndex registry = registryOf(evita, entityType, scope, referenceName);
			description.append(scope).append(':');
			if (registry != null) {
				registry.forEachEntry(
					VirtualReferencePartitionSlot.PARTITION_OWNER,
					(partition, payload) -> description.append(' ').append(partition).append("->")
						.append(NumberUtils.unpackHigh(payload)).append('/').append(NumberUtils.unpackLow(payload))
				);
				registry.forEachEntry(
					VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE,
					(partition, payload) -> description.append(' ').append(partition).append("#")
						.append(Arrays.toString(registry.getTuple((int) payload)))
				);
				description.append(" entries=").append(VirtualReferencePartitionIndexEntries.count(registry));
			}
			description.append('\n');
		}
		return description.toString();
	}

	/**
	 * Returns the primary key of the registered partition of a key in the committed catalog of an engine - the
	 * partition whose registry row names the key's target and representative attribute values.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the partition primary key, or `-1` when the registry holds no row of the key
	 */
	static int registeredPartitionOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final VirtualReferencePartition partition = findRegistered(evita, entityType, scope, key);
		return partition == null ? -1 : partition.getPrimaryKey();
	}

	/**
	 * Finds the registered partition of a key in the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the partition, or `null` when the registry holds no row of the key
	 */
	@Nullable
	static VirtualReferencePartition findRegistered(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityCollection collection = findCollectionOf(evita, entityType);
		if (collection == null) {
			return null;
		}
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, key.referenceName())
		);
		return typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex ?
			ReferencePartitions.findRegisteredForWrite(referencedTypeIndex, key) : null;
	}

	/**
	 * Asserts that no collection of the committed catalog of the engine holds an index object of a partition of a
	 * reference whose partitions may be virtual - in a history whose every partition has a single owner, the catalog
	 * writing virtually represents every one of them by its row.
	 *
	 * @param evita the engine of the catalog writing virtually
	 */
	static void assertNoPartitionObjectOfVirtualReference(@Nonnull Evita evita) {
		for (final String entityType : ENTITY_TYPES) {
			final EntityCollection collection = findCollectionOf(evita, entityType);
			if (collection == null) {
				continue;
			}
			final List<String> found = new ArrayList<>(2);
			VirtualPartitionInvariants.forEachPartitionObjectOfVirtualReference(
				collection,
				(scope, partition) -> found.add(partition.getIndexKey() + " (" + partition.getPrimaryKey() + ")")
			);
			assertTrue(
				found.isEmpty(),
				"collection `" + entityType + "` must hold no partition object of a reference whose partitions " +
					"may be virtual, but holds " + found
			);
		}
	}
}
