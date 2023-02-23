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

package io.evitadb.externalApi.rest.api.catalog.builder.transformer;

import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDataTypeDescriptorToOpenApiSchemaTransformer.PropertyDataType;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Transforms API-independent {@link PropertyDescriptor} to OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PropertyDescriptorToOpenApiSchemaTransformer implements PropertyDescriptorTransformer<Property> {

	@Nonnull
	private final PropertyDataTypeDescriptorTransformer<PropertyDataType> propertyDataTypeDescriptorTransformer;

	@Override
	public Property apply(@Nonnull PropertyDescriptor propertyDescriptor) {
		final Schema<Object> propertySchema;
		final boolean required;

		if (propertyDescriptor.type() != null) {
			final PropertyDataType propertyDataType = propertyDataTypeDescriptorTransformer.apply(propertyDescriptor.type());
			propertySchema = propertyDataType.schema();
			required = propertyDataType.required();
		} else {
			// todo lho is this correct? if there is no type we should let the programmer handle it manually in case of OpenAPI, right?
			throw new OpenApiSchemaBuildingError("");
//			propertySchema = new Schema<>();
//			required = false;
		}

		propertySchema.name(propertyDescriptor.name());
		if (propertySchema.get$ref() == null) {
			propertySchema.description(propertyDescriptor.description());
		}

		return new Property(propertySchema, required);
	}

	public record Property(@Nonnull Schema<Object> schema, boolean required) {}
}
