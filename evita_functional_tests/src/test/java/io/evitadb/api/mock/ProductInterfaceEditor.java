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
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
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
public interface ProductInterfaceEditor extends ProductInterface, InstanceEditor<ProductInterface> {

	ProductInterfaceEditor setId(int id);

	ProductInterfaceEditor setCode(@Nonnull String code);

	ProductInterfaceEditor setName(@Nonnull String name);

	ProductInterfaceEditor setName(Locale locale, @Nonnull String name);

	ProductInterfaceEditor setQuantity(@Nonnull BigDecimal quantity);

	@AttributeRef(DataGenerator.ATTRIBUTE_PRIORITY)
	ProductInterfaceEditor setPriority(Long priority);

	ProductInterfaceEditor setEnum(TestEnum testEnum);

	ProductInterfaceEditor setAlias(boolean alias);

	ProductInterfaceEditor setOptionallyAvailable(boolean optionallyAvailable);

	ProductInterfaceEditor setReferencedFileSet(ReferencedFileSet referencedFileSet);

	ProductInterfaceEditor setReferencedFileSetAsDifferentProperty(ReferencedFileSet referencedFileSetAsDifferentProperty);

	ProductInterfaceEditor setCategoryIds(Collection<Integer> categoryIds);

	ProductInterfaceEditor setCategoryIdsAsList(List<Integer> categoryIdsAsList);

	ProductInterfaceEditor setCategoryIdsAsSet(Set<Integer> categoryIdsAsSet);

	ProductInterfaceEditor setCategoryIdsAsArray(int[] categoryIdsAsArray);

	ProductInterfaceEditor setCategoryReferences(Collection<EntityReference> categoryReferences);

	ProductInterfaceEditor setCategoryReferencesAsList(List<EntityReference> categoryReferencesAsList);

	ProductInterfaceEditor setCategoryReferencesAsSet(Set<EntityReference> categoryReferencesAsSet);

	ProductInterfaceEditor setCategoryReferencesAsArray(EntityReference[] categoryReferencesAsArray);

	ProductInterfaceEditor setProductCategories(Collection<ProductCategoryInterface> productCategories);

	ProductInterfaceEditor setProductCategoriesAsList(List<ProductCategoryInterface> productCategoriesAsList);

	ProductInterfaceEditor setProductCategoriesAsSet(Set<ProductCategoryInterface> productCategoriesAsSet);

	ProductInterfaceEditor setProductCategoriesAsArray(ProductCategoryInterface[] productCategoriesAsArray);

	ProductInterfaceEditor setCategories(Collection<CategoryInterface> categories);

	ProductInterfaceEditor setCategoriesAsList(List<CategoryInterface> categoriesAsList);

	ProductInterfaceEditor setCategoriesAsSet(Set<CategoryInterface> categoriesAsSet);

	ProductInterfaceEditor setCategoriesAsArray(CategoryInterface[] categoriesAsArray);

	ProductInterfaceEditor setLabels(Labels labels, Locale locale);

	ProductInterfaceEditor setMarkets(String[] markets);

	ProductInterfaceEditor setMarketsAsSet(Set<String> marketsAsSet);

	ProductInterfaceEditor setMarketsAsList(List<String> marketsAsList);

	ProductInterfaceEditor setMarketsAttribute(String[] marketsAttribute);

	ProductInterfaceEditor setMarketsAttributeAsSet(Set<String> marketsAttributeAsSet);

	ProductInterfaceEditor setMarketsAttributeAsList(List<String> marketsAttributeAsList);

	ProductInterfaceEditor setBasicPrice(PriceContract basicPrice);

	ProductInterfaceEditor setBasicPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		String currencyCode, int priceId,
		DateTimeRange validIn,
		Integer innerRecordId
	);

	ProductInterfaceEditor setBasicPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		Currency currency, int priceId
	);

	ProductInterfaceEditor setPrice(PriceContract price);

	ProductInterfaceEditor setPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		String priceList, String currencyCode, int priceId,
		DateTimeRange validIn,
		Integer innerRecordId
	);

	ProductInterfaceEditor setPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		String priceList, Currency currency, int priceId
	);

	ProductInterfaceEditor setAllPrices(Collection<PriceContract> allPrices);

	ProductInterfaceEditor setAllPricesAsList(List<PriceContract> allPricesAsList);

	ProductInterfaceEditor setAllPricesAsSet(Set<PriceContract> allPricesAsSet);

	ProductInterfaceEditor setAllPricesAsArray(PriceContract[] allPricesAsArray);

}
