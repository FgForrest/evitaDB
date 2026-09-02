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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCreateAttributeSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeFilterAccelerator;
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

@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CreateAttributeSchemaMutationConverterTest {

	private static CreateAttributeSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = CreateAttributeSchemaMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutation() {
		final CreateAttributeSchemaMutation mutation1 = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
			true,
			true,
			true,
			false,
			false,
			String.class,
			"defaultCode",
			0
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final CreateAttributeSchemaMutation mutation2 = new CreateAttributeSchemaMutation(
			"code",
			null,
			null,
			null,
			false,
			false,
			false,
			true,
			false,
			String.class,
			null,
			0
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}

	@Test
	void shouldRoundTripRepresentativeFlagThroughGrpc() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
			true,
			true,
			true,
			false,
			true,
			String.class,
			"defaultCode",
			0
		);

		final GrpcCreateAttributeSchemaMutation grpcMutation = converter.convert(mutation);
		assertTrue(grpcMutation.getRepresentative());

		final CreateAttributeSchemaMutation roundTrip = converter.convert(grpcMutation);
		assertTrue(roundTrip.isRepresentative());
	}

	@Test
	void shouldRoundTripAcceleratorsThroughGrpc() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			null,
			new Scope[]{Scope.LIVE},
			new ScopedAttributeFilterAccelerators[]{
				new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			},
			Scope.NO_SCOPE,
			false,
			false,
			false,
			String.class,
			null,
			0,
			ConflictResolutionOverride.INHERITED
		);

		final GrpcCreateAttributeSchemaMutation grpcMutation = converter.convert(mutation);
		assertEquals(1, grpcMutation.getAcceleratorsInScopesCount());
		assertEquals(
			GrpcAttributeFilterAccelerator.ATTRIBUTE_FILTER_ACCELERATOR_SUBSTRING_SEARCH,
			grpcMutation.getAcceleratorsInScopes(0).getAccelerators(0)
		);
		assertEquals(mutation, converter.convert(grpcMutation));
	}

	/**
	 * An older client never sends `acceleratorsInScopes`, and proto3 renders that absence as an empty list.
	 * The converter must read it as "no acceleration declared" - never as a spurious capability, never as a failure.
	 */
	@Test
	void shouldConvertMutationOmittingAccelerators() {
		final GrpcCreateAttributeSchemaMutation legacyMutation = GrpcCreateAttributeSchemaMutation.newBuilder()
			.setName("code")
			.setFilterable(true)
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(String.class))
			.build();
		assertTrue(legacyMutation.getAcceleratorsInScopesList().isEmpty());

		final CreateAttributeSchemaMutation mutation = converter.convert(legacyMutation);
		assertArrayEquals(ScopedAttributeFilterAccelerators.EMPTY, mutation.getAcceleratorsInScopes());
	}
}
