/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

import static io.evitadb.api.AbstractHundredProductsFunctionalTest.ATTRIBUTE_PRODUCT_LABEL;
import static io.evitadb.api.AbstractHundredProductsFunctionalTest.ATTRIBUTE_RELATION_TYPE;

/**
 * Example interface mapping a related product reference.
 *
 * This interface exposes attributes and referenced-entity accessors for a single
 * related-product reference. It mirrors ProductCategoryInterface so tests can
 * exercise the same behaviors across different reference types.
 *
 * - Attribute accessors throw ContextMissingException when the attribute wasn't fetched.
 * - Optional/IfPresent variants do not throw and signal missing data via Optionals.
 * - Referenced entity and its reference/primary key can be retrieved in multiple ways.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface RelatedProductInterface extends Serializable, SealedInstance<RelatedProductInterface, RelatedProductInterfaceEditor> {

	@ReferencedEntity
	int getPrimaryKey();

	@AttributeRef(ATTRIBUTE_RELATION_TYPE)
	String getRelationType() throws ContextMissingException;

	@AttributeRef(ATTRIBUTE_PRODUCT_LABEL)
	String getLabel() throws ContextMissingException;

	@AttributeRef(ATTRIBUTE_PRODUCT_LABEL)
	String getLabel(@Nonnull Locale locale) throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	ProductInterface getRelatedProduct() throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	Optional<ProductInterface> getRelatedProductIfPresentAndFetched();

	@ReferencedEntity
	@Nonnull
	Optional<ProductInterface> getRelatedProductIfPresent() throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	EntityReference getRelatedProductReference() throws ContextMissingException;

	@ReferencedEntity
	@Nonnull
	Optional<EntityReference> getRelatedProductReferenceIfPresent();

	@ReferencedEntity
	int getRelatedProductReferencePrimaryKey() throws ContextMissingException;

	@ReferencedEntity
	OptionalInt getRelatedProductReferencePrimaryKeyIfPresent();

}
