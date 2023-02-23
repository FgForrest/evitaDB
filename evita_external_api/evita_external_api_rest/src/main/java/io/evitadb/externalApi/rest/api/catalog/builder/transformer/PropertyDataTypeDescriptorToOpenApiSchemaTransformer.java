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

import io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDataTypeDescriptorToOpenApiSchemaTransformer.PropertyDataType;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createArraySchemaOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;

/**
 * Transforms {@link PropertyDataTypeDescriptor} to concrete {@link Schema<Object>}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class PropertyDataTypeDescriptorToOpenApiSchemaTransformer implements PropertyDataTypeDescriptorTransformer<PropertyDataType> {

	@Override
	public PropertyDataType apply(@Nonnull PropertyDataTypeDescriptor typeDescriptor) {
		if (typeDescriptor instanceof PrimitivePropertyDataTypeDescriptor primitiveType) {
			return new PropertyDataType(
				SchemaCreator.createSchemaByJavaType(primitiveType.javaType()),
				primitiveType.nonNull()
			);
		} else if (typeDescriptor instanceof ObjectPropertyDataTypeDescriptor objectType) {
			Schema<Object> dataType = createReferenceSchema(objectType.objectReference().name());
			if (objectType.list()) {
				dataType = createArraySchemaOf(dataType);
			}

			return new PropertyDataType(
				dataType,
				objectType.nonNull()
			);
		} else {
			throw new OpenApiSchemaBuildingError("Unsupported property data type `" + typeDescriptor.getClass().getName() + "`.");
		}
	}

	public record PropertyDataType(@Nonnull Schema<Object> schema, boolean required) {}
}
