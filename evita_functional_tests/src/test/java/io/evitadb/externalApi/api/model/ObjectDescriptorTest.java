/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.api.model;

import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests {@link ObjectDescriptor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ObjectDescriptorTest {

	@Test
	void shouldNormalizeDuplicateProperties() {
		final ObjectDescriptor objectDescriptor = ObjectDescriptor.builder()
			.name("Object")
			.description("Object description")
			.staticProperty(PropertyDescriptor.builder().name("key").description("desc").type(nonNull(String.class)).build())
			.staticProperty(PropertyDescriptor.builder().name("value").description("desc").type(nonNull(Boolean.class)).build())
			.staticProperty(PropertyDescriptor.builder().name("key").description("desc").type(nonNull(Integer.class)).build()) // should override the first `key` property
			.build();

		final List<PropertyDescriptor> propertyDescriptors = objectDescriptor.staticProperties();
		assertEquals(2, propertyDescriptors.size());

		final PropertyDescriptor keyDescriptor = findPropertyDescriptorByName(propertyDescriptors, "key");
		final PropertyDescriptor valueDescriptor = findPropertyDescriptorByName(propertyDescriptors, "value");

		assertSame(Integer.class, ((PrimitivePropertyDataTypeDescriptor) keyDescriptor.type()).javaType());
		assertSame(Boolean.class, ((PrimitivePropertyDataTypeDescriptor) valueDescriptor.type()).javaType());
	}

	@Test
	void shouldConstructNameFromDynamicNames() {
		assertConstructedObjectName("ProductReference", "*Reference", "Product");
		assertConstructedObjectName("EntityWithProduct", "EntityWith*", "Product");
		assertConstructedObjectName("ProductParameterReference", "*Reference", "Product", "Parameter");
		assertConstructedObjectName("FilterContainerAbc123", "FilterContainer*", "ABC123");
		assertConstructedObjectName("WithParameterAbc123Reference", "With*Reference", "Parameter", "ABC123");
	}

	private void assertConstructedObjectName(
		@Nonnull String expectedName,
		@Nonnull String objectNamePattern,
		@Nonnull Object... dynamicNames
	) {
		assertEquals(
			expectedName,
			ObjectDescriptor.builder()
				.name(objectNamePattern)
				.build()
				.name(dynamicNames)
		);
	}

	@Nonnull
	private static PropertyDescriptor findPropertyDescriptorByName(
		@Nonnull List<PropertyDescriptor> propertyDescriptors,
		@Nonnull String name
	) {
		return propertyDescriptors.stream()
			.filter(it -> it.name().equals(name))
			.findFirst()
			.orElseThrow();
	}
}
