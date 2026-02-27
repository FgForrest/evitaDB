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
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.generator.DataGenerator;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Example interface mapping a product category reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProductCategoryInterface extends Serializable, SealedInstance<ProductCategoryInterface, ProductCategoryInterfaceEditor> {

	@ReferencedEntity
	int getPrimaryKey();

	@AttributeRef(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY)
	Long getOrderInCategory() throws ContextMissingException;

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_CATEGORY_SHADOW)
	boolean isShadow() throws ContextMissingException;

	@AttributeRef(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY)
	OptionalLong getOrderInCategoryIfPresent();

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_CATEGORY_LABEL)
	String getLabel() throws ContextMissingException;

	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_CATEGORY_LABEL)
	String getLabel(@Nonnull Locale locale) throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	CategoryInterface getCategory() throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	Optional<CategoryInterface> getCategoryIfPresentAndFetched();

	@ReferencedEntity
	@Nonnull
	Optional<CategoryInterface> getCategoryIfPresent() throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	EntityReference getCategoryReference() throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	Optional<EntityReference> getCategoryReferenceIfPresent();

	@ReferencedEntity
	int getCategoryReferencePrimaryKey() throws ContextMissingException;

	@ReferencedEntity
	OptionalInt getCategoryReferencePrimaryKeyIfPresent();

}
