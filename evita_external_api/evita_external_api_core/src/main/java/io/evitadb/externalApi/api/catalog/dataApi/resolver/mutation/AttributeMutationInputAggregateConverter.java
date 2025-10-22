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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;

import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.ApplyDeltaAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.AttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.RemoveAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.UpsertAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationInputAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationInputAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationInputAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationInputAggregateDescriptor.THIS_INPUT;
import static io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationInputAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link MutationInputAggregateConverter} for converting aggregates of {@link AttributeMutation}s.
 * into list of individual mutations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class AttributeMutationInputAggregateConverter
	extends MutationInputAggregateConverter<AttributeMutation, AttributeMutationConverter<AttributeMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, AttributeMutationConverter<AttributeMutation>> converters = createHashMap(5);

	public AttributeMutationInputAggregateConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);

		registerConverter(UPSERT_ATTRIBUTE_MUTATION.name(), new UpsertAttributeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(REMOVE_ATTRIBUTE_MUTATION.name(), new RemoveAttributeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(APPLY_DELTA_ATTRIBUTE_MUTATION.name(), new ApplyDeltaAttributeMutationConverter(objectMapper, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return THIS_INPUT.name();
	}
}
