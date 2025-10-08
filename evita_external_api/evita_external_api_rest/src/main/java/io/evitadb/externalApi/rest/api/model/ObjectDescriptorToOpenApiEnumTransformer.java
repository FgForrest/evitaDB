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
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Transforms API-independent {@link ObjectDescriptor} to {@link OpenApiEnum}.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ObjectDescriptorToOpenApiEnumTransformer implements ObjectDescriptorTransformer<OpenApiEnum.Builder> {

	/**
	 * Serialized enum values to as items.
	 */
	@Nonnull private final Set<String> enumValues;

	@Override
	public OpenApiEnum.Builder apply(ObjectDescriptor objectDescriptor) {
		final OpenApiEnum.Builder enumBuilder = OpenApiEnum.newEnum();

		if (objectDescriptor.isNameStatic()) {
			enumBuilder.name(objectDescriptor.name());
		}
		enumBuilder.description(objectDescriptor.description());

		this.enumValues.forEach(enumBuilder::item);

		return enumBuilder;
	}
}
