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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.model.reference.LogFileRecordReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
		return engineState(
			version, active, inactive, readOnly, wal,
			identityBindings(concatDistinct(active, inactive))
		);
	}

	@Nonnull
	private static EngineState<LogRecordReference> engineState(
		long version,
		String[] active,
		String[] inactive,
		String[] readOnly,
		@Nullable LogFileRecordReference wal,
		@Nonnull CatalogFolderBinding[] catalogFolders
	) {
		return EngineState
			.builder()
			.storageProtocolVersion(1)
			.version(version)
			.activeCatalogs(active)
			.inactiveCatalogs(inactive)
			.readOnlyCatalogs(readOnly)
			.catalogFolders(catalogFolders)
			.walFileReference(wal)
			.build();
	}

	/**
	 * Binds every passed name to a folder carrying that same name.
	 *
	 * Staging a catalog no longer invents a binding for an unbound name, so a fixture that puts a catalog into
	 * a bucket has to bind it too — otherwise it describes a state the engine can never reach. Identity is the
	 * right shape here because these fixtures stand for catalogs that predate folder allocation.
	 *
	 * @param catalogNames names to bind, in any order
	 * @return bindings sorted by catalog name, as {@link EngineState} requires
	 */
	@Nonnull
	private static CatalogFolderBinding[] identityBindings(@Nonnull String... catalogNames) {
		final String[] sorted = catalogNames.clone();
		Arrays.sort(sorted);
		final CatalogFolderBinding[] bindings = new CatalogFolderBinding[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			bindings[i] = new CatalogFolderBinding(sorted[i], new CatalogFolderId(sorted[i]));
		}
		return bindings;
	}

	/**
	 * Concatenates two name arrays, dropping duplicates so a name present in both buckets is bound only once.
	 *
	 * @param first  first array of names
	 * @param second second array of names
	 * @return the distinct union of both
	 */
	@Nonnull
	private static String[] concatDistinct(@Nonnull String[] first, @Nonnull String[] second) {
		final LinkedHashSet<String> names = new LinkedHashSet<>(first.length + second.length);
		Collections.addAll(names, first);
		Collections.addAll(names, second);
		return names.toArray(String[]::new);
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
		// `beta` is bound up front: swapping an instance re-stages a catalog the state already knows, and
		// re-staging never establishes a binding
		final EngineState<LogRecordReference> base = engineState(
			10L, new String[0], new String[0], new String[0], null, identityBindings("beta")
		);
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
		final EngineState<LogRecordReference> base = engineState(
			3L, new String[0], new String[0], new String[0], null, identityBindings("beta")
		);
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
	 * Verifies that the staging API keeps the engine state's catalog-to-folder mapping in step with the catalog
	 * buckets it moves names between — see {@link io.evitadb.spi.store.engine.model.CatalogFolderBinding}.
	 */
	@Nested
	@DisplayName("Catalog folder binding staging")
	class CatalogFolderBindingStaging {

		@Test
		@DisplayName("Leaves an existing binding alone when its catalog is re-staged")
		void shouldPreserveExistingBindingWhenCatalogIsRestaged() {
			// re-staging happens on every activation, go-live and instance swap. Overwriting the binding with
			// the catalog's own name at any of those points would silently undo a rename.
			final EngineState<LogRecordReference> base = EngineState.<LogRecordReference>builder()
				.version(1L)
				.inactiveCatalogs(new String[]{"orders"})
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("orders", new CatalogFolderId("products_3"))
					}
				)
				.build();
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState built = ExpandedEngineState
				.builder(expanded)
				.withVersion(2L)
				.withCatalog(contract("orders", 5))
				.build();

			assertEquals(new CatalogFolderId("products_3"), built.boundFolderIdFor("orders"));
			assertEquals(new CatalogFolderId("products_3"), expanded.boundFolderIdFor("orders"));
		}

		@Test
		@DisplayName("Binds a catalog the state has never seen to the folder it is handed")
		void shouldBindPreviouslyUnknownCatalogToSuppliedFolder() {
			// The folder a new catalog occupies is decided by whoever materialised it, and has to survive the
			// trip into engine state. Deriving it from the name here is the defect this guards: the
			// restore wrote a whole catalog into `beta_4` and the state bound `beta`, so activation opened an
			// empty directory and reported the data corrupted.
			final EngineState<LogRecordReference> base = engineState(
				1L, new String[0], new String[0], new String[0], null, new CatalogFolderBinding[0]
			);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState built = ExpandedEngineState
				.builder(expanded)
				.withVersion(2L)
				.withCatalog(contract("beta", 1), new CatalogFolderId("beta_4"))
				.build();

			assertEquals(new CatalogFolderId("beta_4"), built.boundFolderIdFor("beta"));
		}

		@Test
		@DisplayName("Refuses to stage a catalog that carries no binding")
		void shouldRejectStagingOfUnboundCatalog() {
			// Re-staging is for catalogs the state already knows; a name arriving unbound means it was never
			// registered. Failing loudly is what stops the folder decision from silently defaulting to the
			// catalog's own name, which is how the binding used to be lost.
			final EngineState<LogRecordReference> base = engineState(
				1L, new String[0], new String[0], new String[0], null, new CatalogFolderBinding[0]
			);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			assertThrows(
				GenericEvitaInternalError.class,
				() -> ExpandedEngineState.builder(expanded).withVersion(2L).withCatalog(contract("beta", 1))
			);
		}

		@Test
		@DisplayName("Leaves an existing binding alone even when a folder is offered")
		void shouldNotRelocateAlreadyBoundCatalog() {
			// A catalog that outlived a rename must keep its folder. Overwriting it with the token a caller
			// happens to pass would undo the rename on the next re-staging.
			final EngineState<LogRecordReference> base = engineState(
				1L, new String[0], new String[]{"orders"}, new String[0], null,
				new CatalogFolderBinding[]{
					new CatalogFolderBinding("orders", new CatalogFolderId("products_3"))
				}
			);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState built = ExpandedEngineState
				.builder(expanded)
				.withVersion(2L)
				.withCatalog(contract("orders", 5), new CatalogFolderId("orders_9"))
				.build();

			assertEquals(new CatalogFolderId("products_3"), built.boundFolderIdFor("orders"));
		}

		@Test
		@DisplayName("Drops the binding when a catalog is removed and keeps it when one goes missing")
		void shouldDropBindingOnRemovalAndKeepItOnMissing() {
			final EngineState<LogRecordReference> base = EngineState.<LogRecordReference>builder()
				.version(1L)
				.activeCatalogs(new String[]{"alpha", "beta"})
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("alpha", new CatalogFolderId("alpha_1")),
						new CatalogFolderBinding("beta", new CatalogFolderId("beta_1"))
					}
				)
				.build();
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState built = ExpandedEngineState
				.builder(expanded)
				.withVersion(2L)
				.withoutCatalog("alpha")
				.withMissingCatalog("beta")
				.build();

			// nothing points at `alpha`'s folder any more
			assertNull(built.boundFolderIdFor("alpha"));
			// `beta`'s folder merely vanished - the binding names what a later reappearance must be matched
			// against, so dropping it would make the recovered folder indistinguishable from a hand-placed one
			assertEquals(new CatalogFolderId("beta_1"), built.boundFolderIdFor("beta"));
			assertArrayEquals(new String[]{"beta"}, built.engineState().missingCatalogs());
		}

		@Test
		@DisplayName("Rebinds a catalog that stops being missing to the folder it is re-registered with")
		void shouldRebindCatalogLeavingTheMissingBucket() {
			// `withMissingCatalog` keeps the binding on purpose - it names the folder that vanished. But a name
			// that is still bound is one `withCatalog` will not rebind, so leaving it in place would point the
			// recovered catalog at the folder that went away while its data sits in the one just filled. The
			// catalog would then be staged MISSING again on the very next boot.
			final EngineState<LogRecordReference> base = EngineState.<LogRecordReference>builder()
				.version(1L)
				.missingCatalogs(new String[]{"orders"})
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("orders", new CatalogFolderId("orders_1"))
					}
				)
				.build();
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState built = ExpandedEngineState
				.builder(expanded)
				.withVersion(2L)
				.withCatalogNoLongerMissing("orders")
				.withCatalog(contract("orders", 1), new CatalogFolderId("orders_7"))
				.build();

			assertEquals(new CatalogFolderId("orders_7"), built.boundFolderIdFor("orders"));
			assertEquals(0, built.engineState().missingCatalogs().length);
		}

		@Test
		@DisplayName("Leaves a bound catalog alone when it was never in the missing bucket")
		void shouldNotTouchBindingOfCatalogThatWasNotMissing() {
			// The call is chained unconditionally by three operators, so it has to be inert for the far more
			// common case - otherwise it becomes a silent unbinding of a perfectly healthy catalog.
			final EngineState<LogRecordReference> base = EngineState.<LogRecordReference>builder()
				.version(1L)
				.activeCatalogs(new String[]{"orders"})
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("orders", new CatalogFolderId("orders_1"))
					}
				)
				.build();
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState built = ExpandedEngineState
				.builder(expanded)
				.withVersion(2L)
				.withCatalogNoLongerMissing("orders")
				.build();

			assertEquals(new CatalogFolderId("orders_1"), built.boundFolderIdFor("orders"));
		}

		@Test
		@DisplayName("Keeps the folder when a catalog's instance is swapped, and refuses an unbound one")
		void shouldKeepFolderOnInstanceSwapAndRejectUnbound() {
			// An instance swap replaces the object behind a name that is already registered, so it must leave
			// the folder exactly where it is — including a folder that no longer carries the catalog's name.
			final EngineState<LogRecordReference> base = engineState(
				1L, new String[0], new String[]{"gamma"}, new String[0], null,
				new CatalogFolderBinding[]{
					new CatalogFolderBinding("gamma", new CatalogFolderId("gamma_2"))
				}
			);
			final ExpandedEngineState expanded = ExpandedEngineState.create(base, Map.of());

			final ExpandedEngineState updated = expanded.withUpdatedCatalogInstance(contract("gamma", 1));
			assertEquals(new CatalogFolderId("gamma_2"), updated.boundFolderIdFor("gamma"));

			// a swap cannot introduce a name, so an unbound one is a programming error rather than a new catalog
			final ExpandedEngineState empty = ExpandedEngineState.create(
				engineState(1L, new String[0], new String[0], new String[0], null, new CatalogFolderBinding[0]),
				Map.of()
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> empty.withUpdatedCatalogInstance(contract("gamma", 1))
			);
		}

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
