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
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Price;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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

	ProductInterfaceEditor setBrand(int brandId);

	ProductInterfaceEditor setBrand(BrandInterface brand);

	@ReferenceRef(Entities.BRAND)
	ProductInterfaceEditor setNewBrand(@CreateWhenMissing Consumer<BrandInterfaceEditor> brandConsumer);

	@ReferenceRef(Entities.BRAND)
	ProductInterfaceEditor updateBrand(Consumer<BrandInterfaceEditor> brandConsumer);

	@ReferenceRef(Entities.BRAND)
	@CreateWhenMissing
	BrandInterfaceEditor getOrCreateBrand();

	@ReferenceRef(Entities.BRAND)
	@RemoveWhenExists
	ProductInterfaceEditor removeBrand();

	@ReferenceRef(Entities.PARAMETER)
	ProductInterfaceEditor setParameter(int parameterId, @CreateWhenMissing Consumer<ProductParameterInterfaceEditor> parameterEditor);

	@ReferenceRef(Entities.PARAMETER)
	ProductInterfaceEditor updateParameter(int parameterId, Consumer<ProductParameterInterfaceEditor> parameterEditor);

	@ReferenceRef(Entities.PARAMETER)
	@CreateWhenMissing
	ProductParameterInterfaceEditor getOrCreateParameter(int parameterId);

	@ReferenceRef(Entities.CATEGORY)
	ProductInterfaceEditor addProductCategory(int categoryId, @CreateWhenMissing Consumer<ProductCategoryInterfaceEditor> productCategoryEditor);

	@ReferenceRef(Entities.CATEGORY)
	@RemoveWhenExists
	ProductInterfaceEditor removeProductCategoryById(int categoryId);

	ProductInterfaceEditor setLabels(Labels labels, Locale locale);

	ProductInterfaceEditor setMarkets(String[] markets);

	@AssociatedDataRef(AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS)
	ProductInterfaceEditor setMarketsAsVarArg(String... markets);

	ProductInterfaceEditor setMarketsAsList(List<String> marketsAsList);

	ProductInterfaceEditor setMarketsAttribute(String[] marketsAttribute);

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS)
	ProductInterfaceEditor setMarketsAttributeAsVarArg(String... marketsAttribute);

	ProductInterfaceEditor setMarketsAttributeAsList(List<String> marketsAttributeAsList);

	@Price(priceList = "basic")
	ProductInterfaceEditor setBasicPrice(PriceContract basicPrice);

	@Price(priceList = "basic")
	ProductInterfaceEditor setBasicPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		String currencyCode, int priceId,
		DateTimeRange validIn,
		Integer innerRecordId
	);

	@Price(priceList = "basic")
	ProductInterfaceEditor setBasicPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		Currency currency, int priceId
	);

	@Price
	ProductInterfaceEditor setPrice(PriceContract price);

	@Price
	ProductInterfaceEditor setPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		String priceList, String currencyCode, int priceId,
		DateTimeRange validIn,
		Integer innerRecordId
	);

	@Price
	ProductInterfaceEditor setPrice(
		BigDecimal priceWithoutTax, BigDecimal priceWithTax, BigDecimal taxRate,
		String priceList, Currency currency, int priceId
	);

	@Price
	ProductInterfaceEditor setAllPricesAsList(List<PriceContract> allPricesAsList);

	@Price
	ProductInterfaceEditor setAllPricesAsArray(PriceContract... allPricesAsArray);

	@Price
	@RemoveWhenExists
	ProductInterfaceEditor removePricesById(int priceId);

	@Price
	@RemoveWhenExists
	ProductInterfaceEditor removePricesById(String priceList);

	@Price
	@RemoveWhenExists
	ProductInterfaceEditor removePricesByCurrency(Currency currency);

	@Price
	@RemoveWhenExists
	ProductInterfaceEditor removePrice(int priceId, String priceList, Currency currency);

	@Price(priceList = "basic")
	@RemoveWhenExists
	ProductInterfaceEditor removeBasicPrice(int priceId, Currency currency);

	@Price
	@RemoveWhenExists
	ProductInterfaceEditor removePrice(PriceContract price);

	@ReferenceRef(Entities.STORE)
	ProductInterfaceEditor addStore(int storeId);

	@ReferenceRef(Entities.STORE)
	ProductInterfaceEditor setStoresByIds(List<Integer> storeIds);

	@ReferenceRef(Entities.STORE)
	ProductInterfaceEditor setStores(List<StoreInterface> storeIds);

	@ReferenceRef(Entities.STORE)
	ProductInterfaceEditor setStoresByIds(int... storeId);

	@ReferenceRef(Entities.STORE)
	ProductInterfaceEditor setStores(StoreInterface... store);

}