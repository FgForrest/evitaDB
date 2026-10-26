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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.UseGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises ownership changes over surviving keys through live binding, reload and production WAL replay. */
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(SCHEMA)
@Tag(WAL)
@Isolated("Owns engine lifecycles and crash copies")
@DisplayName("Built-with ownership changes only after the attribute empties")
class BuiltWithNormalizationOwnershipFunctionalTest implements EvitaTestSupport {
	private TestPaths paths;
	private TestPaths replayPaths;
	private Evita evita;

	/** Closes the engine and removes both original and crash-copy storage. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
		if (this.replayPaths != null) {
			cleanupTestPaths(this.replayPaths);
		}
	}

	/** Keeps the old chain for queries and old-value removals until an empty identity adopts its new owner. */
	@ParameterizedTest(name = "local to global={0}, {1}")
	@CsvSource({"true, live", "true, restart", "true, replay",
		"false, live", "false, restart", "false, replay"})
	@DisplayName("Populated local and global identities retain ownership across schema replacement")
	void shouldRetainOwnershipUntilEmpty(boolean toGlobal, @Nonnull String recovery) throws Exception {
		this.paths = createTestPaths("BuiltWithNormalizationOwnership");
		this.evita = new Evita(configuration(this.paths));
		this.evita.defineCatalog(TEST_CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			if (toGlobal) {
				session.defineEntitySchema("Owner").withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class,
						a -> a.filterable().sortable().nullable().normalized(lowercase())).updateVia(session);
			} else {
				session.getCatalogSchema().openForWrite().withAttribute("code", String.class,
					a -> a.filterable().sortable().nullable().normalized(lowercase())).updateVia(session);
				session.defineEntitySchema("Owner").withoutGeneratedPrimaryKey().withGlobalAttribute("code")
					.updateVia(session);
			}
			for (int id = 1; id <= 3; id++) {
				session.createNewEntity("Owner", id).setAttribute("code", id == 1 ? "ABC" : "ABC" + id)
					.upsertVia(session);
			}
			if (toGlobal) {
				session.getCatalogSchema().openForWrite().withAttribute("code", String.class,
					a -> a.filterable().sortable().nullable()).updateVia(session);
			}
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			if (toGlobal) {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("Owner",
					new RemoveAttributeSchemaMutation("code"), new UseGlobalAttributeSchemaMutation("code")));
			} else {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("Owner",
					new RemoveAttributeSchemaMutation("code"), new CreateAttributeSchemaMutation(
						"code", null, null, AttributeUniquenessType.NOT_UNIQUE,
						true, true, false, true, false, String.class, null, 0)));
			}
		});
		if (recovery.equals("restart")) {
			this.evita.close();
			this.evita = new Evita(configuration(this.paths));
			this.evita.waitUntilFullyInitialized();
		} else if (recovery.equals("replay")) {
			recoverCrashCopy();
		}
		assertAll(
			() -> assertLookup("ABC", 1),
			() -> assertEquals("abc", owner().getAttributeNormalizationResolver().resolve(null,
				owner().getSchema().getAttribute("code").orElseThrow(), null).apply("ABC")),
			() -> assertEquals(!toGlobal, owner().getNormalizationPolicy().globalAttributes().contains("code"))
		);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntity("Owner", 1, attributeContentAll()).orElseThrow().openForWrite()
				.setAttribute("code", "UPDATED").upsertVia(session);
			session.getEntity("Owner", 2, attributeContentAll()).orElseThrow().openForWrite()
				.removeAttribute("code").upsertVia(session);
		});
		assertLookup("ABC");
		assertLookup("ABC2");
		assertLookup("UPDATED", 1);
		assertLookup("ABC3", 3);
		if (toGlobal) {
			// A global declaration must not be pinned by this collection's still-local footprint.
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("code", asciiFolding()));
			});
			assertEquals(List.of(asciiFolding()), catalog().getGlobalAttributeNormalizationRows().get("code"));
			assertLookup("ABC3", 3);
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("code"));
			});
		}
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			for (final int id : new int[]{1, 3}) {
				session.getEntity("Owner", id, attributeContentAll()).orElseThrow().openForWrite()
					.removeAttribute("code").upsertVia(session);
			}
		});
		assertEquals(!toGlobal, owner().getNormalizationPolicy().globalAttributes().contains("code"),
			"Writes alone never switch ownership");
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateEntitySchema(new ModifyEntitySchemaMutation("Owner",
				new SetAttributeSchemaNormalizedMutation("code")));
			session.createNewEntity("Owner", 4).setAttribute("code", "ABC").upsertVia(session);
		});
		assertEquals(toGlobal, owner().getNormalizationPolicy().globalAttributes().contains("code"));
		assertLookup("ABC", 4);
		assertLookup("abc");
		assertEquals("ABC", owner().getAttributeNormalizationResolver().resolve(null,
			owner().getSchema().getAttribute("code").orElseThrow(), null).apply("ABC"));
		assertEquals(toGlobal ? "ABC" : "abc", catalog().getAttributeNormalizationResolver().resolve(null,
			catalog().getSchema().getAttribute("code").orElseThrow(), null).apply("ABC"));
	}

	/** Copies a bootstrap behind the visible schema version and opens it through production recovery. */
	private void recoverCrashCopy() throws IOException {
		final long version = catalog().getVersion();
		assertTrue(version > catalog().getPersistenceService().getLastCatalogVersion());
		this.replayPaths = createTestPaths("BuiltWithNormalizationOwnershipReplay");
		try (final Stream<Path> files = Files.walk(this.paths.storage())) {
			for (final Path source : files.toList()) {
				final Path destination = this.replayPaths.storage().resolve(this.paths.storage().relativize(source));
				if (Files.isDirectory(source)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		this.evita.close();
		this.evita = new Evita(configuration(this.replayPaths));
		this.evita.waitUntilFullyInitialized();
		assertEquals(version, catalog().getVersion());
	}

	/**
	 * Keeps checkpoints behind the live mutation so the crash copy must replay its ownership change.
	 *
	 * @param testPaths storage and export locations of the engine being (re)started
	 * @return configuration of the test engine with the periodic checkpoint effectively disabled
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths testPaths) {
		final EvitaConfiguration base = newTestEvitaConfigurationBuilder(testPaths).build();
		return EvitaConfiguration.builder(base).transaction(TransactionOptions.builder(base.transaction())
			.checkpointIntervalInMillis(3_600_000L).build()).build();
	}

	/**
	 * Probes raw values through the public filter path, including absent keys after updates and removals.
	 *
	 * @param value raw value of the `code` attribute to look up
	 * @param ids primary keys of the `Owner` entities expected to match, in result order; none when the value is absent
	 */
	private void assertLookup(@Nonnull String value, @Nonnull Integer... ids) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals(List.of(ids), session.queryListOfEntityReferences(query(collection("Owner"),
				filterBy(attributeEquals("code", value)))).stream().map(it -> it.getPrimaryKey()).toList());
			return null;
		});
	}

	/**
	 * Returns the current catalog snapshot after live or recovered mutations.
	 *
	 * @return the current instance of the test catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/**
	 * Returns the collection whose identity is transferring ownership.
	 *
	 * @return the `Owner` collection of the current catalog instance
	 */
	@Nonnull
	private EntityCollection owner() {
		return catalog().getCollectionForEntityOrThrowException("Owner");
	}
}
