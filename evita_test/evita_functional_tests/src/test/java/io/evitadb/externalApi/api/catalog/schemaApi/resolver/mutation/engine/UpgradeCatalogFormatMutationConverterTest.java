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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.system.model.mutation.engine.EngineMutationDescriptor;
import io.evitadb.externalApi.api.system.model.mutation.engine.UpgradeCatalogFormatMutationDescriptor;
import io.evitadb.externalApi.api.system.resolver.mutation.engine.UpgradeCatalogFormatMutationConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Round-trip tests for {@link UpgradeCatalogFormatMutationConverter}. The converter must parse the neutral
 * input-map representation used by GraphQL and REST into the engine mutation and vice versa, preserving the
 * `catalogName`, `fromProtocolVersion`, and `toProtocolVersion` payload.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UpgradeCatalogFormatMutationConverter - round-trip conversion")
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class UpgradeCatalogFormatMutationConverterTest {

	private UpgradeCatalogFormatMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new UpgradeCatalogFormatMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE,
			TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final UpgradeCatalogFormatMutation expectedMutation = new UpgradeCatalogFormatMutation("testCatalog", 2, 3);
		final UpgradeCatalogFormatMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
				.e(UpgradeCatalogFormatMutationDescriptor.FROM_PROTOCOL_VERSION.name(), 2)
				.e(UpgradeCatalogFormatMutationDescriptor.TO_PROTOCOL_VERSION.name(), 3)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final UpgradeCatalogFormatMutation inputMutation = new UpgradeCatalogFormatMutation("testCatalog", 2, 3);
		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), UpgradeCatalogFormatMutation.class.getSimpleName())
					.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
					.e(UpgradeCatalogFormatMutationDescriptor.FROM_PROTOCOL_VERSION.name(), 2)
					.e(UpgradeCatalogFormatMutationDescriptor.TO_PROTOCOL_VERSION.name(), 3)
					.build()
			);
	}
}
