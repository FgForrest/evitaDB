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
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Locale;

/**
 * Two small catalogs whose products own single-owner and shared partitions of a category reference carrying the
 * sortable values a partition orders its owners by:
 *
 * - {@link #setUpTypes(EvitaSessionContract)} - a sortable attribute of every type a sort index normalizes in its own
 *   way, two compounds, and a filterable and a unique attribute that are not sortable; the values of the partitions
 *   tie on purpose (see {@link #fillReference(ReferenceBuilder, int)}), so equal values of different partitions must
 *   form one block;
 * - {@link #setUpChains(EvitaSessionContract)} - an order and both chain types, reflected on the category with the
 *   attributes inherited, so every chain is read by the owner's record on one side and by the referenced entity's
 *   record on the other.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SortValueAndChainFixture {
	private static final String ENTITY_PRODUCT = SingleOwnerPartitionFixture.ENTITY_PRODUCT;
	private static final String ENTITY_CATEGORY = SingleOwnerPartitionFixture.ENTITY_CATEGORY;
	private static final String REFERENCE_CATEGORIES = SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
	private static final String REFERENCE_PRODUCTS = SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
	/**
	 * Filterable and sortable - the partition's sort index is a view over its filter index.
	 */
	static final String ATTRIBUTE_ORDER = "order";
	/**
	 * Sortable only, scaled to two decimal places in the sort index.
	 */
	static final String ATTRIBUTE_PRICE = "price";
	static final String ATTRIBUTE_VALID_FROM = "validFrom";
	/**
	 * Filterable and sortable.
	 */
	static final String ATTRIBUTE_RELEASED_AT = "releasedAt";
	static final String ATTRIBUTE_OPENS_AT = "opensAt";
	/**
	 * Localized.
	 */
	static final String ATTRIBUTE_LABEL = "label";
	static final String ATTRIBUTE_LANGUAGE = "language";
	static final String ATTRIBUTE_CURRENCY = "currency";
	/**
	 * Sortable, two indexed decimal places, written at an intrinsic scale of one.
	 */
	static final String ATTRIBUTE_SPAN = "span";
	/**
	 * Filterable only - never in a sort index, never mirrored.
	 */
	private static final String ATTRIBUTE_FILTERED = "filtered";
	/**
	 * Unique only - never in a sort index, never mirrored.
	 */
	private static final String ATTRIBUTE_UNIQUE_CODE = "uniqueCode";
	/**
	 * A `Predecessor` - the reflected reference inherits it as a `ReferencedEntityPredecessor`.
	 */
	static final String ATTRIBUTE_PRODUCT_ORDER = "productOrder";
	/**
	 * A `ReferencedEntityPredecessor` - the reflected reference inherits it as a `Predecessor`.
	 */
	static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	/**
	 * A localized compound: the label, then the price descending with nulls first.
	 */
	static final String COMPOUND_LABEL_AND_PRICE = "labelAndPrice";
	/**
	 * A compound of the types a sort index wraps or rescales: the language, the currency descending with nulls first,
	 * the validity, the span and the exact price.
	 */
	static final String COMPOUND_TYPED = "typed";
	/**
	 * The category the first two products share - a partition with two owners and therefore no row.
	 */
	static final int SHARED_CATEGORY = 100;
	/**
	 * Categories no product of the fixture references.
	 */
	static final int FREE_CATEGORY = 11;
	/**
	 * How many products the fixture starts with, each the only owner of the category of its own primary key.
	 */
	static final int PRODUCT_COUNT = 4;
	/**
	 * The schema scale of the price and the span.
	 */
	static final int DECIMAL_PLACES = 2;
	static final Locale CZECH = Locale.forLanguageTag("cs");
	/**
	 * A Czech word written precomposed - the decomposed form of {@link #DECOMPOSED_LABEL} is the same string to a sort
	 * index.
	 */
	private static final String COMPOSED_LABEL = "Žluťoučký";
	private static final String DECOMPOSED_LABEL = Normalizer.normalize(COMPOSED_LABEL, Normalizer.Form.NFD);

	private SortValueAndChainFixture() {
	}

	/**
	 * Defines products in two locales with a category reference indexed for filtering that carries a sortable
	 * attribute of every type a sort index normalizes in its own way, two compounds, and a filterable and a unique
	 * attribute that are not sortable; then creates product `n` (1 to {@link #PRODUCT_COUNT}) as the only owner of
	 * category `n`, the first two also sharing {@link #SHARED_CATEGORY} with values of every type (see
	 * {@link #sharedVariantOf(int)}).
	 *
	 * @param session the session to write in
	 */
	public static void setUpTypes(@Nonnull EvitaSessionContract session) {
		setUpTypesIndexedAs(session, ReferenceIndexType.FOR_FILTERING);
	}

	/**
	 * Same as {@link #setUpTypes(EvitaSessionContract)}, with the category reference indexed in both scopes by the
	 * given index type instead of for filtering.
	 *
	 * @param session   the session to write in
	 * @param indexType the index type of the category reference
	 */
	public static void setUpTypesIndexedAs(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceIndexType indexType
	) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, CZECH)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> SingleOwnerPartitionFixture.indexedInBothScopes(whichIs, indexType)
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(
						ATTRIBUTE_PRICE, BigDecimal.class,
						thatIs -> thatIs.sortable().indexDecimalPlaces(DECIMAL_PLACES).nullable()
					)
					.withAttribute(ATTRIBUTE_VALID_FROM, OffsetDateTime.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_RELEASED_AT, LocalDateTime.class, thatIs -> thatIs.filterable().sortable().nullable()
					)
					.withAttribute(ATTRIBUTE_OPENS_AT, LocalTime.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().sortable().nullable())
					.withAttribute(ATTRIBUTE_LANGUAGE, Locale.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(ATTRIBUTE_CURRENCY, Currency.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_SPAN, BigDecimalNumberRange.class,
						thatIs -> thatIs.sortable().indexDecimalPlaces(DECIMAL_PLACES).nullable()
					)
					.withAttribute(ATTRIBUTE_FILTERED, Integer.class, thatIs -> thatIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_UNIQUE_CODE, String.class, thatIs -> thatIs.unique().nullable())
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_PRICE,
						new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
					.withSortableAttributeCompound(
						COMPOUND_TYPED,
						new AttributeElement(ATTRIBUTE_LANGUAGE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_CURRENCY, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement(ATTRIBUTE_VALID_FROM, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_SPAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
			)
			.updateVia(session);
		for (int pk = 1; pk <= FREE_CATEGORY; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY));
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			final int productPrimaryKey = pk;
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, pk)
				.setReference(REFERENCE_CATEGORIES, pk, ref -> fillReference(ref, productPrimaryKey));
			if (pk <= 2) {
				product.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY, ref -> fillReference(ref, sharedVariantOf(productPrimaryKey))
				);
			}
			session.upsertEntity(product);
		}
	}

	/**
	 * Defines products whose category reference carries an order and both chain types, reflected on the category with
	 * its attributes inherited; then creates product `n` (1 to 3) as the only owner of category `n`, chained after
	 * product `n - 1` and after category `n - 1`.
	 *
	 * @param session the session to write in
	 */
	public static void setUpChains(@Nonnull EvitaSessionContract session) {
		setUpChainsIndexedAs(session, ReferenceIndexType.FOR_FILTERING);
	}

	/**
	 * Same as {@link #setUpChains(EvitaSessionContract)}, with the category reference and its reflection indexed in
	 * both scopes by the given index type instead of for filtering.
	 *
	 * @param session   the session to write in
	 * @param indexType the index type of the category reference and its reflection
	 */
	public static void setUpChainsIndexedAs(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceIndexType indexType
	) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> SingleOwnerPartitionFixture.indexedInBothScopes(whichIs, indexType)
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(ATTRIBUTE_PRODUCT_ORDER, Predecessor.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class,
						thatIs -> thatIs.sortable().nullable()
					)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> SingleOwnerPartitionFixture.indexedInBothScopes(
					whichIs.withCardinality(Cardinality.ZERO_OR_MORE).withAttributesInherited(), indexType
				)
			)
			.updateVia(session);
		for (int pk = 1; pk <= FREE_CATEGORY; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		for (int pk = 1; pk <= 3; pk++) {
			final int productPrimaryKey = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setReference(
						REFERENCE_CATEGORIES, pk,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, productPrimaryKey)
							.setAttribute(
								ATTRIBUTE_PRODUCT_ORDER,
								productPrimaryKey == 1 ? Predecessor.HEAD : new Predecessor(productPrimaryKey - 1)
							)
							.setAttribute(
								ATTRIBUTE_CATEGORY_ORDER,
								productPrimaryKey == 1 ?
									ReferencedEntityPredecessor.HEAD :
									new ReferencedEntityPredecessor(productPrimaryKey - 1)
							)
					)
			);
		}
	}

	/**
	 * Returns the variant of the values product `n` has in {@link #SHARED_CATEGORY} - one no single-owner partition of
	 * the fixture uses.
	 *
	 * @param product the product, 1 or 2
	 * @return the variant
	 */
	static int sharedVariantOf(int product) {
		return PRODUCT_COUNT + product;
	}

	/**
	 * Sets every attribute of a category reference to the variant `n` of its values. The variants tie on purpose:
	 * variants 1 and 3, and 2 and 4, share their order; 1 and 2 share their price and their instant of validity at
	 * different offsets; 3 and 4 share their label, once composed and once decomposed.
	 *
	 * @param reference the reference to fill
	 * @param variant   the variant of the values
	 * @return the reference
	 */
	@Nonnull
	static ReferenceBuilder fillReference(@Nonnull ReferenceBuilder reference, int variant) {
		return reference
			.setAttribute(ATTRIBUTE_ORDER, variant % 2 + 1)
			.setAttribute(ATTRIBUTE_PRICE, priceOf(variant))
			.setAttribute(ATTRIBUTE_VALID_FROM, validFromOf(variant))
			.setAttribute(
				ATTRIBUTE_RELEASED_AT, LocalDateTime.of(2026, 3, 29, 2, 30, 0, 123_456_789).plusDays(variant)
			)
			.setAttribute(ATTRIBUTE_OPENS_AT, LocalTime.of(8, variant, 0, 999_999_999))
			.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, labelOf(variant))
			.setAttribute(ATTRIBUTE_LANGUAGE, variant % 2 == 0 ? Locale.ENGLISH : CZECH)
			.setAttribute(ATTRIBUTE_CURRENCY, Currency.getInstance(variant % 2 == 0 ? "CZK" : "EUR"))
			.setAttribute(ATTRIBUTE_SPAN, spanOf(variant))
			.setAttribute(ATTRIBUTE_FILTERED, variant)
			.setAttribute(ATTRIBUTE_UNIQUE_CODE, "U" + variant + "-" + reference.getReferencedPrimaryKey());
	}

	/**
	 * Removes every attribute of a category reference.
	 *
	 * @param reference the reference to clear
	 */
	static void clear(@Nonnull ReferenceBuilder reference) {
		reference
			.removeAttribute(ATTRIBUTE_ORDER)
			.removeAttribute(ATTRIBUTE_PRICE)
			.removeAttribute(ATTRIBUTE_VALID_FROM)
			.removeAttribute(ATTRIBUTE_RELEASED_AT)
			.removeAttribute(ATTRIBUTE_OPENS_AT)
			.removeAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH)
			.removeAttribute(ATTRIBUTE_LANGUAGE)
			.removeAttribute(ATTRIBUTE_CURRENCY)
			.removeAttribute(ATTRIBUTE_SPAN)
			.removeAttribute(ATTRIBUTE_FILTERED)
			.removeAttribute(ATTRIBUTE_UNIQUE_CODE);
	}

	/**
	 * Returns the price of a variant - variants 1 and 2 have the same price at different scales, variant 3 a negative
	 * one rounded at the schema scale.
	 *
	 * @param variant the variant
	 * @return the price
	 */
	@Nonnull
	private static BigDecimal priceOf(int variant) {
		return switch (variant) {
			case 1 -> new BigDecimal("1.25");
			case 2 -> new BigDecimal("1.250");
			case 3 -> new BigDecimal("-1.235");
			default -> new BigDecimal(variant).movePointLeft(1);
		};
	}

	/**
	 * Returns the validity of a variant - variants 1 and 2 are the same instant at different offsets, variant 3 is
	 * before 1970 and finer than a millisecond.
	 *
	 * @param variant the variant
	 * @return the validity
	 */
	@Nonnull
	private static OffsetDateTime validFromOf(int variant) {
		return switch (variant) {
			case 1 -> OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2));
			case 2 -> OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC);
			case 3 -> OffsetDateTime.of(1969, 12, 31, 23, 59, 59, 999_999, ZoneOffset.UTC);
			default -> OffsetDateTime.of(2026, 6, variant % 28 + 1, 0, 0, 0, 123_456_789, ZoneOffset.ofHours(-5));
		};
	}

	/**
	 * Returns the English label of a variant - variants 3 and 4 are the same word, composed and decomposed.
	 *
	 * @param variant the variant
	 * @return the label
	 */
	@Nonnull
	private static String labelOf(int variant) {
		return switch (variant) {
			case 3 -> COMPOSED_LABEL;
			case 4 -> DECOMPOSED_LABEL;
			default -> "label " + variant;
		};
	}

	/**
	 * Returns the span of a variant, written at an intrinsic scale of one decimal place.
	 *
	 * @param variant the variant
	 * @return the span
	 */
	@Nonnull
	static BigDecimalNumberRange spanOf(int variant) {
		return BigDecimalNumberRange.between(
			new BigDecimal(variant).add(new BigDecimal("0.5")), new BigDecimal(variant).add(new BigDecimal("3.4"))
		);
	}
}
