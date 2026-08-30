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


package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeFilterAcceleratorsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaAcceleratedMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetAttributeSchemaAcceleratedMutationConverter}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class SetAttributeSchemaAcceleratedMutationConverterTest {

	private SetAttributeSchemaAcceleratedMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetAttributeSchemaAcceleratedMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetAttributeSchemaAcceleratedMutation expectedMutation = new SetAttributeSchemaAcceleratedMutation(
			"code",
			new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
		);

		final SetAttributeSchemaAcceleratedMutation convertedFromEnums = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaAcceleratedMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS.name(), list()
							.i(AttributeFilterAccelerator.SUBSTRING_SEARCH))))
				.build()
		);
		assertEquals(expectedMutation, convertedFromEnums);

		final SetAttributeSchemaAcceleratedMutation convertedFromStrings = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaAcceleratedMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS.name(), list()
							.i(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()))))
				.build()
		);
		assertEquals(expectedMutation, convertedFromStrings);
	}

	@Test
	void shouldResolveInputWithoutAccelerators() {
		// a client that omits the property must get "no acceleration", not a refusal
		final SetAttributeSchemaAcceleratedMutation converted = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.build()
		);
		assertEquals(new SetAttributeSchemaAcceleratedMutation("code"), converted);
		assertNotNull(converted.getAcceleratorsInScopes());
		assertEquals(0, converted.getAcceleratorsInScopes().length);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldRoundTripAccelerators() {
		final SetAttributeSchemaAcceleratedMutation inputMutation = new SetAttributeSchemaAcceleratedMutation(
			"code",
			new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
		);

		final SetAttributeSchemaAcceleratedMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}
}
