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

package io.evitadb.core.engine;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.model.reference.LogFileRecordReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;

@DisplayName("ExpandedEngineState builder and state transitions")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class ExpandedEngineStateTest {

	private static EngineState<LogRecordReference> engineState(
		long version,
		String[] active,
		String[] inactive,
		String[] readOnly,
		@Nullable LogFileRecordReference wal
	) {
		return EngineState
			.builder()
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
		final LogFileRecordReference wal = new LogFileRecordReference(i -> "wal-" + i, 3, null, 0L);
		final EngineState<LogRecordReference> base = engineState(
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
		final EngineState<LogRecordReference> base = engineState(1L, new String[0], new String[0], new String[0], null);
		final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

		final LogFileRecordReference newWal = new LogFileRecordReference(i -> "wal-" + i, 5, null, 0L);
		final EngineState<LogRecordReference> updated = expanded.engineState(newWal, 2L);

		assertEquals(2L, updated.version());
		assertSame(newWal, updated.walReference());
		assertArrayEquals(new String[0], updated.activeCatalogs());
		assertArrayEquals(new String[0], updated.inactiveCatalogs());
		assertArrayEquals(new String[0], updated.readOnlyCatalogs());
	}

	@Test
	@DisplayName("withCatalog(Contract) should keep inactive")
	void shouldKeepCatalogInactiveWhenContractProvided() {
		final EngineState<LogRecordReference> base = engineState(10L, new String[0], new String[0], new String[0], null);
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
		final EngineState<LogRecordReference> base = engineState(3L, new String[0], new String[0], new String[0], null);
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

	/**
	 * Verifies that `Builder#withInFlightPlaceholder` installs a transient mid-transition catalog reference
	 * (e.g. a `BEING_UPGRADED` placeholder) without touching the persisted `activeCatalogs` / `inactiveCatalogs`
	 * buckets. This invariant is what makes a crash mid-upgrade auto-recoverable: the name stays in its
	 * original bucket so the next boot reloads the same (still-old) catalog and the load-throws path can
	 * reissue the upgrade mutation.
	 */
	@Nested
	@DisplayName("Builder#withInFlightPlaceholder — placeholder installation for mid-transition catalogs")
	class WithInFlightPlaceholder {

		private static final Path PLACEHOLDER_PATH = Paths.get("target", "in-flight-placeholder-test");

		/**
		 * Builds an `UnusableCatalog` placeholder identical to the one `UpgradeCatalogFormatMutationOperator`
		 * installs during its transition phase — `BEING_UPGRADED` state, dummy folder, throwing stub accessor.
		 */
		@Nonnull
		private static UnusableCatalog beingUpgradedPlaceholder(@Nonnull String name) {
			return TestCatalogFolderContexts.onDirectory(PLACEHOLDER_PATH).createUnusableCatalog(
				name,
				CatalogState.BEING_UPGRADED,
				(cn, folderId, root) ->
					new IllegalStateException("Placeholder `" + cn + "` must not be queried.")
			);
		}

		@Test
		@DisplayName("should keep the catalog name in activeCatalogs when it was active before")
		void shouldKeepActiveCatalogInActiveBucket() {
			// given — a single active catalog recorded in the engine state
			final CatalogContract priorCatalog = contract("alpha", 9);
			final EngineState<LogRecordReference> base = engineState(
				5L, new String[]{"alpha"}, new String[0], new String[0], null
			);
			final Map<String, CatalogContract> catalogs = new HashMap<>();
			catalogs.put("alpha", priorCatalog);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, catalogs);

			// when — an in-flight BEING_UPGRADED placeholder is staged via withInFlightPlaceholder
			final ExpandedEngineState updated = ExpandedEngineState
				.builder(expanded)
				.withVersion(6L)
				.withInFlightPlaceholder(beingUpgradedPlaceholder("alpha"))
				.build();

			// then — the bucket arrays are untouched: the name must remain in activeCatalogs and NOT
			// have been moved to inactiveCatalogs (which is the critical property for crash-safe retry).
			assertArrayEquals(new String[]{"alpha"}, updated.engineState().activeCatalogs());
			assertArrayEquals(new String[0], updated.engineState().inactiveCatalogs());
			assertEquals(6L, updated.version());
		}

		@Test
		@DisplayName("should keep the catalog name in inactiveCatalogs when it was inactive before")
		void shouldKeepInactiveCatalogInInactiveBucket() {
			// given — a single inactive catalog recorded in the engine state
			final CatalogContract priorCatalog = contract("beta", 3);
			final EngineState<LogRecordReference> base = engineState(
				5L, new String[0], new String[]{"beta"}, new String[0], null
			);
			final Map<String, CatalogContract> catalogs = new HashMap<>();
			catalogs.put("beta", priorCatalog);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, catalogs);

			// when
			final ExpandedEngineState updated = ExpandedEngineState
				.builder(expanded)
				.withInFlightPlaceholder(beingUpgradedPlaceholder("beta"))
				.build();

			// then — the name must remain in inactiveCatalogs (symmetrical guarantee for the inactive bucket)
			assertArrayEquals(new String[0], updated.engineState().activeCatalogs());
			assertArrayEquals(new String[]{"beta"}, updated.engineState().inactiveCatalogs());
		}

		@Test
		@DisplayName("should install the placeholder in the catalogs map so getCatalog returns it")
		void shouldInstallPlaceholderInCatalogsMap() {
			// given
			final CatalogContract priorCatalog = contract("gamma", 1);
			final EngineState<LogRecordReference> base = engineState(
				5L, new String[]{"gamma"}, new String[0], new String[0], null
			);
			final Map<String, CatalogContract> catalogs = new HashMap<>();
			catalogs.put("gamma", priorCatalog);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, catalogs);
			final UnusableCatalog placeholder = beingUpgradedPlaceholder("gamma");

			// when
			final ExpandedEngineState updated = ExpandedEngineState
				.builder(expanded)
				.withInFlightPlaceholder(placeholder)
				.build();

			// then — getCatalog must return the exact placeholder instance (not the prior catalog) and
			// the placeholder must expose BEING_UPGRADED so concurrent callers fail fast with a transient
			// error instead of reading the half-migrated catalog.
			final Optional<CatalogContract> lookup = updated.getCatalog("gamma");
			assertTrue(lookup.isPresent());
			assertSame(placeholder, lookup.get());
			assertEquals(CatalogState.BEING_UPGRADED, lookup.get().getCatalogState());
		}

		@Test
		@DisplayName("should differ from withCatalog(UnusableCatalog) which moves the name to inactiveCatalogs")
		void shouldContrastWithCatalogBehavior() {
			// given — an active catalog to be replaced with an UnusableCatalog placeholder
			final CatalogContract priorCatalog = contract("delta", 1);
			final EngineState<LogRecordReference> base = engineState(
				5L, new String[]{"delta"}, new String[0], new String[0], null
			);
			final Map<String, CatalogContract> catalogs = new HashMap<>();
			catalogs.put("delta", priorCatalog);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, catalogs);

			final UnusableCatalog placeholder = beingUpgradedPlaceholder("delta");

			// when — the very same placeholder is installed via two different builder entry points
			final ExpandedEngineState viaWithInFlight = ExpandedEngineState
				.builder(expanded)
				.withInFlightPlaceholder(placeholder)
				.build();
			final ExpandedEngineState viaWithCatalog = ExpandedEngineState
				.builder(expanded)
				.withCatalog(placeholder)
				.build();

			// then — `withInFlightPlaceholder` preserves the active bucket; `withCatalog` demotes the
			// UnusableCatalog to the inactive bucket (because it is not a runtime Catalog instance).
			// This is the exact contrast that motivated adding `withInFlightPlaceholder`.
			assertArrayEquals(new String[]{"delta"}, viaWithInFlight.engineState().activeCatalogs());
			assertArrayEquals(new String[0], viaWithInFlight.engineState().inactiveCatalogs());

			assertArrayEquals(new String[0], viaWithCatalog.engineState().activeCatalogs());
			assertArrayEquals(new String[]{"delta"}, viaWithCatalog.engineState().inactiveCatalogs());
		}
	}
}
