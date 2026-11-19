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

package io.evitadb.core.catalog;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@link Catalog#terminate()} publishes of the catalog-wide index when it terminates a `WARMING_UP` catalog
 * whose writer is still registered and idle.
 *
 * A warm-up write of an entity carrying a globally unique attribute changes two structures: the entity collection,
 * and the catalog index that maps the value to the entity across every collection. The collection reaches the disk
 * through its own header. The catalog index lives in the catalog's own data store buffer and reaches the disk only when
 * that buffer is drained, which the session close's flush does before it stores the catalog header. A termination that
 * publishes has to drain it too, or the header it stores names collections whose entities the catalog index of the
 * same published state does not know about - or still knows about after they were removed.
 *
 * A globally unique attribute is looked up in the catalog index whether or not the query names a collection, so a
 * catalog index that misses a value hides the entity from both lookups, and one that keeps a removed value keeps
 * answering with the removed entity and refusing the value to everybody else.
 *
 * Every assertion about what was published reads a fresh engine opened over a copy of the storage taken right after
 * the termination, because the in-memory catalog answers for state that never reached the disk. A product published by
 * the set-up session and never touched again is the in-snapshot control of the catalog-wide lookup, and the tests that
 * close the writer before the termination are the controls of the whole scenario: they publish through the session
 * close, and must pass on the same assertions.
 */
@DisplayName("Termination of a warming-up catalog publishing the catalog-wide unique index")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SESSION)
@Tag(ATTRIBUTE)
class CatalogWarmUpTerminationCatalogIndexTest implements EvitaTestSupport {

	/** A catalog attribute unique across all collections, which is what the catalog index holds. */
	private static final String ATTRIBUTE_CODE = "code";
	/** Primary key of the product published by the set-up session, which the writer removes in some tests. */
	private static final int PUBLISHED_PK = 1;
	/** Code of the product published by the set-up session, which the writer removes in some tests. */
	private static final String PUBLISHED_CODE = "published";
	/** Primary key of the product published by the set-up session and never touched again. */
	private static final int KEPT_PK = 3;
	/** Code of the product published by the set-up session and never touched again - the in-snapshot control. */
	private static final String KEPT_CODE = "kept";
	/** Primary key of the product the writer inserts. */
	private static final int WRITTEN_PK = 2;
	/** Code of the product the writer inserts. */
	private static final String WRITTEN_CODE = "written";
	/** Primary key of the brand the snapshot reader tries to store under an already used or released code. */
	private static final int PROBE_BRAND_PK = 10;

	private TestPaths paths;
	private TestPaths snapshotPaths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogWarmUpTerminationCatalogIndex");
		this.snapshotPaths = createTestPaths("CatalogWarmUpTerminationCatalogIndexSnapshot");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally())
			.updateViaNewSession(this.evita);
		// published by the close of this session - the last published state every test starts from, including a
		// catalog index already stored on disk
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.updateVia(session);
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.updateVia(session);
				session.createNewEntity(Entities.PRODUCT, PUBLISHED_PK)
					.setAttribute(ATTRIBUTE_CODE, PUBLISHED_CODE)
					.upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, KEPT_PK)
					.setAttribute(ATTRIBUTE_CODE, KEPT_CODE)
					.upsertVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
		cleanupTestPaths(this.snapshotPaths);
	}

	@Nested
	@DisplayName("when the writer is still registered")
	class IdleWriter {

		@Test
		@DisplayName("should publish the catalog-wide unique value of an inserted entity")
		void shouldPublishCatalogWideUniqueValueOfInsertedEntity() throws IOException {
			final CatalogWarmUpTerminationCatalogIndexTest test = CatalogWarmUpTerminationCatalogIndexTest.this;
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			insertWrittenProduct(writer);

			test.terminateAndSnapshot();

			test.assertInsertionPublished();
		}

		@Test
		@DisplayName("should release the catalog-wide unique value of a removed entity")
		void shouldReleaseCatalogWideUniqueValueOfRemovedEntity() throws IOException {
			final CatalogWarmUpTerminationCatalogIndexTest test = CatalogWarmUpTerminationCatalogIndexTest.this;
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			assertTrue(writer.deleteEntity(Entities.PRODUCT, PUBLISHED_PK));

			test.terminateAndSnapshot();

			test.assertRemovalPublished();
		}
	}

	@Nested
	@DisplayName("when the writer closed before the termination")
	class ClosedWriter {

		@Test
		@DisplayName("should publish the catalog-wide unique value of an inserted entity")
		void shouldPublishCatalogWideUniqueValueOfInsertedEntity() throws IOException {
			final CatalogWarmUpTerminationCatalogIndexTest test = CatalogWarmUpTerminationCatalogIndexTest.this;
			try (final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG)) {
				insertWrittenProduct(writer);
			}

			test.terminateAndSnapshot();

			test.assertInsertionPublished();
		}

		@Test
		@DisplayName("should release the catalog-wide unique value of a removed entity")
		void shouldReleaseCatalogWideUniqueValueOfRemovedEntity() throws IOException {
			final CatalogWarmUpTerminationCatalogIndexTest test = CatalogWarmUpTerminationCatalogIndexTest.this;
			try (final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG)) {
				assertTrue(writer.deleteEntity(Entities.PRODUCT, PUBLISHED_PK));
			}

			test.terminateAndSnapshot();

			test.assertRemovalPublished();
		}
	}

	/**
	 * Inserts the product carrying {@link #WRITTEN_CODE}.
	 *
	 * @param writer the read-write session to insert it in
	 */
	private static void insertWrittenProduct(@Nonnull EvitaSessionContract writer) {
		writer.createNewEntity(Entities.PRODUCT, WRITTEN_PK)
			.setAttribute(ATTRIBUTE_CODE, WRITTEN_CODE)
			.upsertVia(writer);
	}

	/**
	 * Looks the code up and renders what was found as `type:pk`, so a mismatch reads directly in the assertion message.
	 *
	 * @param session    the session to query in
	 * @param entityType the collection to look in, or `null` for the catalog-wide lookup the catalog index answers
	 * @param code       the code to look up
	 * @return the found entities; never null
	 */
	@Nonnull
	private static List<String> lookUp(
		@Nonnull EvitaSessionContract session,
		@Nullable String entityType,
		@Nonnull String code
	) {
		final Query query = entityType == null ?
			Query.query(filterBy(attributeEquals(ATTRIBUTE_CODE, code))) :
			Query.query(collection(entityType), filterBy(attributeEquals(ATTRIBUTE_CODE, code)));
		return session.queryListOfEntityReferences(query)
			.stream()
			.map(CatalogWarmUpTerminationCatalogIndexTest::describe)
			.toList();
	}

	/**
	 * Renders an entity reference as `type:pk`.
	 *
	 * @param reference the reference to render
	 * @return the rendered reference
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Terminates the catalog directly - what engine shutdown does once it reaches the catalog - and copies the storage
	 * aside for the snapshot engine.
	 *
	 * @throws IOException when the storage cannot be copied
	 */
	private void terminateAndSnapshot() throws IOException {
		final Catalog catalog = assertInstanceOf(
			Catalog.class, this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
		);
		catalog.terminate();
		assertTrue(catalog.isTerminated());
		snapshotStorage();
	}

	/**
	 * Asserts that the snapshot holds the product the writer inserted, and that the catalog index of the same snapshot
	 * finds it by its code and refuses the code in another collection.
	 */
	private void assertInsertionPublished() {
		try (final Evita snapshotEvita = openSnapshot()) {
			final Lookups lookups = readLookups(snapshotEvita, WRITTEN_PK, WRITTEN_CODE);
			// writes to the snapshot, so it runs after every read
			final Throwable duplicateRefusal = storeProbeBrand(snapshotEvita, WRITTEN_CODE);
			final List<String> writtenProduct = List.of(Entities.PRODUCT + ":" + WRITTEN_PK);
			assertAll(
				() -> assertKeptProductFoundCatalogWide(lookups),
				() -> assertTrue(lookups.entityPresent(), "the inserted product must be published"),
				() -> assertEquals(
					writtenProduct, lookups.inCollection(),
					"the lookup within the product collection must find the inserted product - a globally unique " +
						"attribute is looked up in the catalog index even within a collection"
				),
				() -> assertEquals(
					writtenProduct, lookups.catalogWide(),
					"the catalog-wide lookup must find the inserted product - the catalog index published with it " +
						"misses its code"
				),
				() -> assertInstanceOf(
					UniqueValueViolationException.class, duplicateRefusal,
					"a brand must not be stored under the code of the published product, but storing it " +
						(duplicateRefusal == null ? "succeeded" : "failed differently: " + duplicateRefusal)
				)
			);
		}
	}

	/**
	 * Asserts that the snapshot no longer holds the removed product, and that the catalog index of the same snapshot
	 * no longer maps its code to it and accepts the code in another collection.
	 */
	private void assertRemovalPublished() {
		try (final Evita snapshotEvita = openSnapshot()) {
			final Lookups lookups = readLookups(snapshotEvita, PUBLISHED_PK, PUBLISHED_CODE);
			// writes to the snapshot, so it runs after every read
			final Throwable releasedCodeRefusal = storeProbeBrand(snapshotEvita, PUBLISHED_CODE);
			assertAll(
				() -> assertKeptProductFoundCatalogWide(lookups),
				() -> assertFalse(lookups.entityPresent(), "the removal of the product must be published"),
				() -> assertEquals(
					List.of(), lookups.inCollection(),
					"the lookup within the product collection must not find the removed product - a globally unique " +
						"attribute is looked up in the catalog index even within a collection"
				),
				() -> assertEquals(
					List.of(), lookups.catalogWide(),
					"the catalog-wide lookup must not find the removed product - the catalog index published with " +
						"its removal still maps its code"
				),
				() -> assertNull(
					releasedCodeRefusal,
					"a brand must be storable under the code the removed product released"
				)
			);
		}
	}

	/**
	 * Asserts the in-snapshot control: the catalog-wide lookup finds the product nobody touched after the set-up
	 * session published it, so a lookup that misses the product under test misses it in a snapshot that answers
	 * catalog-wide lookups at all.
	 *
	 * @param lookups what the snapshot answered
	 */
	private static void assertKeptProductFoundCatalogWide(@Nonnull Lookups lookups) {
		assertEquals(
			List.of(Entities.PRODUCT + ":" + KEPT_PK), lookups.catalogWideKept(),
			"the catalog-wide lookup must find the product published by the set-up session"
		);
	}

	/**
	 * Reads, in one read-only session of the snapshot, everything the assertions compare.
	 *
	 * @param snapshotEvita the engine over the snapshot
	 * @param primaryKey    primary key of the product the test is about
	 * @param code          code of the product the test is about
	 * @return what the snapshot answered
	 */
	@Nonnull
	private static Lookups readLookups(@Nonnull Evita snapshotEvita, int primaryKey, @Nonnull String code) {
		final Function<EvitaSessionContract, Lookups> reader = session -> new Lookups(
			lookUp(session, null, KEPT_CODE),
			session.getEntity(Entities.PRODUCT, primaryKey).isPresent(),
			lookUp(session, Entities.PRODUCT, code),
			lookUp(session, null, code)
		);
		return snapshotEvita.queryCatalog(TEST_CATALOG, reader);
	}

	/**
	 * Stores a brand under the given code in the snapshot.
	 *
	 * @param snapshotEvita the engine over the snapshot
	 * @param code          the code to store the brand under
	 * @return the failure of the store, or `null` when the brand was stored
	 */
	@Nullable
	private static Throwable storeProbeBrand(@Nonnull Evita snapshotEvita, @Nonnull String code) {
		try {
			snapshotEvita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.BRAND, PROBE_BRAND_PK)
						.setAttribute(ATTRIBUTE_CODE, code)
						.upsertVia(session);
				}
			);
			return null;
		} catch (RuntimeException ex) {
			return ex;
		}
	}

	/**
	 * Copies the whole storage directory of the running engine aside, so what reached the disk can be read by a
	 * fresh engine while the original one still has the writer registered.
	 *
	 * @throws IOException when the storage cannot be copied
	 */
	private void snapshotStorage() throws IOException {
		final Path source = this.paths.storage();
		final Path target = this.snapshotPaths.storage();
		final List<Path> entries;
		try (final Stream<Path> tree = Files.walk(source)) {
			entries = tree.toList();
		}
		for (final Path entry : entries) {
			final Path destination = target.resolve(source.relativize(entry).toString());
			if (Files.isDirectory(entry)) {
				Files.createDirectories(destination);
			} else {
				Files.createDirectories(destination.getParent());
				Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Opens a fresh engine over the storage snapshot.
	 *
	 * @return the engine; the caller closes it
	 */
	@Nonnull
	private Evita openSnapshot() {
		final Evita snapshotEvita = new Evita(newTestEvitaConfigurationBuilder(this.snapshotPaths).build());
		snapshotEvita.waitUntilFullyInitialized();
		return snapshotEvita;
	}

	/**
	 * What the snapshot answered, read in one session before anything is written to it.
	 *
	 * @param catalogWideKept the catalog-wide lookup of {@link #KEPT_CODE}
	 * @param entityPresent   whether the product the test is about is present
	 * @param inCollection    the lookup of the code of the product the test is about within the product collection
	 * @param catalogWide     the catalog-wide lookup of the code of the product the test is about
	 */
	private record Lookups(
		@Nonnull List<String> catalogWideKept,
		boolean entityPresent,
		@Nonnull List<String> inCollection,
		@Nonnull List<String> catalogWide
	) {
	}

}
