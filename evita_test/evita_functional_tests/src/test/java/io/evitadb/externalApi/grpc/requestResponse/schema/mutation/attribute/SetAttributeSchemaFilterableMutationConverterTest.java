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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedFilterCapabilities;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcEntityScope;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaFilterableMutation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;

@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class SetAttributeSchemaFilterableMutationConverterTest {

	private static SetAttributeSchemaFilterableMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = SetAttributeSchemaFilterableMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutation() {
		final SetAttributeSchemaFilterableMutation mutation1 = new SetAttributeSchemaFilterableMutation(
			"code", true
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));
	}

	@Test
	void shouldConvertMutationWithFilterCapabilities() {
		final SetAttributeSchemaFilterableMutation mutation = SetAttributeSchemaFilterableMutation.fromCapabilities(
			"code",
			new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
		);
		final GrpcSetAttributeSchemaFilterableMutation grpcMutation = converter.convert(mutation);
		assertEquals(1, grpcMutation.getFilterCapabilitiesInScopesCount());
		assertEquals(GrpcEntityScope.SCOPE_LIVE, grpcMutation.getFilterCapabilitiesInScopes(0).getScope());
		assertEquals(mutation, converter.convert(grpcMutation));
	}

	/**
	 * An older client never sends `filterCapabilitiesInScopes`, and proto3 renders that absence as an empty list.
	 * The converter must read it as "no acceleration declared" - never as a spurious capability, never as a failure.
	 */
	@Test
	void shouldConvertMutationOmittingFilterCapabilities() {
		final GrpcSetAttributeSchemaFilterableMutation legacyMutation =
			GrpcSetAttributeSchemaFilterableMutation.newBuilder()
				.setName("code")
				.setFilterable(true)
				.build();
		assertTrue(legacyMutation.getFilterCapabilitiesInScopesList().isEmpty());

		final SetAttributeSchemaFilterableMutation mutation = converter.convert(legacyMutation);
		assertArrayEquals(ScopedFilterCapabilities.EMPTY, mutation.getFilterCapabilitiesInScopes());
		assertEquals(new SetAttributeSchemaFilterableMutation("code", true), mutation);
	}
}
