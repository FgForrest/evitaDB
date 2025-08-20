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

import io.evitadb.api.AbstractHundredProductsFunctionalTest.TestEnum;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Concrete class of a product mapped as POJO class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ProductPojo extends AbstractProductPojo {

	public ProductPojo(int id, String code, String[] names, String eanAsDifferentProperty, BigDecimal quantity, TestEnum testEnum, Boolean alias, Boolean available, String[] marketsAttribute, List<String> marketsAttributeAsList, Set<String> marketsAttributeAsSet, String[] markets, List<String> marketsAsList, Set<String> marketsAsSet, ReferencedFileSet referencedFileSet, ReferencedFileSet referencedFileSetAsDifferentProperty, Labels labels, Collection<Integer> categoryIds, List<Integer> categoryIdsAsList, Set<Integer> categoryIdsAsSet, int[] categoryIdsAsArray, Collection<EntityReference> categoryReferences, List<EntityReference> categoryReferencesAsList, Set<EntityReference> categoryReferencesAsSet, EntityReference[] categoryReferencesAsArray, Collection<AbstractProductCategoryPojo> productCategories, List<AbstractProductCategoryPojo> productCategoriesAsList, Set<AbstractProductCategoryPojo> productCategoriesAsSet, AbstractProductCategoryPojo[] productCategoriesAsArray, Collection<AbstractCategoryPojo> categories, List<AbstractCategoryPojo> categoriesAsList, Set<AbstractCategoryPojo> categoriesAsSet, AbstractCategoryPojo[] categoriesAsArray, PriceContract priceForSale, PriceContract[] allPricesForSale, PriceContract basicPrice, Collection<PriceContract> allPrices, List<PriceContract> allPricesAsList, Set<PriceContract> allPricesAsSet, PriceContract[] allPricesAsArray) {
		super(id, code, names, eanAsDifferentProperty, quantity, testEnum, alias, available, marketsAttribute, marketsAttributeAsList, marketsAttributeAsSet, markets, marketsAsList, marketsAsSet, referencedFileSet, referencedFileSetAsDifferentProperty, labels, categoryIds, categoryIdsAsList, categoryIdsAsSet, categoryIdsAsArray, categoryReferences, categoryReferencesAsList, categoryReferencesAsSet, categoryReferencesAsArray, productCategories, productCategoriesAsList, productCategoriesAsSet, productCategoriesAsArray, categories, categoriesAsList, categoriesAsSet, categoriesAsArray, priceForSale, allPricesForSale, basicPrice, allPrices, allPricesAsList, allPricesAsSet, allPricesAsArray);
	}

	public ProductPojo(int id) {
		super(id);
	}

	@Override
	public TestEntity getEntityType() {
		return TestEntity.PRODUCT;
	}

}
