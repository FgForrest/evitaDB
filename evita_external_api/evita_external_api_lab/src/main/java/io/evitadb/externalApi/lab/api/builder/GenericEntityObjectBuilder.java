/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.lab.api.builder;

import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.lab.api.model.entity.GenericEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.EntityObjectBuilder;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Extension of {@link EntityObjectBuilder} for building generic entities without known entity or catalog schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GenericEntityObjectBuilder {

	@Nonnull private final LabApiBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(PriceDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildEntityObject());
	}

	@Nonnull
	private OpenApiObject buildEntityObject() {
		final String objectName = GenericEntityDescriptor.THIS.name();

		// build specific entity object
		final OpenApiObject.Builder entityObject = GenericEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.description("Generic evitaDB-wise entity containing all possible data.");

		entityObject.property(GenericEntityDescriptor.LOCALES.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.ALL_LOCALES.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.PARENT_ENTITY.to(propertyBuilderTransformer).type(typeRefTo(objectName)));
		entityObject.property(GenericEntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.PRICE_FOR_SALE.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.PRICES.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.ATTRIBUTES.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.ASSOCIATED_DATA.to(propertyBuilderTransformer));
		entityObject.property(GenericEntityDescriptor.REFERENCES.to(propertyBuilderTransformer));

		return entityObject.build();
	}
}
