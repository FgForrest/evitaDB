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

package io.evitadb.externalApi.api.transaction.model.mutation;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing the {@link io.evitadb.api.requestResponse.transaction.TransactionMutation}
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface TransactionMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor TRANSACTION_ID = PropertyDescriptor.builder()
		.name("transactionId")
		.description("Represents the unique identifier of a transaction.")
		.type(nonNull(UUID.class))
		.build();
	PropertyDescriptor VERSION = PropertyDescriptor.builder()
		.name("version")
		.description("Represents the next version the transaction transitions the state to.")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor MUTATION_COUNT = PropertyDescriptor.builder()
		.name("mutationCount")
		.description("Represents the number of mutations in this particular transaction.")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor WAL_SIZE_IN_BYTES = PropertyDescriptor.builder()
		.name("walSizeInBytes")
		.description("Represents the size of the serialized transaction mutations that follow this mutation in bytes.")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor COMMIT_TIMESTAMP = PropertyDescriptor.builder()
		.name("commitTimestamp")
		.description("Represents the timestamp of the commit.")
		.type(nonNull(OffsetDateTime.class))
		.build();

	// todo lho register
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("TransactionMutation")
		.description("""
	         This transaction mutation delimits mutations of one transaction from another. It contains data that allow to recognize
	         the scope of the transaction and verify its integrity.
	         """)
		.staticFields(List.of(
			MUTATION_TYPE,
			TRANSACTION_ID,
			VERSION,
			MUTATION_COUNT,
			WAL_SIZE_IN_BYTES,
			COMMIT_TIMESTAMP
		))
		.build();
}
