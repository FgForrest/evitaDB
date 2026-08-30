/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeFilterAccelerator;
import io.evitadb.externalApi.grpc.generated.GrpcEntityScope;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaAcceleratedMutation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SetAttributeSchemaAcceleratedMutationConverter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class SetAttributeSchemaAcceleratedMutationConverterTest {

	private static SetAttributeSchemaAcceleratedMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = SetAttributeSchemaAcceleratedMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutationWithAccelerators() {
		final SetAttributeSchemaAcceleratedMutation mutation = new SetAttributeSchemaAcceleratedMutation(
			"code",
			new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
		);
		final GrpcSetAttributeSchemaAcceleratedMutation grpcMutation = converter.convert(mutation);
		assertEquals(1, grpcMutation.getAcceleratorsInScopesCount());
		assertEquals(GrpcEntityScope.SCOPE_LIVE, grpcMutation.getAcceleratorsInScopes(0).getScope());
		assertEquals(
			GrpcAttributeFilterAccelerator.ATTRIBUTE_FILTER_ACCELERATOR_SUBSTRING_SEARCH,
			grpcMutation.getAcceleratorsInScopes(0).getAccelerators(0)
		);
		assertEquals(mutation, converter.convert(grpcMutation));
	}

	/**
	 * A client that never sends `acceleratorsInScopes` produces an empty list in proto3. The converter must read it
	 * as "no acceleration declared" - never as a spurious accelerator, never as a failure.
	 */
	@Test
	void shouldConvertMutationOmittingAccelerators() {
		final GrpcSetAttributeSchemaAcceleratedMutation bareMutation =
			GrpcSetAttributeSchemaAcceleratedMutation.newBuilder()
				.setName("code")
				.build();
		assertTrue(bareMutation.getAcceleratorsInScopesList().isEmpty());

		final SetAttributeSchemaAcceleratedMutation mutation = converter.convert(bareMutation);
		assertArrayEquals(ScopedAttributeFilterAccelerators.EMPTY, mutation.getAcceleratorsInScopes());
		assertEquals(new SetAttributeSchemaAcceleratedMutation("code"), mutation);
	}
}
