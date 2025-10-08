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

package io.evitadb.api.proxy.mock;

import io.evitadb.api.AbstractHundredProductsFunctionalTest;
import io.evitadb.api.AbstractHundredProductsFunctionalTest.TestEnum;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.proxy.WithEntityContract;
import io.evitadb.api.proxy.WithEntitySchema;
import io.evitadb.api.proxy.WithLocales;
import io.evitadb.api.proxy.WithScope;
import io.evitadb.api.proxy.WithVersion;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.*;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.api.AbstractHundredProductsFunctionalTest.ATTRIBUTE_RELATION_TYPE;

/**
 * Example product interface for proxying.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.PRODUCT)
public interface ProductInterface extends EntityClassifier, WithEntityContract, WithEntitySchema, WithLocales, WithVersion, WithScope {

	@PrimaryKeyRef
	int getId();

	@Nonnull
	TestEntity getEntityType();

	@Attribute(name = DataGenerator.ATTRIBUTE_CODE)
	@Nonnull
	String getCode() throws ContextMissingException;

	@Attribute(name = DataGenerator.ATTRIBUTE_NAME)
	@Nonnull
	String getName() throws ContextMissingException;

	@Nonnull
	String getName(@Nonnull Locale locale) throws ContextMissingException;

	@Nonnull
	default String getEan() {
		return "computed EAN";
	}

	@AttributeRef(DataGenerator.ATTRIBUTE_EAN)
	@Nonnull
	String getEanAsDifferentProperty() throws ContextMissingException;

	@Attribute(name = DataGenerator.ATTRIBUTE_QUANTITY)
	@Nonnull
	BigDecimal getQuantity() throws ContextMissingException;

	@AttributeRef(DataGenerator.ATTRIBUTE_QUANTITY)
	@Nonnull
	BigDecimal getQuantityAsDifferentProperty() throws ContextMissingException;

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_ENUM)
	TestEnum getEnum() throws ContextMissingException;

	@Attribute(name = DataGenerator.ATTRIBUTE_ALIAS)
	boolean isAlias() throws ContextMissingException;

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_OPTIONAL_AVAILABILITY)
	boolean isOptionallyAvailable() throws ContextMissingException;

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_OPTIONAL_AVAILABILITY)
	Optional<Boolean> getOptionallyAvailable();

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	Optional<List<String>> getMarketsIfAvailable();

	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	Optional<List<String>> getMarketsAssociatedDataIfAvailable();

	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	ReferencedFileSet getReferencedFileSet() throws ContextMissingException;

	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	Optional<ReferencedFileSet> getReferencedFileSetIfPresent();

	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	ReferencedFileSet getReferencedFileSetAsDifferentProperty() throws ContextMissingException;

	@ReferenceRef(Entities.BRAND)
	BrandInterface getBrand() throws ContextMissingException;

	@ReferenceRef(Entities.BRAND)
	Integer getBrandId() throws ContextMissingException;

	@ReferenceRef(Entities.PARAMETER)
	ProductParameterInterface getParameter() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Collection<Integer> getCategoryIds() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	List<Integer> getCategoryIdsAsList() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Set<Integer> getCategoryIdsAsSet() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	int[] getCategoryIdsAsArray() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Collection<EntityReference> getCategoryReferences() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	List<EntityReference> getCategoryReferencesAsList() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Set<EntityReference> getCategoryReferencesAsSet() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	EntityReference[] getCategoryReferencesAsArray() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Collection<ProductCategoryInterface> getProductCategories() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	List<ProductCategoryInterface> getProductCategoriesAsList() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Set<ProductCategoryInterface> getProductCategoriesAsSet() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	ProductCategoryInterface[] getProductCategoriesAsArray() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Collection<CategoryInterface> getCategories() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Optional<Collection<CategoryInterface>> getCategoriesIfFetched();

	@ReferenceRef(Entities.CATEGORY)
	List<CategoryInterface> getCategoriesAsList() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	Set<CategoryInterface> getCategoriesAsSet() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	CategoryInterface[] getCategoriesAsArray() throws ContextMissingException;

	@ReferenceRef(Entities.CATEGORY)
	ProductCategoryInterface getCategoryById(int parameterId) throws ContextMissingException;

	@ReferenceRef(Entities.PARAMETER)
	ProductParameterInterface getParameterById(int parameterId) throws ContextMissingException;

	@ReferenceRef(Entities.PRODUCT)
	Collection<RelatedProductInterface> getAllRelatedProducts()
		throws ContextMissingException;

	@ReferenceRef(Entities.PRODUCT)
	RelatedProductInterface getRelatedProduct(@AttributeRef(ATTRIBUTE_RELATION_TYPE) String category)
		throws ContextMissingException;

	@ReferenceRef(Entities.PRODUCT)
	Collection<RelatedProductInterface> getRelatedProducts(@AttributeRef(ATTRIBUTE_RELATION_TYPE) String category)
		throws ContextMissingException;

	@PriceForSale
	PriceContract getPriceForSale() throws ContextMissingException;

	@PriceForSale
	Optional<PriceContract> getPriceForSaleIfPresent();

	@PriceForSale
	PriceContract getPriceForSale(@Nonnull String priceList, @Nonnull Currency currency) throws ContextMissingException;

	@PriceForSale
	PriceContract getPriceForSale(@Nonnull String priceList, @Nonnull Currency currency, @Nonnull OffsetDateTime validNow) throws ContextMissingException;

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale() throws ContextMissingException;

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale(@Nonnull String priceList) throws ContextMissingException;

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale(@Nonnull Currency currency) throws ContextMissingException;

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale(@Nonnull String priceList, @Nonnull Currency currency) throws ContextMissingException;

	@Price(priceList = "basic")
	PriceContract getBasicPrice() throws ContextMissingException;

	@Price(priceList = "basic")
	Optional<PriceContract> getBasicPriceIfPresent();

	@AccompanyingPrice
	Optional<PriceContract> getReferencePriceIfPresent();

	@AccompanyingPrice
	PriceContract getReferencePrice() throws ContextMissingException;

	@Price
	Collection<PriceContract> getAllPrices() throws ContextMissingException;

	@Price
	List<PriceContract> getAllPricesAsList() throws ContextMissingException;

	@Price
	Set<PriceContract> getAllPricesAsSet() throws ContextMissingException;

	@Price
	PriceContract[] getAllPricesAsArray() throws ContextMissingException;

	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_LABELS)
	Labels getLabels() throws ContextMissingException;

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	String[] getMarkets() throws ContextMissingException;

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	Set<String> getMarketsAsSet() throws ContextMissingException;

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	List<String> getMarketsAsList() throws ContextMissingException;

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	String[] getMarketsAttribute() throws ContextMissingException;

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	Set<String> getMarketsAttributeAsSet() throws ContextMissingException;

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	List<String> getMarketsAttributeAsList() throws ContextMissingException;

	@Price
	@Nullable
	PriceContract getPrice(String priceListName, Currency currency, int priceId) throws ContextMissingException;

	@ReferenceRef(Entities.STORE)
	int[] getStores() throws ContextMissingException;

	@ReferenceRef(Entities.STORE)
	StoreInterface getStoreById(int storeId) throws ContextMissingException;
}
