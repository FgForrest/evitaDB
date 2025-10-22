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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.transaction.model.mutation.TransactionMutationDescriptor;

import java.util.List;

/**
 * Descriptor of aggregate object containing all implementations of infrastructure mutations.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface InfrastructureMutationInputAggregateDescriptor {

	PropertyDescriptor TRANSACTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"transactionMutation",
		TransactionMutationDescriptor.THIS_INPUT
	);

	// todo lho register? where is this even needed?
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("InfrastructureMutationInputAggregate")
		.description("""
             Contains all possible infrastructure mutations
             """)
		.staticProperties(List.of(
			TRANSACTION_MUTATION
		))
		.build();
}
