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
import lombok.Data;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Variant of a product mapped as POJO class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.PRODUCT)
@Data
@RequiredArgsConstructor
public abstract class AbstractProductPojo implements Serializable {
	@Serial private static final long serialVersionUID = -4105423434415501748L;
	@PrimaryKeyRef private final int id;
	@Attribute(name = DataGenerator.ATTRIBUTE_CODE) private final String code;
	@Attribute(name = DataGenerator.ATTRIBUTE_NAME) private final String[] names;
	@AttributeRef(DataGenerator.ATTRIBUTE_EAN) private final String eanAsDifferentProperty;
	@Attribute(name = DataGenerator.ATTRIBUTE_QUANTITY) private final BigDecimal quantity;
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_ENUM) private final TestEnum testEnum;
	@Attribute(name = DataGenerator.ATTRIBUTE_ALIAS) private final Boolean alias;
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_OPTIONAL_AVAILABILITY) private final Boolean available;
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS) private final String[] marketsAttribute;
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS) private final List<String> marketsAttributeAsList;
	@Attribute(name = AbstractHundredProductsFunctionalTest.ATTRIBUTE_MARKETS) private final Set<String> marketsAttributeAsSet;
	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS) private final String[] markets;
	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS) private final List<String> marketsAsList;
	@AssociatedData(name = AbstractHundredProductsFunctionalTest.ASSOCIATED_DATA_MARKETS) private final Set<String> marketsAsSet;
	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES) private final ReferencedFileSet referencedFileSet;
	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES) private final ReferencedFileSet referencedFileSetAsDifferentProperty;
	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_LABELS) private final Labels labels;
	@ReferenceRef(Entities.CATEGORY) private final Collection<Integer> categoryIds;
	@ReferenceRef(Entities.CATEGORY) private final List<Integer> categoryIdsAsList;
	@ReferenceRef(Entities.CATEGORY) private final Set<Integer> categoryIdsAsSet;
	@ReferenceRef(Entities.CATEGORY) private final int[] categoryIdsAsArray;
	@ReferenceRef(Entities.CATEGORY) private final Collection<EntityReference> categoryReferences;
	@ReferenceRef(Entities.CATEGORY) private final List<EntityReference> categoryReferencesAsList;
	@ReferenceRef(Entities.CATEGORY) private final Set<EntityReference> categoryReferencesAsSet;
	@ReferenceRef(Entities.CATEGORY) private final EntityReference[] categoryReferencesAsArray;
	@ReferenceRef(Entities.CATEGORY) private final Collection<AbstractProductCategoryPojo> productCategories;
	@ReferenceRef(Entities.CATEGORY) private final List<AbstractProductCategoryPojo> productCategoriesAsList;
	@ReferenceRef(Entities.CATEGORY) private final Set<AbstractProductCategoryPojo> productCategoriesAsSet;
	@ReferenceRef(Entities.CATEGORY) private final AbstractProductCategoryPojo[] productCategoriesAsArray;
	@ReferenceRef(Entities.CATEGORY) private final Collection<AbstractCategoryPojo> categories;
	@ReferenceRef(Entities.CATEGORY) private final List<AbstractCategoryPojo> categoriesAsList;
	@ReferenceRef(Entities.CATEGORY) private final Set<AbstractCategoryPojo> categoriesAsSet;
	@ReferenceRef(Entities.CATEGORY) private final AbstractCategoryPojo[] categoriesAsArray;
	@PriceForSale private final PriceContract priceForSale;
	@PriceForSale private final PriceContract[] allPricesForSale;
	@Price(priceList = "basic") private final PriceContract basicPrice;
	@Price private final Collection<PriceContract> allPrices;
	@Price private final List<PriceContract> allPricesAsList;
	@Price private final Set<PriceContract> allPricesAsSet;
	@Price private final PriceContract[] allPricesAsArray;

	public AbstractProductPojo(int id) {
		this.id = id;
		code = null;
		names = null;
		eanAsDifferentProperty = null;
		quantity = null;
		testEnum = null;
		alias = false;
		markets = null;
		marketsAsList = null;
		marketsAsSet = null;
		referencedFileSet = null;
		referencedFileSetAsDifferentProperty = null;
		categoryIds = Collections.emptyList();
		categoryIdsAsList = Collections.emptyList();
		categoryIdsAsSet = Collections.emptySet();
		categoryIdsAsArray = new int[0];
		available = null;
		categoryReferences = Collections.emptyList();
		categoryReferencesAsList = Collections.emptyList();
		categoryReferencesAsSet = Collections.emptySet();
		categoryReferencesAsArray = new EntityReference[0];
		productCategories = Collections.emptyList();
		productCategoriesAsList = Collections.emptyList();
		productCategoriesAsSet = Collections.emptySet();
		productCategoriesAsArray = new AbstractProductCategoryPojo[0];
		categories = Collections.emptyList();
		categoriesAsList = Collections.emptyList();
		categoriesAsSet = Collections.emptySet();
		categoriesAsArray = new AbstractCategoryPojo[0];
		priceForSale = null;
		allPricesForSale = null;
		basicPrice = null;
		allPrices = Collections.emptyList();
		allPricesAsList = Collections.emptyList();
		allPricesAsSet = Collections.emptySet();
		allPricesAsArray = new PriceContract[0];
		labels = null;
		marketsAttribute = new String[0];
		marketsAttributeAsSet = Collections.emptySet();
		marketsAttributeAsList = Collections.emptyList();
	}

	/**
	 * If method is abstract evitaDB will try to implement it by matching its name to an entity property.
	 */
	public abstract TestEntity getEntityType();

	/**
	 * Implemented methods will remain intact (not intercepted), unless their field is annotated by evitaDB annotation.
	 */
	@Nonnull
	public String getEan() {
		return "computed EAN";
	}
}
