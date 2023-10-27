/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.mock;

import io.evitadb.api.AbstractHundredProductsFunctionalTest;
import io.evitadb.api.AbstractHundredProductsFunctionalTest.TestEnum;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.*;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Example product interface for proxying.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.PRODUCT)
public interface ProductInterface extends EntityClassifier {

	@PrimaryKeyRef
	int getId();

	@Nonnull
	TestEntity getEntityType();

	@Attribute(name = DataGenerator.ATTRIBUTE_CODE)
	@Nonnull
	String getCode();

	@Attribute(name = DataGenerator.ATTRIBUTE_NAME)
	@Nonnull
	String getName();

	@Nonnull
	String getName(@Nonnull Locale locale);

	@Nonnull
	default String getEan() {
		return "computed EAN";
	}

	@AttributeRef(DataGenerator.ATTRIBUTE_EAN)
	@Nonnull
	String getEanAsDifferentProperty();

	@Attribute(name = DataGenerator.ATTRIBUTE_QUANTITY)
	@Nonnull
	BigDecimal getQuantity();

	@AttributeRef(DataGenerator.ATTRIBUTE_QUANTITY)
	@Nonnull
	BigDecimal getQuantityAsDifferentProperty();

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_ENUM)
	TestEnum getEnum();

	@Attribute(name = DataGenerator.ATTRIBUTE_ALIAS)
	boolean isAlias();

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_OPTIONAL_AVAILABILITY)
	boolean isOptionallyAvailable();

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_OPTIONAL_AVAILABILITY)
	Optional<Boolean> getOptionallyAvailable();

	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	Optional<List<String>> getMarketsIfAvailable();

	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	Optional<List<String>> getMarketsAssociatedDataIfAvailable();

	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	ReferencedFileSet getReferencedFileSet();

	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	Optional<ReferencedFileSet> getReferencedFileSetIfPresent();

	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	ReferencedFileSet getReferencedFileSetAsDifferentProperty();

	@ReferenceRef(Entities.CATEGORY)
	Collection<Integer> getCategoryIds();

	@ReferenceRef(Entities.CATEGORY)
	List<Integer> getCategoryIdsAsList();

	@ReferenceRef(Entities.CATEGORY)
	Set<Integer> getCategoryIdsAsSet();

	@ReferenceRef(Entities.CATEGORY)
	int[] getCategoryIdsAsArray();

	@ReferenceRef(Entities.CATEGORY)
	Collection<EntityReference> getCategoryReferences();

	@ReferenceRef(Entities.CATEGORY)
	List<EntityReference> getCategoryReferencesAsList();

	@ReferenceRef(Entities.CATEGORY)
	Set<EntityReference> getCategoryReferencesAsSet();

	@ReferenceRef(Entities.CATEGORY)
	EntityReference[] getCategoryReferencesAsArray();

	@ReferenceRef(Entities.CATEGORY)
	Collection<ProductCategoryInterface> getProductCategories();

	@ReferenceRef(Entities.CATEGORY)
	List<ProductCategoryInterface> getProductCategoriesAsList();

	@ReferenceRef(Entities.CATEGORY)
	Set<ProductCategoryInterface> getProductCategoriesAsSet();

	@ReferenceRef(Entities.CATEGORY)
	ProductCategoryInterface[] getProductCategoriesAsArray();

	@ReferenceRef(Entities.CATEGORY)
	Collection<CategoryInterface> getCategories();

	@ReferenceRef(Entities.CATEGORY)
	Optional<Collection<CategoryInterface>> getCategoriesIfFetched();

	@ReferenceRef(Entities.CATEGORY)
	List<CategoryInterface> getCategoriesAsList();

	@ReferenceRef(Entities.CATEGORY)
	Set<CategoryInterface> getCategoriesAsSet();

	@ReferenceRef(Entities.CATEGORY)
	CategoryInterface[] getCategoriesAsArray();

	@ReferenceRef(Entities.PARAMETER)
	Collection<Integer> getParameterIds();

	@ReferenceRef(Entities.PARAMETER)
	ParameterReferenceInterface getParameterById(int parameterId);

	@PriceForSale
	PriceContract getPriceForSale();

	@PriceForSale
	Optional<PriceContract> getPriceForSaleIfPresent();

	@PriceForSale
	PriceContract getPriceForSale(@Nonnull String priceList, @Nonnull Currency currency);

	@PriceForSale
	PriceContract getPriceForSale(@Nonnull String priceList, @Nonnull Currency currency, @Nonnull OffsetDateTime validNow);

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale();

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale(@Nonnull String priceList);

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale(@Nonnull Currency currency);

	@PriceForSaleRef
	PriceContract[] getAllPricesForSale(@Nonnull String priceList, @Nonnull Currency currency);

	@Price(priceList = "basic")
	PriceContract getBasicPrice();

	@Price(priceList = "basic")
	Optional<PriceContract> getBasicPriceIfPresent();

	@Price
	Collection<PriceContract> getAllPrices();

	@Price
	List<PriceContract> getAllPricesAsList();

	@Price
	Set<PriceContract> getAllPricesAsSet();

	@Price
	PriceContract[] getAllPricesAsArray();

	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_LABELS)
	Labels getLabels();

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	String[] getMarkets();

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	Set<String> getMarketsAsSet();

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	List<String> getMarketsAsList();

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	String[] getMarketsAttribute();

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	Set<String> getMarketsAttributeAsSet();

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	List<String> getMarketsAttributeAsList();

}
