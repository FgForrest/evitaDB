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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createObjectSchema;

/**
 * Transforms API-independent {@link ObjectDescriptor} to OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ObjectDescriptorToOpenApiSchemaTransformer implements ObjectDescriptorTransformer<Schema<Object>> {

	@Nonnull
	private final PropertyDescriptorTransformer<Property> propertyDescriptorTransformer;

	@Override
	public Schema<Object> apply(@Nonnull ObjectDescriptor objectDescriptor) {
		final Schema<Object> schema = createObjectSchema();

		if (objectDescriptor.isNameStatic()) {
			schema.name(objectDescriptor.name());
		}
		schema.description(objectDescriptor.description());

		// static properties of object
		if (objectDescriptor.staticFields() != null) {
			objectDescriptor.staticFields()
				.stream()
				.map(propertyDescriptorTransformer)
				.forEach(it -> {
					schema.addProperty(it.schema().getName(), it.schema());
					if (it.required()) {
						schema.addRequiredItem(it.schema().getName());
					}
				});
		}

		return schema;
	}
}
