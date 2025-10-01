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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData.RemoveAssociatedDataMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData.UpsertAssociatedDataMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.ApplyDeltaAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.RemoveAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute.UpsertAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity.RemoveParentMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity.SetEntityScopeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity.SetParentMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price.RemovePriceMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price.SetPriceInnerRecordHandlingMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price.UpsertPriceMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference.InsertReferenceMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference.ReferenceAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference.RemoveReferenceGroupMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference.RemoveReferenceMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference.SetReferenceGroupMutationConverter;
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
public class DelegatingLocalMutationConverter extends DelegatingMutationConverter<LocalMutation<?, ?>, LocalMutationConverter<LocalMutation<?, ?>>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends LocalMutation<?, ?>>, LocalMutationConverter<LocalMutation<?, ?>>> converters = createHashMap(20);

	public DelegatingLocalMutationConverter(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull MutationObjectMapper mutationObjectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(mutationObjectMapper, exceptionFactory);

		// associated data
		registerConverter(RemoveAssociatedDataMutation.class, new RemoveAssociatedDataMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(UpsertAssociatedDataMutation.class, new UpsertAssociatedDataMutationConverter(objectMapper, mutationObjectMapper, exceptionFactory));
		// attributes
		registerConverter(ApplyDeltaAttributeMutation.class, new ApplyDeltaAttributeMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(RemoveAttributeMutation.class, new RemoveAttributeMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(UpsertAttributeMutation.class, new UpsertAttributeMutationConverter(mutationObjectMapper, exceptionFactory));
		// entity
		registerConverter(RemoveParentMutation.class, new RemoveParentMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(SetParentMutation.class, new SetParentMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(SetEntityScopeMutation.class, new SetEntityScopeMutationConverter(mutationObjectMapper, exceptionFactory));
		// price
		registerConverter(SetPriceInnerRecordHandlingMutation.class, new SetPriceInnerRecordHandlingMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(RemovePriceMutation.class, new RemovePriceMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(UpsertPriceMutation.class, new UpsertPriceMutationConverter(mutationObjectMapper, exceptionFactory));
		// reference
		registerConverter(InsertReferenceMutation.class, new InsertReferenceMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(RemoveReferenceMutation.class, new RemoveReferenceMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(SetReferenceGroupMutation.class, new SetReferenceGroupMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(RemoveReferenceGroupMutation.class, new RemoveReferenceGroupMutationConverter(mutationObjectMapper, exceptionFactory));
		registerConverter(ReferenceAttributeMutation.class, new ReferenceAttributeMutationConverter(mutationObjectMapper, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return LocalMutation.class.getSimpleName();
	}
}
