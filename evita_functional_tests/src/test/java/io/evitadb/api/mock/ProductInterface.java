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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Example product interface for proxying.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
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

	@Attribute(name = DataGenerator.ATTRIBUTE_QUANTITY)
	@Nonnull
	BigDecimal getQuantity();

	@AttributeRef(DataGenerator.ATTRIBUTE_QUANTITY)
	@Nonnull
	BigDecimal getQuantityAsDifferentProperty();

	@Attribute(name = DataGenerator.ATTRIBUTE_ALIAS)
	boolean isAlias();

	@AssociatedData(name = DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	ReferencedFileSet getReferencedFileSet();

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
	List<CategoryInterface> getCategoriesAsList();

	@ReferenceRef(Entities.CATEGORY)
	Set<CategoryInterface> getCategoriesAsSet();

	@ReferenceRef(Entities.CATEGORY)
	CategoryInterface[] getCategoriesAsArray();

}
