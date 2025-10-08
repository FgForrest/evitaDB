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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;

import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.ApplyDeltaAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.AttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.RemoveAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.UpsertAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.DelegatingMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class DelegatingAttributeMutationConverter extends DelegatingMutationConverter<AttributeMutation, AttributeMutationConverter<AttributeMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends AttributeMutation>, AttributeMutationConverter<AttributeMutation>> converters = createHashMap(5);

	public DelegatingAttributeMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);

		registerConverter(UpsertAttributeMutation.class, new UpsertAttributeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(RemoveAttributeMutation.class, new RemoveAttributeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ApplyDeltaAttributeMutation.class, new ApplyDeltaAttributeMutationConverter(objectMapper, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return AttributeMutation.class.getSimpleName();
	}
}
