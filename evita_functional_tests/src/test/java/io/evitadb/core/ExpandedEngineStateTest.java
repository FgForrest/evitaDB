/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ExpandedEngineState builder and state transitions")
class ExpandedEngineStateTest {

	private static EngineState engineState(
		long version,
		String[] active,
		String[] inactive,
		String[] readOnly,
		@Nullable LogFileRecordReference wal
	) {
		return EngineState.builder()
		                  .storageProtocolVersion(1)
		                  .version(version)
		                  .activeCatalogs(active)
		                  .inactiveCatalogs(inactive)
		                  .readOnlyCatalogs(readOnly)
		                  .walFileReference(wal)
		                  .build();
	}

	@Nonnull
	private static CatalogContract contract(@Nonnull String name, long version) {
		final CatalogContract cc = mock(CatalogContract.class);
		when(cc.getName()).thenReturn(name);
		when(cc.getVersion()).thenReturn(version);
		when(cc.isTerminated()).thenReturn(false);
		when(cc.supportsTransaction()).thenReturn(false);
		when(cc.isGoingLive()).thenReturn(false);
		return cc;
	}

	@Test
	@DisplayName("create() should reflect base state and catalogs")
	void shouldCreateExpandedSnapshotFromEngineState() {
		final LogFileRecordReference wal = new LogFileRecordReference(i -> "wal-" + i, 3, null);
		final EngineState base = engineState(
			7L,
			new String[]{},
			new String[]{"bInactive", "cRO"},
			new String[]{"cRO"},
			wal
		);
		final Map<String, CatalogContract> cats = new HashMap<>();
		cats.put("bInactive", contract("bInactive", 2));
		cats.put("cRO", contract("cRO", 3));

		final ExpandedEngineState expanded = ExpandedEngineState.create(base, cats);

		assertEquals(7L, expanded.version());
		assertSame(wal, expanded.walFileReference());
		assertTrue(expanded.getCatalog("bInactive").isPresent());
		assertTrue(expanded.getCatalog("cRO").isPresent());
		assertEquals(2, expanded.getCatalogCollection().size());
		assertTrue(expanded.isReadOnly("cRO"));
		assertFalse(expanded.isReadOnly("bInactive"));
		assertTrue(expanded.getCatalog("unknown").isEmpty());
	}

	@Test
	@DisplayName("engineState(wal, version) should update WAL and change version")
	void shouldUpdateWalWhenEngineStateRequested() {
		final EngineState base = engineState(1L, new String[0], new String[0], new String[0], null);
		final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

		final LogFileRecordReference newWal = new LogFileRecordReference(i -> "wal-" + i, 5, null);
		final EngineState updated = expanded.engineState(newWal, 2L);

		assertEquals(2L, updated.version());
		assertSame(newWal, updated.walFileReference());
		assertArrayEquals(new String[0], updated.activeCatalogs());
		assertArrayEquals(new String[0], updated.inactiveCatalogs());
		assertArrayEquals(new String[0], updated.readOnlyCatalogs());
	}

	@Test
	@DisplayName("withCatalog(Contract) should keep inactive")
	void shouldKeepCatalogInactiveWhenContractProvided() {
		final EngineState base = engineState(10L, new String[0], new String[0], new String[0], null);
		final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

		final CatalogContract cc = contract("beta", 7);
		final ExpandedEngineState updated = expanded.withUpdatedCatalogInstance(cc);

		assertEquals(10L, updated.version());
		assertArrayEquals(new String[0], updated.engineState().activeCatalogs());
		assertArrayEquals(new String[]{"beta"}, updated.engineState().inactiveCatalogs());
		assertTrue(updated.getCatalog("beta").isPresent());
	}

	@Test
	@DisplayName("Builder should stage operations and bump version once on build")
	void shouldBumpVersionOnceWhenBuilderBuilds() {
		final CatalogContract cc = contract("beta", 2);
		final EngineState base = engineState(3L, new String[0], new String[0], new String[0], null);
		final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

		final ExpandedEngineState built = ExpandedEngineState
			.builder(expanded)
			.withVersion(4L)
			.withCatalog(cc)
			.withReadOnlyCatalog(cc)
			.build();

		assertEquals(4L, built.version());
		assertArrayEquals(new String[0], built.engineState().activeCatalogs());
		assertArrayEquals(new String[]{"beta"}, built.engineState().inactiveCatalogs());
		assertArrayEquals(new String[]{"beta"}, built.engineState().readOnlyCatalogs());
		assertTrue(built.getCatalog("beta").isPresent());
	}
}
