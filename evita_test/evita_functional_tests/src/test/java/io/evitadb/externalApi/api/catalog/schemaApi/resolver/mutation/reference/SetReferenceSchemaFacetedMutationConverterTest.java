/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedFacetedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaFacetedMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetReferenceSchemaFacetedMutationConverter} verifying input
 * resolution, output serialization, and facetedPartially expression handling.
 *
 * @author Lukas Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("SetReferenceSchemaFacetedMutationConverter (REST/GraphQL)")
class SetReferenceSchemaFacetedMutationConverterTest {

	private SetReferenceSchemaFacetedMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetReferenceSchemaFacetedMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE,
			TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	/**
	 * Verifies that a basic input map with FACETED_IN_SCOPES is correctly
	 * resolved to a SetReferenceSchemaFacetedMutation.
	 */
	@Test
	@DisplayName("should resolve basic input to mutation")
	void shouldResolveInputToLocalMutation() {
		final SetReferenceSchemaFacetedMutation expectedMutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE}
			);

		final SetReferenceSchemaFacetedMutation convertedMutation1 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaFacetedMutationDescriptor
						.FACETED_IN_SCOPES.name(), list().i(Scope.LIVE))
					.build()
			);
		assertEquals(expectedMutation, convertedMutation1);

		final SetReferenceSchemaFacetedMutation convertedMutation2 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaFacetedMutationDescriptor
						.FACETED_IN_SCOPES.name(), list().i(Scope.LIVE.name()))
					.build()
			);
		assertEquals(expectedMutation, convertedMutation2);
	}

	/**
	 * Verifies that missing required data throws an exception.
	 */
	@Test
	@DisplayName("should throw when required data is missing")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetReferenceSchemaFacetedMutationDescriptor
						.FACETED_IN_SCOPES.name(), true)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(Map.of())
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput((Object) null)
		);
	}

	/**
	 * Verifies that output serialization produces the expected structure.
	 */
	@Test
	@DisplayName("should serialize basic mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		final SetReferenceSchemaFacetedMutation inputMutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(),
						SetReferenceSchemaFacetedMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaFacetedMutationDescriptor
						.FACETED_IN_SCOPES.name(), array().i(Scope.LIVE.name()))
					.build()
			);
	}

	/**
	 * Verifies that an input map with FACETED_PARTIALLY_IN_SCOPES containing
	 * scope+expression objects is correctly parsed into the mutation.
	 */
	@Test
	@DisplayName("should resolve input with facetedPartially expressions")
	void shouldResolveInputWithFacetedPartially() {
		final SetReferenceSchemaFacetedMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaFacetedMutationDescriptor
						.FACETED_IN_SCOPES.name(), list().i(Scope.LIVE))
					.e(SetReferenceSchemaFacetedMutationDescriptor
						.FACETED_PARTIALLY_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedFacetedPartiallyDescriptor
								.EXPRESSION.name(), "1 > 0")
					))
					.build()
			);

		assertNotNull(convertedMutation.getFacetedPartiallyInScopes());
		assertEquals(1, convertedMutation.getFacetedPartiallyInScopes().length);
		assertEquals(
			Scope.LIVE,
			convertedMutation.getFacetedPartiallyInScopes()[0].scope()
		);
		assertNotNull(
			convertedMutation.getFacetedPartiallyInScopes()[0].expression()
		);
	}

	/**
	 * Verifies that a mutation with facetedPartially expression serializes
	 * to output containing the FACETED_PARTIALLY_IN_SCOPES key.
	 */
	@Test
	@DisplayName("should serialize mutation with facetedPartially to output")
	void shouldSerializeOutputWithFacetedPartially() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final SetReferenceSchemaFacetedMutation inputMutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);

		assertNotNull(serializedMutation);
		assertThat(serializedMutation).containsKey(
			SetReferenceSchemaFacetedMutationDescriptor
				.FACETED_PARTIALLY_IN_SCOPES.name()
		);
	}
}
