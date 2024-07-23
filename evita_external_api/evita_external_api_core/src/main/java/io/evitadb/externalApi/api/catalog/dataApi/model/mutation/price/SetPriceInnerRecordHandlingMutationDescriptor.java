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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link SetPriceInnerRecordHandlingMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface SetPriceInnerRecordHandlingMutationDescriptor {

	PropertyDescriptor PRICE_INNER_RECORD_HANDLING = PropertyDescriptor.builder()
		.name("priceInnerRecordHandling")
		.description("""
			Price inner record handling controls how prices that share same `inner entity id` will behave during filtering and sorting.
			""")
		.type(nonNull(PriceInnerRecordHandling.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetPriceInnerRecordHandlingMutation")
		.description("""
			This mutation allows to set / remove `priceInnerRecordHandling` behaviour of the entity.
			""")
		.staticFields(List.of(PRICE_INNER_RECORD_HANDLING))
		.build();
}
