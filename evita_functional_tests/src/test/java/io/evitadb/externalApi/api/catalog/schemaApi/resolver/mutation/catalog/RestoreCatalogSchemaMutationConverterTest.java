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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.TopLevelCatalogSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test class for {@link RestoreCatalogSchemaMutationConverter}. This test suite verifies
 * the functionality of the catalog schema restoration mutation converter, ensuring proper
 * conversion and handling of catalog schema restoration operations in the external API context.
 * Tests cover various catalog restoration scenarios and validation of input parameters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("RestoreCatalogSchemaMutationConverter functionality")
public class RestoreCatalogSchemaMutationConverterTest {

	private RestoreCatalogSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new RestoreCatalogSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	@DisplayName("Should resolve input to local mutation when restoring catalog")
	void shouldResolverInputToLocalMutationWhenRestoringCatalog() {
		final RestoreCatalogSchemaMutation expectedMutation = new RestoreCatalogSchemaMutation("testCatalog");
		final RestoreCatalogSchemaMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(TopLevelCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("Should not resolve input when missing required data")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	@DisplayName("Should serialize local mutation to output when restoring catalog")
	void shouldSerializeLocalMutationToOutputWhenRestoringCatalog() {
		final RestoreCatalogSchemaMutation inputMutation = new RestoreCatalogSchemaMutation("testCatalog");
		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(TopLevelCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
					.build()
			);
	}
}