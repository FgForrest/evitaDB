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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.upgrade.Release2026_1FixtureRecipes;
import io.evitadb.test.upgrade.Release2026_2FixtureRecipes;
import io.evitadb.test.upgrade.ReleaseFixtureRecipe;
import io.evitadb.test.upgrade.ReleaseFixtureWriter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.BRAND;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opens the release-written upgrade fixtures under `testData/release_upgrade_fixtures` with the current engine, and
 * reads what the upgrade left on disk. Each fixture is a storage directory of its own, written by the released engines
 * from a recipe in `io.evitadb.test.upgrade` (see that package for how to regenerate them); the harness always works on
 * a fresh copy, so the committed bytes stay untouched.
 *
 * The same recipe can be replayed into a fresh catalog of the current engine
 * ({@link #withFreshCatalog(String, Consumer)}), which is what an upgraded catalog is compared with: an assertion that
 * fails on the upgraded fixture and holds on the fresh replay separates an upgrade defect from correct behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReleaseFixtureHarness {

	/**
	 * Class-path location of the fixtures, one storage directory per fixture.
	 */
	static final String FIXTURES = "testData/release_upgrade_fixtures";

	/**
	 * The test whose paths, configuration and clean-up the harness uses.
	 */
	private final EvitaTestSupport support;

	/**
	 * Creates a harness working in the test directories of the given test.
	 *
	 * @param support the test
	 */
	ReleaseFixtureHarness(@Nonnull EvitaTestSupport support) {
		this.support = support;
	}

	/**
	 * Returns the recipe a fixture was written from.
	 *
	 * @param fixture the fixture name
	 * @return the recipe
	 * @throws IllegalArgumentException when no recipe has that name
	 */
	@Nonnull
	static ReleaseFixtureRecipe recipe(@Nonnull String fixture) {
		return Stream.concat(Release2026_2FixtureRecipes.all().stream(), Release2026_1FixtureRecipes.all().stream())
			.filter(it -> it.name().equals(fixture))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("No recipe of fixture `" + fixture + "`."));
	}

	/**
	 * Copies the fixture into fresh test directories. The copy is the storage directory of the returned paths; the
	 * caller boots engines over it with {@link #boot(TestPaths)} and removes it with {@link #cleanup(TestPaths)}.
	 *
	 * @param fixture the fixture name
	 * @return the paths holding the copy
	 */
	@Nonnull
	TestPaths copy(@Nonnull String fixture) {
		final TestPaths paths = this.support.createTestPaths("ReleaseFixture_" + fixture);
		try {
			final Path source = Path.of(
				Objects.requireNonNull(
					ReleaseFixtureHarness.class.getClassLoader().getResource(FIXTURES + "/" + fixture),
					"Fixture `" + fixture + "` is not on the class path."
				).toURI()
			);
			try (Stream<Path> files = Files.walk(source)) {
				for (Path file : files.toList()) {
					final Path target = paths.storage().resolve(source.relativize(file).toString());
					if (Files.isDirectory(file)) {
						Files.createDirectories(target);
					} else {
						Files.copy(file, target);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
		return paths;
	}

	/**
	 * Starts the current engine over the paths, with sessions that never time out.
	 *
	 * @param paths the paths, usually a {@link #copy(String)}
	 * @return the started engine
	 */
	@Nonnull
	Evita boot(@Nonnull TestPaths paths) {
		return new Evita(configuration(paths));
	}

	/**
	 * Removes the paths.
	 *
	 * @param paths the paths
	 */
	void cleanup(@Nonnull TestPaths paths) {
		this.support.cleanupTestPaths(paths);
	}

	/**
	 * Copies the fixture, boots the current engine over it, requires the fixture's catalog to come alive and hands the
	 * engine to `body`.
	 *
	 * @param fixture the fixture name, which is also its catalog name
	 * @param body    the test body
	 */
	void withUpgradedCatalog(@Nonnull String fixture, @Nonnull Consumer<Evita> body) {
		final TestPaths paths = copy(fixture);
		try {
			try (Evita evita = boot(paths)) {
				requireAlive(evita, fixture);
				body.accept(evita);
			}
		} finally {
			cleanup(paths);
		}
	}

	/**
	 * Replays the fixture's recipe into a fresh catalog of the current engine and hands the engine to `body`.
	 *
	 * @param fixture the fixture name, which is also its catalog name
	 * @param body    the test body
	 */
	void withFreshCatalog(@Nonnull String fixture, @Nonnull Consumer<Evita> body) {
		final TestPaths paths = this.support.createTestPaths("ReleaseFixtureFresh_" + fixture);
		try {
			try (Evita evita = boot(paths)) {
				ReleaseFixtureWriter.replay(evita, recipe(fixture));
				body.accept(evita);
			}
		} finally {
			cleanup(paths);
		}
	}

	/**
	 * Runs the assertion on a fresh current engine fed the fixture's recipe first — where it must hold, or the
	 * assertion does not describe correct behaviour — and then on the upgraded fixture.
	 *
	 * @param fixture   the fixture
	 * @param assertion the assertion
	 */
	void assertOnFreshThenUpgraded(@Nonnull String fixture, @Nonnull Consumer<Evita> assertion) {
		withFreshCatalog(fixture, assertion);
		withUpgradedCatalog(fixture, assertion);
	}

	/**
	 * Commits one transaction in the catalog.
	 *
	 * @param evita   the engine
	 * @param catalog the catalog
	 * @param updater the transaction
	 */
	static void update(
		@Nonnull Evita evita,
		@Nonnull String catalog,
		@Nonnull Consumer<EvitaSessionContract> updater
	) {
		evita.updateCatalog(catalog, updater);
	}

	/**
	 * Replaces an attribute of a live product.
	 *
	 * @param session the session
	 * @param pk      the product
	 * @param name    the attribute
	 * @param value   the new value
	 */
	static void setAttribute(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String name,
		@Nonnull Serializable value
	) {
		session.getEntity(PRODUCT, pk, entityFetchAllContent()).orElseThrow()
			.openForWrite()
			.setAttribute(name, value)
			.upsertVia(session);
	}

	/**
	 * Replaces an English attribute of a live product.
	 *
	 * @param session the session
	 * @param pk      the product
	 * @param name    the attribute
	 * @param value   the new value
	 */
	static void setEnglishAttribute(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String name,
		@Nonnull Serializable value
	) {
		session.getEntity(PRODUCT, pk, entityFetchAllContent()).orElseThrow()
			.openForWrite()
			.setAttribute(name, Locale.ENGLISH, value)
			.upsertVia(session);
	}

	/**
	 * Removes every `brand` reference of a live product to the brand.
	 *
	 * @param session the session
	 * @param pk      the product
	 * @param brandPk the brand
	 */
	static void removeBrand(@Nonnull EvitaSessionContract session, int pk, int brandPk) {
		session.getEntity(PRODUCT, pk, entityFetchAllContent()).orElseThrow()
			.openForWrite()
			.removeReferences(BRAND, brandPk)
			.upsertVia(session);
	}

	/**
	 * Returns the answers of the fixture recipe's probes on the given engine, one line per probe.
	 *
	 * @param evita   the engine holding the fixture's catalog
	 * @param fixture the fixture name
	 * @return the answers
	 */
	@Nonnull
	static List<String> probe(@Nonnull Evita evita, @Nonnull String fixture) {
		return ReleaseFixtureWriter.probe(evita, recipe(fixture));
	}

	/**
	 * Waits until the catalog settles and fails, with the catalog's load exception as the cause, unless it is alive.
	 *
	 * @param evita   the engine
	 * @param catalog the catalog
	 */
	static void requireAlive(@Nonnull Evita evita, @Nonnull String catalog) {
		final CatalogState state = ReleaseFixtureWriter.awaitSettled(evita, catalog);
		if (state != CatalogState.ALIVE) {
			final CatalogContract instance = evita.getCatalogInstance(catalog).orElse(null);
			final RuntimeException cause = instance instanceof UnusableCatalog unusable
				? unusable.getRepresentativeException() : null;
			throw new AssertionError(
				"Released catalog `" + catalog + "` did not load after the upgrade, state " + state, cause
			);
		}
	}

	/**
	 * Returns the primary keys the query answers, in order.
	 *
	 * @param evita   the engine
	 * @param catalog the catalog
	 * @param query   the query
	 * @return the primary keys
	 */
	@Nonnull
	static List<Integer> pks(@Nonnull Evita evita, @Nonnull String catalog, @Nonnull Query query) {
		return evita.queryCatalog(
			catalog,
			session -> {
				return session.queryListOfEntityReferences(query)
					.stream()
					.map(EntityReferenceContract::getPrimaryKey)
					.toList();
			}
		);
	}

	/**
	 * Returns the live collection of the entity type, for assertions on its indexes.
	 *
	 * @param evita      the engine
	 * @param catalog    the catalog
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	static EntityCollection entityCollection(
		@Nonnull Evita evita,
		@Nonnull String catalog,
		@Nonnull String entityType
	) {
		final Catalog instance = (Catalog) evita.getCatalogInstanceOrThrowException(catalog);
		return (EntityCollection) instance.getCollectionForEntity(entityType).orElseThrow();
	}

	/**
	 * Returns the storage protocol in the header of the catalog's last version, read through the loaded catalog.
	 *
	 * @param evita   the engine
	 * @param catalog the loaded catalog
	 * @return the storage protocol version of the header
	 */
	static int headerStorageProtocol(@Nonnull Evita evita, @Nonnull String catalog) {
		// through the wildcard: `Catalog` holds the service under the interface's parameterisation while the
		// implementation declares concrete reference types, so the direct cast is rejected at compile time
		final CatalogPersistenceService<?, ?, ?> service =
			((Catalog) evita.getCatalogInstanceOrThrowException(catalog)).getPersistenceService();
		final DefaultCatalogPersistenceService persistenceService = (DefaultCatalogPersistenceService) service;
		final long version = persistenceService.getLastCatalogVersion();
		return persistenceService.getStoragePartPersistenceService(version)
			.getCatalogHeader(version)
			.storageProtocolVersion();
	}

	/**
	 * Reads every bootstrap record of the catalog in the copy, oldest first. Works with no engine running.
	 *
	 * @param paths   the paths of the copy
	 * @param catalog the catalog
	 * @return the bootstrap records
	 */
	@Nonnull
	List<CatalogBootstrap> bootstrapRecords(@Nonnull TestPaths paths, @Nonnull String catalog) {
		final EvitaConfiguration configuration = configuration(paths);
		final StorageSettings settings = new StorageSettings(configuration.storage(), configuration.transaction())
			.modifyForBootstrapFile();
		try (
			Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				catalog, catalogDirectory(paths, catalog), settings
			)
		) {
			return records.toList();
		}
	}

	/**
	 * Returns the storage protocol the last bootstrap record of the catalog in the copy is stamped with — the record a
	 * boot loads, and the one a release refuses when its protocol is not the release's own.
	 *
	 * @param paths   the paths of the copy
	 * @param catalog the catalog
	 * @return the storage protocol version of the last bootstrap record
	 */
	int lastBootstrapStorageProtocol(@Nonnull TestPaths paths, @Nonnull String catalog) {
		final List<CatalogBootstrap> records = bootstrapRecords(paths, catalog);
		return records.get(records.size() - 1).storageProtocolVersion();
	}

	/**
	 * Returns the folder of the catalog in the copy.
	 *
	 * @param paths   the paths of the copy
	 * @param catalog the catalog
	 * @return the catalog folder
	 */
	@Nonnull
	static Path catalogDirectory(@Nonnull TestPaths paths, @Nonnull String catalog) {
		return EvitaTestSupport.catalogDirectory(paths.storage(), catalog);
	}

	/**
	 * Returns the length of every file in the folder, by file name.
	 *
	 * @param directory the folder
	 * @return file name to length, sorted by name
	 */
	@Nonnull
	static Map<String, Long> fileLengths(@Nonnull Path directory) {
		final Map<String, Long> lengths = new TreeMap<>();
		for (Path file : listFiles(directory)) {
			try {
				lengths.put(file.getFileName().toString(), Files.size(file));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return lengths;
	}

	/**
	 * Returns the SHA-256 digest of every file in the folder, by file name — the snapshot
	 * {@link #assertByteIdentical(Map, Path)} compares a folder with.
	 *
	 * @param directory the folder
	 * @return file name to hexadecimal digest, sorted by name
	 */
	@Nonnull
	static Map<String, String> fileDigests(@Nonnull Path directory) {
		final Map<String, String> digests = new TreeMap<>();
		for (Path file : listFiles(directory)) {
			digests.put(file.getFileName().toString(), digest(file));
		}
		return digests;
	}

	/**
	 * Asserts that the folder holds exactly the files of the snapshot, each with the same bytes.
	 *
	 * @param snapshot  the snapshot taken by {@link #fileDigests(Path)}
	 * @param directory the folder
	 */
	static void assertByteIdentical(@Nonnull Map<String, String> snapshot, @Nonnull Path directory) {
		assertEquals(snapshot, fileDigests(directory), "The files of `" + directory + "` changed.");
	}

	/**
	 * Returns the configuration every engine of the harness runs with.
	 *
	 * @param paths the paths
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths paths) {
		return this.support.newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Lists the regular files directly in the folder.
	 *
	 * @param directory the folder
	 * @return the files
	 */
	@Nonnull
	private static List<Path> listFiles(@Nonnull Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Computes the SHA-256 digest of the file.
	 *
	 * @param file the file
	 * @return the digest in hexadecimal
	 */
	@Nonnull
	private static String digest(@Nonnull Path file) {
		try (InputStream input = Files.newInputStream(file)) {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
