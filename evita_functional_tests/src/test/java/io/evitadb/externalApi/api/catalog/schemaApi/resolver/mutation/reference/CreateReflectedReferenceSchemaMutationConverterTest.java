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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;

/**
 * Tests for {@link CreateReflectedReferenceSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateReflectedReferenceSchemaMutationConverterTest {

	private CreateReflectedReferenceSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new CreateReflectedReferenceSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	/* TODO JNO - fix */
	/*@Test
	void shouldResolveInputToLocalMutation() {
		final CreateReflectedReferenceSchemaMutation expectedMutation = new CreateReflectedReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			"tags",
			new Scope[] { Scope.LIVE },
			new Scope[] { Scope.LIVE },
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			new String[] { "order" }
		);

		final CreateReflectedReferenceSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE)
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT)
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_FILTER.name(), list()
					.i("order"))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateReflectedReferenceSchemaMutation convertedMutation2 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(), "ZERO_OR_MORE")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_FILTER.name(), list()
					.i("order"))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateReflectedReferenceSchemaMutation expectedMutation = new CreateReflectedReferenceSchemaMutation(
			"tags",
			null,
			null,
			null,
			"tag",
			"tags",
			null,
			null,
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			null
		);

		final CreateReflectedReferenceSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}*/
}
