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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
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
 * - `brand` - indexed for filtering and partitioning, so it is never shadowed by the registry.
 *
 * Products also carry a unique `code` and a numeric, filterable and sortable `rank`.
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
	static final String REFERENCE_CATEGORIES = "categories";
	static final String REFERENCE_GALLERY = "gallery";
	static final String REFERENCE_BRAND = "brand";
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
