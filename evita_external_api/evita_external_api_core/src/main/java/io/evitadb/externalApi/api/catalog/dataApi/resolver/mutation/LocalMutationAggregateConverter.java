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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor;
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
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Resolves aggregate object of input local mutation objects parsed from JSON to actual {@link LocalMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class LocalMutationAggregateConverter extends MutationAggregateConverter<LocalMutation<?, ?>, LocalMutationConverter<LocalMutation<?, ?>>> {

	@Nonnull
	@Getter(AccessLevel.PRIVATE)
	private final EntitySchemaContract entitySchema;

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, LocalMutationConverter<LocalMutation<?, ?>>> converters = createHashMap(20);

	public LocalMutationAggregateConverter(
		@Nonnull ObjectMapper objectMapper,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull MutationObjectParser objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
		// todo lho nullability comes from io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.subscribingDataFetcher.ChangeCatalogDataCaptureBodyDataFetcher.ChangeCatalogDataCaptureBodyDataFetcher
		//  we need to decide what we want when we generate schema for this use case
		this.entitySchema = entitySchema;

		// associated data
		registerConverter(REMOVE_ASSOCIATED_DATA_MUTATION.name(), new RemoveAssociatedDataMutationConverter(objectParser, exceptionFactory));
		registerConverter(UPSERT_ASSOCIATED_DATA_MUTATION.name(), new UpsertAssociatedDataMutationConverter(objectMapper, entitySchema, objectParser, exceptionFactory));
		// attributes
		registerConverter(APPLY_DELTA_ATTRIBUTE_MUTATION.name(), new ApplyDeltaAttributeMutationConverter(entitySchema, objectParser, exceptionFactory));
		registerConverter(REMOVE_ATTRIBUTE_MUTATION.name(), new RemoveAttributeMutationConverter(objectParser, exceptionFactory));
		registerConverter(UPSERT_ATTRIBUTE_MUTATION.name(), new UpsertAttributeMutationConverter(entitySchema, objectParser, exceptionFactory));
		// entity
		registerConverter(REMOVE_PARENT_MUTATION.name(), new RemoveParentMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_PARENT_MUTATION.name(), new SetParentMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ENTITY_SCOPE_MUTATION.name(), new SetEntityScopeMutationConverter(objectParser, exceptionFactory));
		// price
		registerConverter(SET_PRICE_INNER_RECORD_HANDLING_MUTATION.name(), new SetPriceInnerRecordHandlingMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_PRICE_MUTATION.name(), new RemovePriceMutationConverter(objectParser, exceptionFactory));
		registerConverter(UPSERT_PRICE_MUTATION.name(), new UpsertPriceMutationConverter(objectParser, exceptionFactory));
		// reference
		registerConverter(INSERT_REFERENCE_MUTATION.name(), new InsertReferenceMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_REFERENCE_MUTATION.name(), new RemoveReferenceMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_REFERENCE_GROUP_MUTATION.name(), new SetReferenceGroupMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_REFERENCE_GROUP_MUTATION.name(), new RemoveReferenceGroupMutationConverter(objectParser, exceptionFactory));
		registerConverter(REFERENCE_ATTRIBUTE_MUTATION.name(), new ReferenceAttributeMutationConverter(entitySchema, objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		// we need separate converter for each schema because each schema has different mutations allowed
		return LocalMutationAggregateDescriptor.THIS.name(this.entitySchema);
	}
}
