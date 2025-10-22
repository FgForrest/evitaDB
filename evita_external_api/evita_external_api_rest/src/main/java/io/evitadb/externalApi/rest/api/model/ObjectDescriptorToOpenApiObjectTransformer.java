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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Transforms API-independent {@link ObjectDescriptor} to {@link OpenApiObject}.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ObjectDescriptorToOpenApiObjectTransformer implements ObjectDescriptorTransformer<OpenApiObject.Builder> {

	@Nonnull
	private final PropertyDescriptorTransformer<OpenApiProperty.Builder> propertyDescriptorTransformer;

	@Override
	public OpenApiObject.Builder apply(ObjectDescriptor objectDescriptor) {
		final OpenApiObject.Builder objectBuilder = OpenApiObject.newObject();

		if (objectDescriptor.isNameStatic()) {
			objectBuilder.name(objectDescriptor.name());
		}
		objectBuilder.description(objectDescriptor.description());

		// static properties of object
		objectDescriptor.staticProperties()
			.stream()
			.map(this.propertyDescriptorTransformer)
			.forEach(objectBuilder::property);

		return objectBuilder;
	}
}
