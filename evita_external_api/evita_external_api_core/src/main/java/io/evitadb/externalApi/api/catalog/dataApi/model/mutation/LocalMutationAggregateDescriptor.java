/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.RemoveParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.RemovePriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Descriptor of aggregate object containing all implementations of {@link LocalMutation}
 * for schema-based external APIs.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface LocalMutationAggregateDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*LocalMutationAggregate")
		.description("""
            Contains all possible local mutations to perform on entity `%s`.
            """)
		// fields are not set because they should be created dynamically depending on entity schema
		.build();

	PropertyDescriptor REMOVE_ASSOCIATED_DATA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveAssociatedDataMutationDescriptor.THIS);
	PropertyDescriptor UPSERT_ASSOCIATED_DATA_MUTATION = PropertyDescriptor.nullableFromObject(UpsertAssociatedDataMutationDescriptor.THIS);

	PropertyDescriptor APPLY_DELTA_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(ApplyDeltaAttributeMutationDescriptor.THIS);
	PropertyDescriptor UPSERT_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(UpsertAttributeMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(RemoveAttributeMutationDescriptor.THIS);

	PropertyDescriptor REMOVE_PARENT_MUTATION = PropertyDescriptor.nullableFromObject(RemoveParentMutationDescriptor.THIS);
	PropertyDescriptor SET_PARENT_MUTATION = PropertyDescriptor.nullableFromObject(SetParentMutationDescriptor.THIS);

	PropertyDescriptor SET_PRICE_INNER_RECORD_HANDLING_MUTATION = PropertyDescriptor.nullableFromObject(SetPriceInnerRecordHandlingMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_PRICE_MUTATION = PropertyDescriptor.nullableFromObject(RemovePriceMutationDescriptor.THIS);
	PropertyDescriptor UPSERT_PRICE_MUTATION = PropertyDescriptor.nullableFromObject(UpsertPriceMutationDescriptor.THIS);

	PropertyDescriptor INSERT_REFERENCE_MUTATION = PropertyDescriptor.nullableFromObject(InsertReferenceMutationDescriptor.THIS);

	PropertyDescriptor REMOVE_REFERENCE_MUTATION = PropertyDescriptor.nullableFromObject(RemoveReferenceMutationDescriptor.THIS);

	PropertyDescriptor SET_REFERENCE_GROUP_MUTATION = PropertyDescriptor.nullableFromObject(SetReferenceGroupMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_REFERENCE_GROUP_MUTATION = PropertyDescriptor.nullableFromObject(RemoveReferenceGroupMutationDescriptor.THIS);

	PropertyDescriptor REFERENCE_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(ReferenceAttributeMutationDescriptor.THIS);
}
