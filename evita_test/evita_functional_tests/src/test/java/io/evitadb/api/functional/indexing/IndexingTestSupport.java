/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared helper interface for entity indexing integration tests. Provides static utility methods
 * and constants commonly needed across the indexing test suite.
 *
 * Follows the same pattern as {@link io.evitadb.test.EvitaTestSupport}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface IndexingTestSupport {

	// Common attribute name constants
	String ATTRIBUTE_CODE = "code";
	String ATTRIBUTE_NAME = "name";
	String ATTRIBUTE_DESCRIPTION = "description";
	String ATTRIBUTE_EAN = "ean";
	String ATTRIBUTE_BRAND_NAME = "brandName";
	String ATTRIBUTE_BRAND_DESCRIPTION = "brandDescription";
	String ATTRIBUTE_BRAND_EAN = "brandEan";
	String ATTRIBUTE_CATEGORY_PRIORITY = "categoryPriority";
	String ATTRIBUTE_CATEGORY_MARKET = "market";
	String ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED = "notInherited";
	String ATTRIBUTE_PRODUCT_CATEGORY_INHERITED = "inherited";
	String ATTRIBUTE_PRODUCT_CATEGORY_VARIANT = "variant";

	// Common locale and currency constants
	Locale LOCALE_CZ = new Locale("cs", "CZ");
	Currency CURRENCY_CZK = Currency.getInstance("CZK");
	Currency CURRENCY_EUR = Currency.getInstance("EUR");
	Currency CURRENCY_GBP = Currency.getInstance("GBP");

	// Common price list constants
	String PRICE_LIST_BASIC = "basic";
	String PRICE_LIST_VIP = "vip";

	// Common reference name constants
	String REFERENCE_PRODUCT_CATEGORY = "productCategory";
	String REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY = "productsInCategory";

	// Pre-built attribute schema for EAN attribute assertions
	AttributeSchema ATTRIBUTE_EAN_SCHEMA = AttributeSchema._internalBuild(
		ATTRIBUTE_EAN, String.class, false
	);

	// Pre-built attribute schema for categoryPriority reference attribute assertions
	AttributeSchema ATTRIBUTE_CATEGORY_PRIORITY_SCHEMA = AttributeSchema._internalBuild(
		ATTRIBUTE_CATEGORY_PRIORITY, Long.class, false
	);

	/**
	 * Retrieves the global entity index for the given collection in the default (LIVE) scope.
	 *
	 * @param collection the entity collection to query
	 * @return the global entity index, or null if not found
	 */
	@Nullable
	static EntityIndex getGlobalIndex(@Nonnull EntityCollectionContract collection) {
		return getGlobalIndex(collection, Scope.LIVE);
	}

	/**
	 * Retrieves the global entity index for the given collection in the specified scope.
	 *
	 * @param collection the entity collection to query
	 * @param scope      the scope to search in
	 * @return the global entity index, or null if not found
	 */
	@Nullable
	static EntityIndex getGlobalIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope
	) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
	}

	/**
	 * Retrieves the referenced entity index for the given collection, entity type, and record id
	 * in the default (LIVE) scope.
	 *
	 * @param collection the entity collection to query
	 * @param entityType the referenced entity type name
	 * @param recordId   the primary key of the referenced entity
	 * @return the referenced entity index, or null if not found
	 */
	@Nullable
	static EntityIndex getReferencedEntityIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String entityType,
		int recordId
	) {
		return getReferencedEntityIndex(collection, Scope.LIVE, entityType, recordId);
	}

	/**
	 * Retrieves the referenced entity index for the given collection, scope, entity type, and record id.
	 *
	 * @param collection the entity collection to query
	 * @param scope      the scope to search in
	 * @param entityType the referenced entity type name
	 * @param recordId   the primary key of the referenced entity
	 * @return the referenced entity index, or null if not found
	 */
	@Nullable
	static EntityIndex getReferencedEntityIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String entityType,
		int recordId
	) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY,
				scope,
				new RepresentativeReferenceKey(new ReferenceKey(entityType, recordId))
			)
		);
	}

	/**
	 * Retrieves the referenced entity type index for the given collection, scope, and reference name.
	 *
	 * @param collection    the entity collection to query
	 * @param scope         the scope to search in
	 * @param referenceName the reference schema name
	 * @return the referenced entity type index, or null if not found
	 */
	@Nullable
	static EntityIndex getReferencedEntityTypeIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
	}

	/**
	 * Retrieves the referenced group entity index for the given collection, scope, reference name,
	 * and referenced entity primary key.
	 *
	 * @param collection         the entity collection to query
	 * @param scope              the scope to search in
	 * @param referenceName      the reference schema name
	 * @param referencedEntityPk the primary key of the referenced entity
	 * @return the referenced group entity index, or null if not found
	 */
	@Nullable
	static EntityIndex getReferencedGroupEntityIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int referencedEntityPk
	) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY,
				scope,
				new RepresentativeReferenceKey(new ReferenceKey(referenceName, referencedEntityPk))
			)
		);
	}

	/**
	 * Retrieves the referenced group entity type index for the given collection, scope, and reference name.
	 *
	 * @param collection    the entity collection to query
	 * @param scope         the scope to search in
	 * @param referenceName the reference schema name
	 * @return the referenced group entity type index, or null if not found
	 */
	@Nullable
	static EntityIndex getReferencedGroupEntityTypeIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName)
		);
	}

	/**
	 * Asserts that the given entity index contains expected data (unique, filter, sort indexes
	 * and price index) for the given record id.
	 *
	 * @param categoryIndex the entity index to verify
	 * @param recordId      the expected record id in the indexes
	 */
	static void assertDataWasPropagated(@Nonnull EntityIndex categoryIndex, int recordId) {
		assertNotNull(categoryIndex);
		assertTrue(
			categoryIndex.getUniqueIndex(null, ATTRIBUTE_EAN_SCHEMA, null)
				.getRecordIds().contains(recordId)
		);
		assertTrue(
			categoryIndex.getFilterIndex(null, ATTRIBUTE_EAN_SCHEMA, null)
				.getAllRecords().contains(recordId)
		);
		assertTrue(ArrayUtils.contains(
			categoryIndex.getSortIndex(null, ATTRIBUTE_EAN_SCHEMA, null)
				.getSortedRecords(), recordId
		));
		assertTrue(
			categoryIndex.getPriceIndex(PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE)
				.getIndexedPriceEntityIds()
				.contains(recordId)
		);
		// EUR price is not indexed
		assertNull(
			categoryIndex.getPriceIndex(PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE)
		);
	}

	/**
	 * Asserts the price values on an entity match the expected values.
	 *
	 * @param updatedInstance the entity with prices
	 * @param priceId         the price id
	 * @param priceList       the price list name
	 * @param currency        the currency
	 * @param priceWithoutTax the expected price without tax
	 * @param taxRate         the expected tax rate
	 * @param priceWithTax    the expected price with tax
	 * @param indexed         whether the price should be indexed
	 */
	static void assertPrice(
		@Nonnull PricesContract updatedInstance,
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		final PriceContract price = updatedInstance.getPrice(priceId, priceList, currency).orElseGet(
			() -> fail("Price not found!"));
		assertEquals(priceWithoutTax, price.priceWithoutTax());
		assertEquals(taxRate, price.taxRate());
		assertEquals(priceWithTax, price.priceWithTax());
		assertEquals(indexed, price.indexed());
	}

	/**
	 * Simple serializable class used to verify associated data roundtrip with custom types.
	 */
	@RequiredArgsConstructor
	class ProductStockAvailability implements Serializable {
		@Serial private static final long serialVersionUID = 373668161042101104L;

		@Getter private final Integer available;
	}
}
