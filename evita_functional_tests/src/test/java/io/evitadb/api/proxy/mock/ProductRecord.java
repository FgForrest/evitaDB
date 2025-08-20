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
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.Price;
import io.evitadb.api.requestResponse.data.annotation.PriceForSale;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Variant of a product mapped as Java record.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.PRODUCT)
public record ProductRecord(
	@PrimaryKeyRef int id,
	TestEntity entityType,
	@Attribute(name = DataGenerator.ATTRIBUTE_CODE) String code,
	@Attribute(name = DataGenerator.ATTRIBUTE_NAME) String[] names,
	@AttributeRef(DataGenerator.ATTRIBUTE_EAN) String eanAsDifferentProperty,
	@Attribute(name = DataGenerator.ATTRIBUTE_QUANTITY) BigDecimal quantity,
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_ENUM) TestEnum testEnum,
	@Attribute(name = DataGenerator.ATTRIBUTE_ALIAS) boolean alias,
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_OPTIONAL_AVAILABILITY) Boolean available,
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS) String[] marketsAttribute,
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS) List<String> marketsAttributeAsList,
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS) Set<String> marketsAttributeAsSet,
	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS) String[] markets,
	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS) List<String> marketsAsList,
	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS) Set<String> marketsAsSet,
	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES) ReferencedFileSet referencedFileSet,
	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES) ReferencedFileSet referencedFileSetAsDifferentProperty,
	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_LABELS) Labels labels,
	@ReferenceRef(Entities.CATEGORY) Collection<Integer> categoryIds,
	@ReferenceRef(Entities.CATEGORY) List<Integer> categoryIdsAsList,
	@ReferenceRef(Entities.CATEGORY) Set<Integer> categoryIdsAsSet,
	@ReferenceRef(Entities.CATEGORY) int[] categoryIdsAsArray,
	@ReferenceRef(Entities.CATEGORY) Collection<EntityReference> categoryReferences,
	@ReferenceRef(Entities.CATEGORY) List<EntityReference> categoryReferencesAsList,
	@ReferenceRef(Entities.CATEGORY) Set<EntityReference> categoryReferencesAsSet,
	@ReferenceRef(Entities.CATEGORY) EntityReference[] categoryReferencesAsArray,
	@ReferenceRef(Entities.CATEGORY) Collection<ProductCategoryRecord> productCategories,
	@ReferenceRef(Entities.CATEGORY) List<ProductCategoryRecord> productCategoriesAsList,
	@ReferenceRef(Entities.CATEGORY) Set<ProductCategoryRecord> productCategoriesAsSet,
	@ReferenceRef(Entities.CATEGORY) ProductCategoryRecord[] productCategoriesAsArray,
	@ReferenceRef(Entities.CATEGORY) Collection<CategoryRecord> categories,
	@ReferenceRef(Entities.CATEGORY) List<CategoryRecord> categoriesAsList,
	@ReferenceRef(Entities.CATEGORY) Set<CategoryRecord> categoriesAsSet,
	@ReferenceRef(Entities.CATEGORY) CategoryRecord[] categoriesAsArray,
	@PriceForSale PriceContract priceForSale,
	@PriceForSale PriceContract[] allPricesForSale,
	@Price(priceList = "basic") PriceContract basicPrice,
	@Price Collection<PriceContract> allPrices,
	@Price List<PriceContract> allPricesAsList,
	@Price Set<PriceContract> allPricesAsSet,
	@Price PriceContract[] allPricesAsArray
) implements Serializable {
	@Serial private static final long serialVersionUID = -4105423434415501748L;

	/**
	 * Implemented methods will remain intact (not intercepted), unless their field is annotated by evitaDB annotation.
	 */

	@Nonnull
	public String getEan() {
		return "computed EAN";
	}
}

