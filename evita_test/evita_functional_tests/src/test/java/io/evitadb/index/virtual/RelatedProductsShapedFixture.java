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
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Functions;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

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
 * - every tenth product is archived after the catalog goes live, so both scopes hold rows of both kinds.
 *
 * The generator is deterministic: the same seed builds the same catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RelatedProductsShapedFixture {
	static final String ENTITY_PRODUCT = "product";
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
	 * Every product whose primary key is divisible by this is archived.
	 */
	static final int ARCHIVED_EVERY = 10;
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
			.updateVia(session);
		final Random random = new Random(SEED);
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			session.upsertEntity(newProduct(session, pk, random));
		}
	}

	/**
	 * Archives every {@link #ARCHIVED_EVERY}-th product - meant to run once the catalog is live.
	 *
	 * @param session the session to write in
	 */
	static void archive(@Nonnull EvitaSessionContract session) {
		for (int pk = ARCHIVED_EVERY; pk <= PRODUCT_COUNT; pk += ARCHIVED_EVERY) {
			session.archiveEntity(ENTITY_PRODUCT, pk);
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
