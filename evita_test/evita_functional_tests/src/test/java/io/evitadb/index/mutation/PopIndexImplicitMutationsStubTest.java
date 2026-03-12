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

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the stub {@link EntityIndexLocalMutationExecutor#popIndexImplicitMutations} method.
 * The stub always returns an empty {@link IndexImplicitMutations} — real detection logic will be
 * implemented in WBS-08/WBS-09.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("popIndexImplicitMutations stub behavior")
class PopIndexImplicitMutationsStubTest {

	private static final String ENTITY_TYPE = "product";

	/**
	 * Creates a minimal {@link EntityIndexLocalMutationExecutor} with mocked dependencies.
	 * The stub method does not access any instance state, so mocks are sufficient.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static EntityIndexLocalMutationExecutor createMinimalExecutor() {
		final WritableEntityStorageContainerAccessor containerAccessor =
			Mockito.mock(WritableEntityStorageContainerAccessor.class);
		final IndexMaintainer<EntityIndexKey, EntityIndex> entityIndexMaintainer =
			Mockito.mock(IndexMaintainer.class);
		final IndexMaintainer<CatalogIndexKey, CatalogIndex> catalogIndexMaintainer =
			Mockito.mock(IndexMaintainer.class);
		final EntitySchema schema = Mockito.mock(EntitySchema.class);
		Mockito.when(schema.getName()).thenReturn(ENTITY_TYPE);
		final AtomicInteger sequencer = new AtomicInteger(1);
		return new EntityIndexLocalMutationExecutor(
			containerAccessor, 1,
			entityIndexMaintainer,
			catalogIndexMaintainer,
			() -> schema,
			sequencer::getAndIncrement,
			false,
			() -> {
				throw new UnsupportedOperationException("Not used in stub test.");
			}
		);
	}

	/**
	 * Verifies that the current stub implementation always returns an empty
	 * {@link IndexImplicitMutations} regardless of input — real logic will be added in WBS-08/WBS-09.
	 */
	@Nested
	@DisplayName("Stub behavior")
	class StubBehavior {

		@Test
		@DisplayName("Should return empty IndexImplicitMutations for empty input")
		void shouldReturnEmptyIndexImplicitMutationsForEmptyInput() {
			final EntityIndexLocalMutationExecutor executor = createMinimalExecutor();
			final IndexImplicitMutations result = executor.popIndexImplicitMutations(
				Collections.emptyList()
			);
			assertEquals(0, result.indexMutations().length);
		}

		@Test
		@DisplayName("Should return empty IndexImplicitMutations for non-empty input")
		void shouldReturnEmptyIndexImplicitMutationsForNonEmptyInput() {
			final EntityIndexLocalMutationExecutor executor = createMinimalExecutor();
			final List<LocalMutation<?, ?>> mutations = List.of(
				new UpsertAttributeMutation("name", "test-value")
			);
			final IndexImplicitMutations result = executor.popIndexImplicitMutations(mutations);
			assertEquals(0, result.indexMutations().length);
		}

	}

}
