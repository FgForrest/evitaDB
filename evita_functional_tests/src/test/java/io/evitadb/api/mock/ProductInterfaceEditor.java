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

import io.evitadb.api.AbstractHundredProductsFunctionalTest.TestEnum;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Example product interface for proxying.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProductInterfaceEditor extends EntityClassifier {

	void setId(int id);

	void setEntityType(@Nonnull TestEntity entityType);

	void setCode(@Nonnull String code);

	void setName(@Nonnull String name);

	void setName(Locale locale, @Nonnull String name);

	void setEanAsDifferentProperty(@Nonnull String eanAsDifferentProperty);

	void setQuantity(@Nonnull BigDecimal quantity);

	void setQuantityAsDifferentProperty(@Nonnull BigDecimal quantityAsDifferentProperty);

	void setEnum(TestEnum testEnum);

	void setAlias(boolean alias);

	void setOptionallyAvailable(boolean optionallyAvailable);

	void setReferencedFileSet(ReferencedFileSet referencedFileSet);

	void setReferencedFileSetAsDifferentProperty(ReferencedFileSet referencedFileSetAsDifferentProperty);

	void setCategoryIds(Collection<Integer> categoryIds);

	void setCategoryIdsAsList(List<Integer> categoryIdsAsList);

	void setCategoryIdsAsSet(Set<Integer> categoryIdsAsSet);

	void setCategoryIdsAsArray(int[] categoryIdsAsArray);

	void setCategoryReferences(Collection<EntityReference> categoryReferences);

	void setCategoryReferencesAsList(List<EntityReference> categoryReferencesAsList);

	void setCategoryReferencesAsSet(Set<EntityReference> categoryReferencesAsSet);

	void setCategoryReferencesAsArray(EntityReference[] categoryReferencesAsArray);

	void setProductCategories(Collection<ProductCategoryInterface> productCategories);

	void setProductCategoriesAsList(List<ProductCategoryInterface> productCategoriesAsList);

	void setProductCategoriesAsSet(Set<ProductCategoryInterface> productCategoriesAsSet);

	void setProductCategoriesAsArray(ProductCategoryInterface[] productCategoriesAsArray);

	void setCategories(Collection<CategoryInterface> categories);

	void setCategoriesAsList(List<CategoryInterface> categoriesAsList);

	void setCategoriesAsSet(Set<CategoryInterface> categoriesAsSet);

	void setCategoriesAsArray(CategoryInterface[] categoriesAsArray);

	void setPriceForSale(PriceContract priceForSale);

	void setPriceForSale(String priceList, @Nonnull Currency currency);

	void setPriceForSale(String priceList, @Nonnull Currency currency, @Nonnull OffsetDateTime validNow);

	void setAllPricesForSale(PriceContract[] allPricesForSale);

	void setAllPricesForSale(String priceList, PriceContract[] allPricesForSale);

	void setAllPricesForSale(@Nonnull Currency currency, PriceContract[] allPricesForSale);

	void setAllPricesForSale(String priceList, @Nonnull Currency currency, PriceContract[] allPricesForSale);

	void setBasicPrice(PriceContract basicPrice);

	void setAllPrices(Collection<PriceContract> allPrices);

	void setAllPricesAsList(List<PriceContract> allPricesAsList);

	void setAllPricesAsSet(Set<PriceContract> allPricesAsSet);

	void setAllPricesAsArray(PriceContract[] allPricesAsArray);

	void setLabels(Labels labels);

	void setMarkets(String[] markets);

	void setMarketsAsSet(Set<String> marketsAsSet);

	void setMarketsAsList(List<String> marketsAsList);

	void setMarketsAttribute(String[] marketsAttribute);

	void setMarketsAttributeAsSet(Set<String> marketsAttributeAsSet);

	void setMarketsAttributeAsList(List<String> marketsAttributeAsList);

}
