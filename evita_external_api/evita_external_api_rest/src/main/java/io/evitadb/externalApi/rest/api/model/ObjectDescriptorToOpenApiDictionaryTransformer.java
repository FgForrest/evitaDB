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
import io.evitadb.externalApi.rest.api.openApi.OpenApiDictionary;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

/**
 * Transforms API-independent {@link ObjectDescriptor} to {@link io.evitadb.externalApi.rest.api.openApi.OpenApiDictionary}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ObjectDescriptorToOpenApiDictionaryTransformer implements ObjectDescriptorTransformer<OpenApiDictionary.Builder> {

	@Override
	public OpenApiDictionary.Builder apply(ObjectDescriptor objectDescriptor) {
		final OpenApiDictionary.Builder unionBuilder = OpenApiDictionary.newDictionary();

		if (objectDescriptor.isNameStatic()) {
			unionBuilder.name(objectDescriptor.name());
		}
		unionBuilder.description(objectDescriptor.description());

		Assert.isPremiseValid(
			objectDescriptor.staticFields().isEmpty(),
			() -> new OpenApiBuildingError("Union object cannot have properties.")
		);

		return unionBuilder;
	}
}
