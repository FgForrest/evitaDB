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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Functions;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * A generated catalog of the shape of a production `relatedProducts` reference: products referencing other products,
 * duplicates told apart by a representative relation type, with roughly a third of the partitions owned by a single
 * product (so read virtually) and the rest shared by several - and about a thousand owners holding rows of both kinds.
 *
 * - {@link #PRODUCT_COUNT} products, each with one to six related products, drawn from a hot set of
 *   {@link #HOT_TARGET_COUNT} products with probability 0.87 and uniformly from all products otherwise - including
 *   {@link #MISSING_TARGET_COUNT} primary keys past the last product, which the managed collection never holds
 *   (references to missing managed targets);
 * - the reference allows duplicates told apart by the representative `relationType`, is indexed for filtering in both
 *   scopes and faceted in both, and carries a sortable `order` and a localized sortable `label`, a compound over the
 *   relation type and the order and a localized compound over the order and the label. About 15 % of the rows carry
 *   neither value (value-less rows);
 * - every product has an English name, every other one a German one too, and the label of a row is set in the locales
 *   its owner has;
 * - every tenth product is archived after the catalog goes live, so both scopes hold rows of both kinds;
 * - every product sits in one of the first {@link #POPULAR_CATEGORY_COUNT} categories through `categories` (indexed for
 *   filtering in the live scope, with a filterable `priority` and a group - one of the first {@link #GROUP_COUNT}
 *   categories - whose index is kept), and products `10k + 1` sit additionally in category `POPULAR_CATEGORY_COUNT + k`
 *   alone - single-owner partitions of `categories` - for `k` up to {@link #SINGLETON_CATEGORY_COUNT}, with a priority
 *   and a group other than those of their row of a popular category. Categories reflect the reference as `products`,
 *   inheriting its attributes, so a `referenceHaving` over the products of a category is answered from the product
 *   side by the bidirectional rewrite;
 * - products sit in the hierarchical `section` collection through `sections` (indexed for filtering in both scopes,
 *   hierarchy indexed in both scopes): live products in a tree of {@link #LIVE_SECTION_COUNT} live sections - each in
 *   one of the popular leaves, products `10k + 3` additionally alone in a singleton leaf, one product alone in an inner
 *   node and another alone in a root - and archived products in a tree of archived sections from
 *   {@link #ARCHIVED_SECTION_ROOT} on, shaped the same way, so roughly half of the partitions of the hierarchy nodes
 *   are single-owner in each scope (see {@link #sectionsOf(int)} and {@link #sectionParentOf(int)}).
 *
 * The generator is deterministic: the same seed builds the same catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RelatedProductsShapedFixture {
	static final String ENTITY_PRODUCT = "product";
	static final String ENTITY_CATEGORY = "category";
	static final String ENTITY_SECTION = "section";
	/**
	 * Every entity type of the fixture.
	 */
	static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_SECTION);
	static final String REFERENCE_SECTIONS = "sections";
	static final String REFERENCE_CATEGORIES = "categories";
	static final String REFERENCE_PRODUCTS = "products";
	static final String ATTRIBUTE_PRIORITY = "priority";
	static final String REFERENCE_RELATED = "relatedProducts";
	static final String ATTRIBUTE_CODE = "code";
	static final String ATTRIBUTE_RANK = "rank";
	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_RELATION_TYPE = "relationType";
	static final String ATTRIBUTE_ORDER = "order";
	static final String ATTRIBUTE_LABEL = "label";
	static final String COMPOUND_TYPE_ORDER = "typeOrder";
	static final String COMPOUND_ORDER_LABEL = "orderLabel";
	static final String TYPE_ALTERNATIVE = "alternative";
	static final String TYPE_ACCESSORY = "accessory";
	static final String TYPE_SPARE = "spare";
	static final Locale LOCALE_EN = Locale.ENGLISH;
	static final Locale LOCALE_DE = Locale.GERMAN;
	/**
	 * How many products the catalog holds.
	 */
	static final int PRODUCT_COUNT = 4_000;
	/**
	 * How many products form the hot set most rows reference.
	 */
	static final int HOT_TARGET_COUNT = 700;
	/**
	 * How many primary keys past the last product rows reference - targets the managed collection never holds.
	 */
	static final int MISSING_TARGET_COUNT = 50;
	/**
	 * How many categories serve as the groups of the rows of `categories`.
	 */
	static final int GROUP_COUNT = 6;
	/**
	 * How many categories every product is spread over.
	 */
	static final int POPULAR_CATEGORY_COUNT = 40;
	/**
	 * How many categories hold a single product each.
	 */
	static final int SINGLETON_CATEGORY_COUNT = 20;
	/**
	 * Every product whose primary key is divisible by this is archived.
	 */
	static final int ARCHIVED_EVERY = 10;
	/**
	 * How many sections form the live tree: roots `1` and `2`, inner nodes `3` - `8` (three under each root) and leaves
	 * `9` - `44` (six under each inner node, the first three popular, the other three singleton).
	 */
	static final int LIVE_SECTION_COUNT = 44;
	/**
	 * The root of the tree of sections archived once the catalog is live: inner nodes `51` - `53` under it and leaves
	 * `54` - `62` (three under each inner node).
	 */
	static final int ARCHIVED_SECTION_ROOT = 50;
	/**
	 * The last section of the archived tree.
	 */
	static final int LAST_ARCHIVED_SECTION = 62;
	/**
	 * The inner live section held by every product `50k + 1` - a shared partition of an inner node.
	 */
	static final int SHARED_LIVE_INNER_SECTION = 4;
	/**
	 * The inner live section held by product {@link #SINGLETON_LIVE_INNER_OWNER} alone.
	 */
	static final int SINGLETON_LIVE_INNER_SECTION = 5;
	/**
	 * The only owner of {@link #SINGLETON_LIVE_INNER_SECTION}.
	 */
	static final int SINGLETON_LIVE_INNER_OWNER = 7;
	/**
	 * The live root held by product {@link #SINGLETON_LIVE_ROOT_OWNER} alone.
	 */
	static final int SINGLETON_LIVE_ROOT = 2;
	/**
	 * The only owner of {@link #SINGLETON_LIVE_ROOT}.
	 */
	static final int SINGLETON_LIVE_ROOT_OWNER = 17;
	/**
	 * The inner archived section held by every archived product `100k` - a shared partition of an inner node.
	 */
	static final int SHARED_ARCHIVED_INNER_SECTION = 52;
	/**
	 * The inner archived section held by product {@link #SINGLETON_ARCHIVED_INNER_OWNER} alone.
	 */
	static final int SINGLETON_ARCHIVED_INNER_SECTION = 51;
	/**
	 * The only owner of {@link #SINGLETON_ARCHIVED_INNER_SECTION}.
	 */
	static final int SINGLETON_ARCHIVED_INNER_OWNER = 40;
	/**
	 * The only owner of {@link #ARCHIVED_SECTION_ROOT}.
	 */
	static final int SINGLETON_ARCHIVED_ROOT_OWNER = 50;
	/**
	 * The popular leaves of the live tree - the first three leaves under each inner node.
	 */
	private static final int[] POPULAR_LIVE_LEAVES = IntStream.rangeClosed(9, LIVE_SECTION_COUNT)
		.filter(it -> (it - 9) % 6 < 3)
		.toArray();
	/**
	 * The singleton leaves of the live tree - the last three leaves under each inner node, each held by one product.
	 */
	private static final int[] SINGLETON_LIVE_LEAVES = IntStream.rangeClosed(9, LIVE_SECTION_COUNT)
		.filter(it -> (it - 9) % 6 >= 3)
		.toArray();
	/**
	 * The popular leaves of the archived tree - the first two leaves under each archived inner node.
	 */
	private static final int[] POPULAR_ARCHIVED_LEAVES = {54, 55, 57, 58, 60, 61};
	/**
	 * The singleton leaves of the archived tree, held by archived products `10`, `20` and `30` alone.
	 */
	private static final int[] SINGLETON_ARCHIVED_LEAVES = {56, 59, 62};
	/**
	 * The seed of the generator.
	 */
	private static final long SEED = 42L;
	/**
	 * The probability a row references the hot set.
	 */
	private static final double HOT_TARGET_PROBABILITY = 0.87;
	/**
	 * The relation types, which tell duplicates apart.
	 */
	private static final String[] RELATION_TYPES = {TYPE_ALTERNATIVE, TYPE_ACCESSORY, TYPE_SPARE};
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};

	private RelatedProductsShapedFixture() {
	}

	/**
	 * Defines the schema and writes the products - meant to run in the warm-up session of a fresh catalog whose
	 * product collection is maintained from the first write.
	 *
	 * @param session the session to write in
	 */
	static void setUp(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_SECTION)
			.withHierarchyIndexedInScope(BOTH_SCOPES)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(LOCALE_EN, LOCALE_DE)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.withAttribute(
				ATTRIBUTE_RANK, Integer.class,
				whichIs -> whichIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES)
			)
			.withAttribute(
				ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().filterableInScope(BOTH_SCOPES).nullable()
			)
			.withReferenceToEntity(
				REFERENCE_RELATED, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringInScope(BOTH_SCOPES)
					.facetedInScope(BOTH_SCOPES)
					.withAttribute(
						ATTRIBUTE_RELATION_TYPE, String.class,
						thatIs -> thatIs.filterableInScope(BOTH_SCOPES).representative()
					)
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class,
						thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES).nullable()
					)
					.withAttribute(
						ATTRIBUTE_LABEL, String.class,
						thatIs -> thatIs.localized().filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES)
							.nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_TYPE_ORDER,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_RELATION_TYPE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
						},
						thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
					)
					.withSortableAttributeCompound(
						COMPOUND_ORDER_LABEL,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
						},
						thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
					)
			)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withGroupTypeRelatedToEntity(ENTITY_CATEGORY)
					.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
			)
			.withReferenceToEntity(
				REFERENCE_SECTIONS, ENTITY_SECTION, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE)
			)
			.updateVia(session);
		final int categoryCount = POPULAR_CATEGORY_COUNT + SINGLETON_CATEGORY_COUNT;
		for (int pk = 1; pk <= categoryCount; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk).setAttribute(ATTRIBUTE_CODE, "C" + pk));
		}
		// parents precede their children in both trees
		for (int pk = 1; pk <= LAST_ARCHIVED_SECTION; pk++) {
			if (pk <= LIVE_SECTION_COUNT || pk >= ARCHIVED_SECTION_ROOT) {
				final EntityBuilder section = session.createNewEntity(ENTITY_SECTION, pk)
					.setAttribute(ATTRIBUTE_CODE, "S" + pk);
				final int parent = sectionParentOf(pk);
				if (parent > 0) {
					section.setParent(parent);
				}
				session.upsertEntity(section);
			}
		}
		final Random random = new Random(SEED);
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			session.upsertEntity(newProduct(session, pk, random));
		}
	}

	/**
	 * Returns the category product `10k + 1` sits in alone, or `0` for every other product.
	 *
	 * @param primaryKey the product primary key
	 * @return the singleton category, `0` when there is none
	 */
	static int singletonCategoryOf(int primaryKey) {
		final int k = (primaryKey - 1) / 10;
		return primaryKey % 10 == 1 && k >= 1 && k <= SINGLETON_CATEGORY_COUNT ? POPULAR_CATEGORY_COUNT + k : 0;
	}

	/**
	 * Returns the parent of a section, or `0` for a root.
	 *
	 * @param sectionPrimaryKey the section primary key
	 * @return the parent section, `0` for a root
	 */
	static int sectionParentOf(int sectionPrimaryKey) {
		if (sectionPrimaryKey <= 2 || sectionPrimaryKey == ARCHIVED_SECTION_ROOT) {
			return 0;
		} else if (sectionPrimaryKey <= 8) {
			return sectionPrimaryKey <= 5 ? 1 : 2;
		} else if (sectionPrimaryKey <= LIVE_SECTION_COUNT) {
			return 3 + (sectionPrimaryKey - 9) / 6;
		} else if (sectionPrimaryKey <= 53) {
			return ARCHIVED_SECTION_ROOT;
		} else {
			return 51 + (sectionPrimaryKey - 54) / 3;
		}
	}

	/**
	 * Returns the sections a product sits in: a live product in one popular live leaf, products `10k + 3` alone in a
	 * singleton live leaf - for an odd `k` instead of a popular leaf, so they sit in the tree only through a single-owner
	 * partition - products `50k + 1` in the shared inner node {@link #SHARED_LIVE_INNER_SECTION}, and the singleton inner
	 * node and root their only owners, the root owner nowhere else; an archived product in one popular archived leaf,
	 * the archived products `100k` in the shared inner node {@link #SHARED_ARCHIVED_INNER_SECTION}, and the archived
	 * singleton leaves, inner node and root their only owners, product `20` and the root owner nowhere else.
	 *
	 * @param primaryKey the product primary key
	 * @return the sections of the product
	 */
	@Nonnull
	static int[] sectionsOf(int primaryKey) {
		final IntStream.Builder sections = IntStream.builder();
		if (isArchived(primaryKey)) {
			final int k = primaryKey / ARCHIVED_EVERY;
			if (k != 2 && primaryKey != SINGLETON_ARCHIVED_ROOT_OWNER) {
				sections.add(POPULAR_ARCHIVED_LEAVES[k % POPULAR_ARCHIVED_LEAVES.length]);
			}
			if (k <= SINGLETON_ARCHIVED_LEAVES.length) {
				sections.add(SINGLETON_ARCHIVED_LEAVES[k - 1]);
			}
			if (primaryKey % 100 == 0) {
				sections.add(SHARED_ARCHIVED_INNER_SECTION);
			}
			if (primaryKey == SINGLETON_ARCHIVED_INNER_OWNER) {
				sections.add(SINGLETON_ARCHIVED_INNER_SECTION);
			} else if (primaryKey == SINGLETON_ARCHIVED_ROOT_OWNER) {
				sections.add(ARCHIVED_SECTION_ROOT);
			}
		} else {
			final int k = (primaryKey - 3) / 10;
			final boolean singletonLeafOwner = primaryKey % 10 == 3 && k < SINGLETON_LIVE_LEAVES.length;
			if ((!singletonLeafOwner || k % 2 == 0) && primaryKey != SINGLETON_LIVE_ROOT_OWNER) {
				sections.add(POPULAR_LIVE_LEAVES[primaryKey % POPULAR_LIVE_LEAVES.length]);
			}
			if (singletonLeafOwner) {
				sections.add(SINGLETON_LIVE_LEAVES[k]);
			}
			if (primaryKey % 50 == 1) {
				sections.add(SHARED_LIVE_INNER_SECTION);
			}
			if (primaryKey == SINGLETON_LIVE_INNER_OWNER) {
				sections.add(SINGLETON_LIVE_INNER_SECTION);
			} else if (primaryKey == SINGLETON_LIVE_ROOT_OWNER) {
				sections.add(SINGLETON_LIVE_ROOT);
			}
		}
		return sections.build().toArray();
	}

	/**
	 * Archives every {@link #ARCHIVED_EVERY}-th product and the tree of sections from {@link #ARCHIVED_SECTION_ROOT}
	 * on, children before their parents - meant to run once the catalog is live.
	 *
	 * @param session the session to write in
	 */
	static void archive(@Nonnull EvitaSessionContract session) {
		for (int pk = ARCHIVED_EVERY; pk <= PRODUCT_COUNT; pk += ARCHIVED_EVERY) {
			session.archiveEntity(ENTITY_PRODUCT, pk);
		}
		for (int pk = LAST_ARCHIVED_SECTION; pk >= ARCHIVED_SECTION_ROOT; pk--) {
			session.archiveEntity(ENTITY_SECTION, pk);
		}
	}

	/**
	 * Returns TRUE when the product is archived by {@link #archive(EvitaSessionContract)}.
	 *
	 * @param primaryKey the product primary key
	 * @return TRUE for an archived product
	 */
	static boolean isArchived(int primaryKey) {
		return primaryKey % ARCHIVED_EVERY == 0;
	}

	/**
	 * Creates one product with its generated related products.
	 *
	 * @param session    the session to create the entity in
	 * @param primaryKey the product primary key
	 * @param random     the generator, advanced by the product
	 * @return the entity builder
	 */
	@Nonnull
	private static EntityBuilder newProduct(@Nonnull EvitaSessionContract session, int primaryKey, @Nonnull Random random) {
		final boolean german = primaryKey % 2 == 0;
		final EntityBuilder product = session
			.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "P" + primaryKey)
			.setAttribute(ATTRIBUTE_RANK, primaryKey * 10)
			.setAttribute(ATTRIBUTE_NAME, LOCALE_EN, "product " + primaryKey);
		if (german) {
			product.setAttribute(ATTRIBUTE_NAME, LOCALE_DE, "Produkt " + primaryKey);
		}
		product.setReference(
			REFERENCE_CATEGORIES, 1 + (primaryKey - 1) % POPULAR_CATEGORY_COUNT,
			ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, primaryKey % 10).setGroup(1 + primaryKey % GROUP_COUNT)
		);
		final int singletonCategory = singletonCategoryOf(primaryKey);
		if (singletonCategory > 0) {
			final int k = singletonCategory - POPULAR_CATEGORY_COUNT;
			product.setReference(
				REFERENCE_CATEGORIES, singletonCategory,
				ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, (k + 5) % 10).setGroup(1 + k % GROUP_COUNT)
			);
		}
		for (final int section : sectionsOf(primaryKey)) {
			product.setReference(REFERENCE_SECTIONS, section);
		}
		final int rowCount = 1 + random.nextInt(6);
		final Set<String> taken = new HashSet<>(rowCount * 2);
		for (int i = 0; i < rowCount; i++) {
			final int target = random.nextDouble() < HOT_TARGET_PROBABILITY ?
				1 + random.nextInt(HOT_TARGET_COUNT) :
				1 + random.nextInt(PRODUCT_COUNT + MISSING_TARGET_COUNT);
			final String relationType = RELATION_TYPES[random.nextInt(RELATION_TYPES.length)];
			final boolean valueLess = random.nextInt(100) < 15;
			final int order = random.nextInt(100);
			if (!taken.add(target + "/" + relationType)) {
				// the owner already relates to the target in this role - one reference per key
				continue;
			}
			product.setOrUpdateReference(
				REFERENCE_RELATED, target, Functions.alwaysFalse(),
				ref -> {
					ref.setAttribute(ATTRIBUTE_RELATION_TYPE, relationType);
					if (!valueLess) {
						ref.setAttribute(ATTRIBUTE_ORDER, order);
						ref.setAttribute(ATTRIBUTE_LABEL, LOCALE_EN, "l" + order);
						if (german) {
							ref.setAttribute(ATTRIBUTE_LABEL, LOCALE_DE, "g" + order);
						}
					}
				}
			);
		}
		return product;
	}

}
