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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link UnionDescriptor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class UnionDescriptorTest {

	@Test
	void shouldConstructNameFromDynamicNames() {
		assertConstructedUnionName("ProductReference", "$Reference", "Product");
		assertConstructedUnionName("ProductWithParameterReference", "$With$Reference", "Product", "Parameter");
		assertConstructedUnionName("EntityWithProduct", "EntityWith$", "Product");
		assertConstructedUnionName("ProductParameterReference", "*Reference", "Product", "Parameter");
		assertConstructedUnionName("FilterContainerAbc123", "FilterContainer*", "ABC123");
	}

	private void assertConstructedUnionName(
		@Nonnull String expectedName,
		@Nonnull String objectNamePattern,
		@Nonnull Object... dynamicNames
	) {
		assertEquals(
			expectedName,
			UnionDescriptor.builder()
				.name(objectNamePattern)
				.build()
				.name(dynamicNames)
		);
	}
}
