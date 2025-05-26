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

package io.evitadb.externalApi.rest.api.model;

import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Transforms API-independent {@link PropertyDescriptor} to {@link OpenApiProperty}.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PropertyDescriptorToOpenApiPropertyTransformer implements PropertyDescriptorTransformer<OpenApiProperty.Builder> {

	@Nonnull
	private final PropertyDataTypeDescriptorTransformer<OpenApiSimpleType> propertyDataTypeDescriptorTransformer;

	@Override
	public OpenApiProperty.Builder apply(PropertyDescriptor propertyDescriptor) {
		final OpenApiProperty.Builder propertyBuilder = OpenApiProperty.newProperty()
			.description(propertyDescriptor.description());
		Assert.isPremiseValid(
			propertyDescriptor.defaultValue() == null,
			() -> new OpenApiBuildingError("Default values are not supported in REST API right now.")
		);

		if (propertyDescriptor.isNameStatic()) {
			propertyBuilder.name(propertyDescriptor.name());
		}
		if (propertyDescriptor.deprecate() != null) {
			propertyBuilder.deprecationNotice(propertyDescriptor.deprecate());
		}

		if (propertyDescriptor.type() != null) {
			final OpenApiSimpleType openApiType = this.propertyDataTypeDescriptorTransformer.apply(propertyDescriptor.type());
			propertyBuilder.type(openApiType);
		}

		return propertyBuilder;
	}
}
