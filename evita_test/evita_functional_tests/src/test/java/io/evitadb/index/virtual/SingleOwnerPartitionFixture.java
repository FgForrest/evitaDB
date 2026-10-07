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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;

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
 * {@link #setUpWithEveryReadRoute(EvitaSessionContract)} extends the fixture so that every query route of the
 * virtual reading has something to read in the query lines of {@link DifferentialEngines}: owner locales, a
 * hierarchical and grouped `sections` reference with a sortable and a chained attribute, and enough products in
 * the shared category for the bidirectional rewrite to answer `categories`' reflection from its counterpart.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class SingleOwnerPartitionFixture {
	static final String ENTITY_PRODUCT = "product";
	static final String ENTITY_CATEGORY = "category";
	static final String ENTITY_MEDIA = "media";
	static final String ENTITY_BRAND = "brand";
	/**
	 * Every entity type of the fixture.
	 */
	static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_MEDIA, ENTITY_BRAND);
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
	static final String REFERENCE_CATEGORIES = "categories";
	static final String REFERENCE_GALLERY = "gallery";
	static final String REFERENCE_BRAND = "brand";
	static final String REFERENCE_PRODUCTS = "products";
	static final String ATTRIBUTE_CODE = "code";
	static final String ATTRIBUTE_RANK = "rank";
	static final String ATTRIBUTE_ORDER = "orderInCategory";
	static final String ATTRIBUTE_ROLE = "role";
	static final String HISTOGRAM_ORDER = "orderInCategoryHistogram";
	static final String ROLE_GALLERY = "gallery";
	static final String ROLE_DETAIL = "detail";
	static final String ROLE_THUMBNAIL = "thumbnail";
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
		setUp(session, categories, false);
	}

	/**
	 * Defines the schema with the reflected `products` reference on the category, the referenced entities and the
	 * initial products.
	 *
	 * @param session the session to write in
	 */
	static void setUpWithReflectedProducts(@Nonnull EvitaSessionContract session) {
		setUp(session, categories -> { }, true);
	}

	/**
	 * Defines the schema with the reflected `products` reference on the category and everything the query lines of
	 * {@link DifferentialEngines} need to exercise every query route of the virtual reading, and the data:
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
	static void setUpWithEveryReadRoute(@Nonnull EvitaSessionContract session) {
		setUpWithReflectedProducts(session);
		session.defineEntitySchema(ENTITY_SECTION)
			.withHierarchyIndexedInScope(Scope.LIVE, Scope.ARCHIVED)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_SECTIONS, ENTITY_SECTION, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
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
	 * Defines the schema, the referenced entities and the initial products.
	 *
	 * @param session           the session to write in
	 * @param categories        extends the definition of the category reference
	 * @param reflectedProducts TRUE to define the reflected `products` reference on the category
	 */
	private static void setUp(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReferenceSchemaBuilder> categories,
		boolean reflectedProducts
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
					whichIs
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
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
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
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
					whichIs -> whichIs
						.withCardinality(Cardinality.ZERO_OR_MORE)
						.withAttributesInherited()
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
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
	 * Creates product `primaryKey`: in category `primaryKey` (and the shared one, for the first three products),
	 * showing media `primaryKey` in two roles and the shared media as a thumbnail, of the fixture's brand.
	 *
	 * @param session    the session to create the entity in
	 * @param primaryKey the product primary key, which also picks its own category and media
	 * @return the entity builder
	 */
	@Nonnull
	static EntityBuilder newProduct(@Nonnull EvitaSessionContract session, int primaryKey) {
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
	static Consumer<EvitaSessionContract> updateProduct(
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
}
