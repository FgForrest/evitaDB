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


package io.evitadb.store.catalog;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.FAR;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.U1;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.U2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks representative collisions against published release manifests before conversion or chained migrations. */
@Tag(STORAGE)
@Tag(INDEXING)
@DisplayName("Legacy representative partition preflight")
class LegacyRepresentativeKeyPreflightTest implements EvitaTestSupport {
	/** Supplies isolated copies of the release catalogs. */
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/** Both partitions and their raw offset spellings must survive a refused upgrade. */
	@ParameterizedTest
	@ValueSource(strings = {"d6", "d6c", "d8"})
	@DisplayName("Should refuse colliding partitions before publishing any migration")
	void shouldRefuseCollidingPartitionsBeforePublishing(@Nonnull String fixture) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			final var before = this.harness.bootstrapRecords(paths, fixture);
			final var files = ReleaseFixtureHarness.fileDigests(ReleaseFixtureHarness.catalogDirectory(paths, fixture));
			final Exception failure;
			try (final Evita evita = this.harness.boot(paths)) {
				failure = assertThrows(Exception.class, evita::waitUntilFullyInitialized,
					"Colliding representative partitions must refuse before publication");
			}
			assertEquals(before, this.harness.bootstrapRecords(paths, fixture),
				"A refused migration chain must retain the original published bootstrap");
			assertEquals(fixture.equals("d6c") ? 4 : 6,
				this.harness.lastBootstrapStorageProtocol(paths, fixture));
			ReleaseFixtureHarness.assertByteIdentical(files, ReleaseFixtureHarness.catalogDirectory(paths, fixture));
			final String message = refusal(failure).getMessage();
			for (final String detail : List.of(
				fixture, "product", "brand", "validity", "scope LIVE", "target 1", "owner",
				"[,2026-01-01T10:00:00+01:00]", "[,2026-01-01T11:00:00+02:00]",
				"Nothing has been published", "storage protocol version " + before.getLast().storageProtocolVersion()
			)) {
				assertTrue(message.contains(detail), message);
			}
			if (fixture.equals("d8")) {
				assertTrue(message.contains("Both stored partitions contain owner 1"), message);
			}
			if (!fixture.equals("d8")) {
				assertTrue(message.contains("[1]") && message.contains("[2]"), message);
			}
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** The early reader owns and closes its collection services even though no rewrite follows it. */
	@ParameterizedTest
	@ValueSource(strings = {"d1a", "d9"})
	@DisplayName("Should close read-only preflight collection services")
	void shouldCloseReadOnlyPreflightCollectionServices(@Nonnull String fixture) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			OfflineCatalogImages.withOfflineCatalog(
				newTestEvitaConfigurationBuilder(paths).build(), fixture, service -> {
				final long version = service.getLastCatalogVersion();
				final var catalogParts = service.getStoragePartPersistenceService(version);
				final var before = this.harness.bootstrapRecords(paths, fixture);
				final List<DefaultEntityCollectionPersistenceService> opened = new ArrayList<>();
				LegacyRepresentativeKeyPreflight.verifyBeforeMigrationChain(
					catalogParts.getCatalogHeader(version), catalogParts, header -> {
						final var collection = service.createEntityCollectionPersistenceService(header);
						opened.add(collection);
						return collection;
					});
				assertFalse(opened.isEmpty(), "the preflight must open at least one collection");
				assertTrue(opened.stream().allMatch(DefaultEntityCollectionPersistenceService::isClosed));
				assertEquals(before, this.harness.bootstrapRecords(paths, fixture));
				return Boolean.TRUE;
			});
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** Index family, scope, target, reference and tuple position each remain part of partition identity. */
	@Test
	@DisplayName("Should retain positional manifests and distinguish complete partition identities")
	void shouldRetainPositionalManifestsAndDistinguishPartitionIdentities() {
		final EntityIndexStoragePart first = manifest(1, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, "brand", 1, U1);
		final EntityIndexStoragePart second =
			manifest(2, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, "brand", 1, U2);
		final var collisions = LegacyRepresentativeKeyPreflight.findCollidingManifests(List.of(first, second));
		assertEquals(1, collisions.size());
		assertSame(first, collisions.getFirst().first());
		assertSame(second, collisions.getFirst().second());
		assertEquals(0, LegacyRepresentativeKeyPreflight.findCollidingManifests(List.of(
			first, first,
			manifest(3, EntityIndexType.REFERENCED_ENTITY, Scope.ARCHIVED, "brand", 1, U2),
			manifest(4, EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, "brand", 1, U2),
			manifest(5, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, "brand", 2, U2),
			manifest(6, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, "other", 1, U2),
			manifest(7, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, "brand", 1, FAR)
		)).size());
		final EntityIndexStoragePart left = manifest(8, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			"brand", 1, U1, FAR);
		final EntityIndexStoragePart right = manifest(9, EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			"brand", 1, FAR, U2);
		assertTrue(LegacyRepresentativeKeyPreflight.findCollidingManifests(List.of(left, right)).isEmpty());
	}

	/** Builds an actual manifest with its complete partition identity and untouched positional values. */
	@Nonnull
	private static EntityIndexStoragePart manifest(
		int id, @Nonnull EntityIndexType type, @Nonnull Scope scope, @Nonnull String reference,
		int target, @Nonnull Serializable... values
	) {
		return new EntityIndexStoragePart(id, 1,
			new EntityIndexKey(type, scope,
				new RepresentativeReferenceKey(new ReferenceKey(reference, target), values)),
			Set.of(), Set.of(), false, Set.of(), Set.of());
	}

	/** Finds the deliberate protocol refusal even when catalog initialization wraps it. */
	@Nonnull
	static ObsoleteStorageProtocolException refusal(@Nonnull Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof ObsoleteStorageProtocolException refusal) {
				return refusal;
			}
			current = current.getCause();
		}
		throw new AssertionError("Expected a deliberate storage protocol refusal", failure);
	}
}
