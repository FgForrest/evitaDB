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

import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptorTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion.Builder;
import lombok.RequiredArgsConstructor;

/**
 * Transforms API-independent {@link UnionDescriptor} to {@link OpenApiUnion}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class UnionDescriptorToOpenApiUnionTransformer implements UnionDescriptorTransformer<Builder> {

	@Override
	public OpenApiUnion.Builder apply(UnionDescriptor unionDescriptor) {
		final OpenApiUnion.Builder unionBuilder = OpenApiUnion.newUnion();

		unionBuilder.name(unionDescriptor.name());
		unionBuilder.description(unionDescriptor.description());
		unionBuilder.type(OpenApiObjectUnionType.ONE_OF);
		unionBuilder.discriminator(unionDescriptor.discriminator().name());

		unionDescriptor.types().forEach(typeDescriptor -> {
			unionBuilder.object(OpenApiTypeReference.typeRefTo(typeDescriptor.name()));
		});

		return unionBuilder;
	}
}
