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

package io.evitadb.spi.store.engine.model;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;

/**
 * This test verifies the functionality of the {@link EngineState} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EngineState functionality tests")
@Tag(ENGINE)
@Tag(EXPORT)
class EngineStateTest {

	@Test
	@DisplayName("Should create EngineState with default values using builder")
	void shouldCreateEngineStateWithDefaultValues() {
		// when
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder().build();

		// then
		assertEquals(0, engineState.storageProtocolVersion());
		assertEquals(0, engineState.version());
		assertNull(engineState.walReference());
		assertNotNull(engineState.activeCatalogs());
		assertEquals(0, engineState.activeCatalogs().length);
		assertNotNull(engineState.inactiveCatalogs());
		assertEquals(0, engineState.inactiveCatalogs().length);
	}

	@Test
	@DisplayName("Should create EngineState with custom values using builder")
	void shouldCreateEngineStateWithCustomValues() {
		// given
		final int storageProtocolVersion = 1;
		final long version = 2L;
		final LogFileRecordReference walFileReference = new LogFileRecordReference(
			index -> CatalogPersistenceService.getWalFileName("test", index),
			3,
			new FileLocation(100L, 200),
			123L
		);
		final String[] activeCatalogs = new String[]{"catalog1", "catalog2"};
		final String[] inactiveCatalogs = new String[]{"catalog3"};

		// when
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(storageProtocolVersion)
			.version(version)
			.walFileReference(walFileReference)
			.activeCatalogs(activeCatalogs)
			.inactiveCatalogs(inactiveCatalogs)
			.build();

		// then
		assertEquals(storageProtocolVersion, engineState.storageProtocolVersion());
		assertEquals(version, engineState.version());
		assertEquals(walFileReference, engineState.walReference());
		assertArrayEquals(activeCatalogs, engineState.activeCatalogs());
		assertArrayEquals(inactiveCatalogs, engineState.inactiveCatalogs());
	}

	@Test
	@DisplayName("Should create modified EngineState using builder copy")
	void shouldCreateModifiedEngineStateUsingWithMethods() {
		// given
		final EngineState<LogFileRecordReference> originalState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(1)
			.version(2L)
			.walFileReference(
				new LogFileRecordReference(
					index -> CatalogPersistenceService.getWalFileName("test", index),
					3, new FileLocation(100L, 200),
					123L
				)
			)
			.activeCatalogs(new String[]{"catalog1", "catalog2"})
			.inactiveCatalogs(new String[]{"catalog3"})
			.build();

		// when
		final EngineState<LogFileRecordReference> modifiedState1 = EngineState.builder(originalState)
			.storageProtocolVersion(10)
			.build();
		final EngineState<LogFileRecordReference> modifiedState2 = EngineState.builder(originalState)
			.version(20L)
			.build();
		final EngineState<LogFileRecordReference> modifiedState3 = EngineState.builder(originalState)
			.walFileReference(
				new LogFileRecordReference(
					index -> CatalogPersistenceService.getWalFileName("modified", index),
					30, new FileLocation(300L, 400),
					123L
				)
			)
			.build();
		final EngineState<LogFileRecordReference> modifiedState4 = EngineState.builder(originalState)
			.activeCatalogs(new String[]{"modified1"})
			.build();
		final EngineState<LogFileRecordReference> modifiedState5 = EngineState.builder(originalState)
			.inactiveCatalogs(new String[]{"modified2", "modified3"})
			.build();

		// then
		assertEquals(10, modifiedState1.storageProtocolVersion());
		assertEquals(originalState.version(), modifiedState1.version());
		assertEquals(originalState.walReference(), modifiedState1.walReference());
		assertArrayEquals(originalState.activeCatalogs(), modifiedState1.activeCatalogs());
		assertArrayEquals(originalState.inactiveCatalogs(), modifiedState1.inactiveCatalogs());

		assertEquals(originalState.storageProtocolVersion(), modifiedState2.storageProtocolVersion());
		assertEquals(20L, modifiedState2.version());
		assertEquals(originalState.walReference(), modifiedState2.walReference());
		assertArrayEquals(originalState.activeCatalogs(), modifiedState2.activeCatalogs());
		assertArrayEquals(originalState.inactiveCatalogs(), modifiedState2.inactiveCatalogs());

		assertEquals(originalState.storageProtocolVersion(), modifiedState3.storageProtocolVersion());
		assertEquals(originalState.version(), modifiedState3.version());
		assertEquals("modified_0.wal", modifiedState3.walReference().walFileNameProvider().apply(0));
		assertEquals(30, modifiedState3.walReference().fileIndex());
		assertArrayEquals(originalState.activeCatalogs(), modifiedState3.activeCatalogs());
		assertArrayEquals(originalState.inactiveCatalogs(), modifiedState3.inactiveCatalogs());

		assertEquals(originalState.storageProtocolVersion(), modifiedState4.storageProtocolVersion());
		assertEquals(originalState.version(), modifiedState4.version());
		assertEquals(originalState.walReference(), modifiedState4.walReference());
		assertArrayEquals(new String[]{"modified1"}, modifiedState4.activeCatalogs());
		assertArrayEquals(originalState.inactiveCatalogs(), modifiedState4.inactiveCatalogs());

		assertEquals(originalState.storageProtocolVersion(), modifiedState5.storageProtocolVersion());
		assertEquals(originalState.version(), modifiedState5.version());
		assertEquals(originalState.walReference(), modifiedState5.walReference());
		assertArrayEquals(originalState.activeCatalogs(), modifiedState5.activeCatalogs());
		assertArrayEquals(new String[]{"modified2", "modified3"}, modifiedState5.inactiveCatalogs());
	}

	@Test
	@DisplayName("Should create EngineState from existing instance using builder")
	void shouldCreateEngineStateFromExistingInstanceUsingBuilder() {
		// given
		final EngineState<LogFileRecordReference> originalState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(1)
			.version(2L)
			.walFileReference(
				new LogFileRecordReference(
					index -> CatalogPersistenceService.getWalFileName("test", index),
					3, new FileLocation(100L, 200),
					123L
				)
			)
			.activeCatalogs(new String[]{"catalog1", "catalog2"})
			.inactiveCatalogs(new String[]{"catalog3"})
			.build();

		// when
		final EngineState<LogFileRecordReference> modifiedState = EngineState.builder(originalState)
			.storageProtocolVersion(10)
			.activeCatalogs(new String[]{"modified1"})
			.build();

		// then
		assertEquals(10, modifiedState.storageProtocolVersion());
		assertEquals(originalState.version(), modifiedState.version());
		assertEquals(originalState.walReference(), modifiedState.walReference());
		assertArrayEquals(new String[]{"modified1"}, modifiedState.activeCatalogs());
		assertArrayEquals(originalState.inactiveCatalogs(), modifiedState.inactiveCatalogs());
	}

	@Test
	@DisplayName("Should preserve introducedAt when copying via builder")
	void shouldPreserveIntroducedAtWhenCopyingViaBuilder() {
		// Construct an engine state with a known introduced-at far in the past so
		// we can detect any accidental "refresh to now" during a builder copy.
		final OffsetDateTime originalIntroducedAt = OffsetDateTime.parse("2020-01-15T08:30:00Z");
		final EngineState<LogFileRecordReference> originalState = new EngineState<>(
			1,
			1L,
			originalIntroducedAt,
			null,
			new String[]{"alpha"},
			new String[0],
			new String[0]
		);

		// Round-trip through the copy builder with an unrelated modification.
		final EngineState<LogFileRecordReference> rewritten = EngineState.builder(originalState)
			.activeCatalogs(new String[]{"alpha", "beta"})
			.build();

		assertEquals(
			originalIntroducedAt, rewritten.introducedAt(),
			"Builder copy must preserve introducedAt — rewriting the engine state for reconciliation " +
				"purposes must not reset the original creation timestamp."
		);
	}

	@Test
	@DisplayName("Should default introducedAt to now for fresh builders")
	void shouldDefaultIntroducedAtToNowForFreshBuilders() {
		// The no-arg builder has no source timestamp to carry forward — it should
		// fall back to the current time so genuinely new states are timestamped.
		final OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(1)
			.version(1L)
			.build();
		final OffsetDateTime after = OffsetDateTime.now().plusSeconds(1);

		assertNotNull(engineState.introducedAt());
		assertTrue(engineState.introducedAt().isAfter(before));
		assertTrue(engineState.introducedAt().isBefore(after));
	}

	@Test
	@DisplayName("Should handle null values correctly")
	void shouldHandleNullValuesCorrectly() {
		// when
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
			.walFileReference(null)
			.activeCatalogs(null)
			.inactiveCatalogs(null)
			.build();

		// then
		assertNull(engineState.walReference());
		assertNotNull(engineState.activeCatalogs());
		assertEquals(0, engineState.activeCatalogs().length);
		assertNotNull(engineState.inactiveCatalogs());
		assertEquals(0, engineState.inactiveCatalogs().length);
	}

	/**
	 * Covers the engine state in its role as the sole authority for the catalog-to-folder mapping — see
	 * {@link CatalogFolderBinding} and issue #649.
	 */
	@Nested
	@DisplayName("Catalog folder bindings")
	class CatalogFolderBindings {

		@Test
		@DisplayName("Resolves a bound catalog and reports an unbound one instead of guessing")
		void shouldResolveBoundCatalogAndReportUnboundOne() {
			final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
				.activeCatalogs(new String[]{"products"})
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("products", new CatalogFolderId("products_7"))
					}
				)
				.build();

			assertEquals(new CatalogFolderId("products_7"), engineState.boundFolderIdFor("products"));
			// answering an unbound name with the name itself is exactly the guess this lookup must not make
			assertNull(engineState.boundFolderIdFor("orders"));
		}

		@Test
		@DisplayName("Translates a state that predates the folder map into identity bindings")
		void shouldSynthesiseIdentityBindingsForLegacyShapedState() {
			// the eight-argument constructor is the shape every backward-compatible serializer delegates to;
			// under that format a catalog's folder *was* its name, so the bindings are not empty but identity
			final EngineState<LogFileRecordReference> engineState = new EngineState<>(
				1, 1L, OffsetDateTime.parse("2026-01-01T00:00:00Z"), null,
				new String[]{"products"},
				new String[]{"orders"},
				new String[0],
				new String[]{"archive"}
			);

			// every bucket a catalog name can sit in must come out bound - a lookup has to be total from the
			// first moment the state exists
			assertEquals(new CatalogFolderId("products"), engineState.boundFolderIdFor("products"));
			assertEquals(new CatalogFolderId("orders"), engineState.boundFolderIdFor("orders"));
			assertEquals(new CatalogFolderId("archive"), engineState.boundFolderIdFor("archive"));
			assertEquals(3, engineState.catalogFolders().length);
			// nothing was ever allocated or retired under that layout
			assertEquals(0, engineState.retiredFolders().length);
			assertEquals(0, engineState.generationPeaks().length);
		}

		@Test
		@DisplayName("Rebinds an existing name rather than leaving the old folder in place")
		void shouldReplaceBindingOfAlreadyBoundCatalog() {
			// a rename and a replace both work by rebinding a name that is already in the array - the shared
			// ordered-array helper inserts only when absent, which would silently keep the stale folder
			final CatalogFolderBinding[] bindings = {
				new CatalogFolderBinding("alpha", new CatalogFolderId("alpha_1")),
				new CatalogFolderBinding("beta", new CatalogFolderId("beta_1"))
			};

			final CatalogFolderBinding[] rebound = EngineState.withBinding(
				bindings, new CatalogFolderBinding("alpha", new CatalogFolderId("alpha_2"))
			);

			assertEquals(2, rebound.length);
			assertEquals(new CatalogFolderId("alpha_2"), rebound[0].folderId());
			assertEquals(new CatalogFolderId("beta_1"), rebound[1].folderId());
			// the input array must not be modified - engine states are shared immutable snapshots
			assertEquals(new CatalogFolderId("alpha_1"), bindings[0].folderId());
		}

		@Test
		@DisplayName("Keeps the binding array ascending when a new name is inserted or one is dropped")
		void shouldKeepBindingArrayOrderedAcrossInsertAndRemoval() {
			final CatalogFolderBinding[] bindings = {
				new CatalogFolderBinding("alpha", new CatalogFolderId("alpha_1")),
				new CatalogFolderBinding("gamma", new CatalogFolderId("gamma_1"))
			};

			final CatalogFolderBinding[] inserted = EngineState.withBinding(
				bindings, new CatalogFolderBinding("beta", new CatalogFolderId("beta_1"))
			);
			assertArrayEquals(
				new String[]{"alpha", "beta", "gamma"},
				new String[]{inserted[0].catalogName(), inserted[1].catalogName(), inserted[2].catalogName()}
			);

			final CatalogFolderBinding[] removed = EngineState.withoutBinding(inserted, "beta");
			assertArrayEquals(
				new String[]{"alpha", "gamma"},
				new String[]{removed[0].catalogName(), removed[1].catalogName()}
			);
			// dropping a name that was never bound is a no-op rather than an error
			assertSame(removed, EngineState.withoutBinding(removed, "delta"));
		}

		@Test
		@DisplayName("Normalizes builder input into the ordering the record requires")
		void shouldSortFolderArraysSuppliedOutOfOrder() {
			// the record asserts strict ascending order in its compact constructor, so the builder has to
			// normalize whatever a caller hands it rather than pass it straight through
			final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("zulu", new CatalogFolderId("zulu_1")),
						new CatalogFolderBinding("alpha", new CatalogFolderId("alpha_1"))
					}
				)
				.retiredFolders(
					new RetiredFolder[]{
						new RetiredFolder("zulu", new CatalogFolderId("zulu_9")),
						new RetiredFolder("alpha", new CatalogFolderId("alpha_2"))
					}
				)
				.generationPeaks(
					new CatalogGenerationPeak[]{
						new CatalogGenerationPeak("zulu", 9),
						new CatalogGenerationPeak("alpha", 2)
					}
				)
				.build();

			assertEquals("alpha", engineState.catalogFolders()[0].catalogName());
			// tombstones order by folder token, because one catalog may have several folders awaiting deletion
			assertEquals("alpha_2", engineState.retiredFolders()[0].folderId().id());
			assertEquals("alpha", engineState.generationPeaks()[0].catalogName());
		}

		@Test
		@DisplayName("Carries bindings, tombstones and peaks through a builder copy")
		void shouldPreserveFolderStateAcrossBuilderCopy() {
			// a state rewritten for an unrelated reason must not lose its folder mapping - it cannot be
			// reconstructed from anything else once gone
			final EngineState<LogFileRecordReference> originalState = EngineState.<LogFileRecordReference>builder()
				.activeCatalogs(new String[]{"products"})
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("products", new CatalogFolderId("products_7"))
					}
				)
				.retiredFolders(
					new RetiredFolder[]{new RetiredFolder("products", new CatalogFolderId("products_6"))}
				)
				.generationPeaks(new CatalogGenerationPeak[]{new CatalogGenerationPeak("products", 7)})
				.build();

			final EngineState<LogFileRecordReference> rewritten = EngineState.builder(originalState)
				.storageProtocolVersion(9)
				.build();

			assertEquals(new CatalogFolderId("products_7"), rewritten.boundFolderIdFor("products"));
			assertArrayEquals(originalState.retiredFolders(), rewritten.retiredFolders());
			assertArrayEquals(originalState.generationPeaks(), rewritten.generationPeaks());
		}

		@Test
		@DisplayName("Accepts a token containing dots, and refuses one that is a traversal segment")
		void shouldRejectOnlyTraversalSegments() {
			// A catalog name may contain `.` anywhere - the classifier format allows it - so `foo..bar` is a
			// legitimate name whose folder token is either the identity binding a pre-#649 state translates to
			// or the `foo..bar_1` an allocation produces. Refusing every occurrence of `..` would refuse to boot
			// such an installation, and buys nothing: without a separator the token is a single segment.
			assertEquals("foo..bar", new CatalogFolderId("foo..bar").id());
			assertEquals("foo..bar_1", new CatalogFolderId("foo..bar_1").id());

			// what the check is actually for: a token that escapes the storage root, or names it
			assertThrows(GenericEvitaInternalError.class, () -> new CatalogFolderId(".."));
			assertThrows(GenericEvitaInternalError.class, () -> new CatalogFolderId("."));
			assertThrows(GenericEvitaInternalError.class, () -> new CatalogFolderId("../sibling"));
			assertThrows(GenericEvitaInternalError.class, () -> new CatalogFolderId("nested/folder"));
			assertThrows(GenericEvitaInternalError.class, () -> new CatalogFolderId("nested\\folder"));
			assertThrows(GenericEvitaInternalError.class, () -> new CatalogFolderId("   "));
		}

	}
}
