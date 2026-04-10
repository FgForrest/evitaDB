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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SetReferenceSchemaIndexedMutation gRPC converter test")
class SetReferenceSchemaIndexedMutationConverterTest {

	private static SetReferenceSchemaIndexedMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = SetReferenceSchemaIndexedMutationConverter.INSTANCE;
	}

	@Test
	@DisplayName("should convert simple indexed mutation")
	void shouldConvertMutation() {
		final SetReferenceSchemaIndexedMutation mutation1 = new SetReferenceSchemaIndexedMutation(
			"tags", true
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));
	}

	@Test
	@DisplayName("should convert mutation with scoped index types and indexed components")
	void shouldConvertMutationWithIndexedComponents() {
		final SetReferenceSchemaIndexedMutation mutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
			},
			new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				)
			}
		);
		assertEquals(mutation, converter.convert(converter.convert(mutation)));
	}

	@Test
	@DisplayName("should convert inherited (null) indexed mutation")
	void shouldConvertInheritedMutation() {
		final SetReferenceSchemaIndexedMutation mutation = new SetReferenceSchemaIndexedMutation(
			"tags", (ScopedReferenceIndexType[]) null
		);
		assertEquals(mutation, converter.convert(converter.convert(mutation)));
	}
}
