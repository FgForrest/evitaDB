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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.InfrastructureMutationAggregateDescriptor;
import io.evitadb.externalApi.api.transaction.model.mutation.TransactionMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link InfrastructureMutationAggregateConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class InfrastructureMutationAggregateConverterTest {

	private static final UUID TRANSACTION_ID = UUID.randomUUID();
	private static final OffsetDateTime COMMIT_TIMESTAMP = OffsetDateTime.of(2025, 9, 24, 13, 0, 0, 0, ZoneOffset.UTC);

	private InfrastructureMutationAggregateConverter converter;

	@BeforeEach
	void init() {
		this.converter = new InfrastructureMutationAggregateConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToMutation() {
		final List<Mutation> expectedMutations = List.of(
			new TransactionMutation(TRANSACTION_ID, 10, 15, 100, COMMIT_TIMESTAMP)
		);

		final List<Mutation> convertedMutations = this.converter.convertFromInput(
			map()
				.e(InfrastructureMutationAggregateDescriptor.TRANSACTION_MUTATION.name(), map()
					.e(TransactionMutationDescriptor.TRANSACTION_ID.name(), TRANSACTION_ID.toString())
					.e(TransactionMutationDescriptor.VERSION.name(), 10)
					.e(TransactionMutationDescriptor.MUTATION_COUNT.name(), 15)
					.e(TransactionMutationDescriptor.WAL_SIZE_IN_BYTES.name(), 100)
					.e(TransactionMutationDescriptor.COMMIT_TIMESTAMP.name(), "2025-09-24T13:00:00Z"))
				.build()
		);

		assertEquals(expectedMutations, convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(
			map()
				.e(InfrastructureMutationAggregateDescriptor.TRANSACTION_MUTATION.name(), map()
					.e(TransactionMutationDescriptor.VERSION.name(), "10")
					.e(TransactionMutationDescriptor.MUTATION_COUNT.name(), 15)
					.e(TransactionMutationDescriptor.WAL_SIZE_IN_BYTES.name(), "100")
					.e(TransactionMutationDescriptor.COMMIT_TIMESTAMP.name(), "2025-09-24T13:00:00Z"))
				.build()
		));
	}

	@Test
	void shouldSerializeMutationToOutput() {
		final List<Mutation> inputMutation = List.of(
			new TransactionMutation(TRANSACTION_ID, 10, 15, 100, COMMIT_TIMESTAMP)
		);

		//noinspection unchecked
		final List<Map<String, Object>> serializedMutation = (List<Map<String, Object>>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				list()
					.i(map()
						   .e(InfrastructureMutationAggregateDescriptor.TRANSACTION_MUTATION.name(), map()
							   .e(TransactionMutationDescriptor.TRANSACTION_ID.name(), TRANSACTION_ID.toString())
							   .e(TransactionMutationDescriptor.VERSION.name(), "10")
							   .e(TransactionMutationDescriptor.MUTATION_COUNT.name(), 15)
							   .e(TransactionMutationDescriptor.WAL_SIZE_IN_BYTES.name(), "100")
							   .e(TransactionMutationDescriptor.COMMIT_TIMESTAMP.name(), "2025-09-24T13:00:00Z"))
						   .build())
					.build()
			);
	}
}
