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

package io.evitadb.api.functional.schema;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.externalApi.FulltextMutationTestSupport.analyzerSamples;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real catalog persistence and WAL replay of sample-only declaration changes. */
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SCHEMA)
@Tag(WAL)
@DisplayName("Analyzer samples after catalog recovery")
class FulltextAnalyzerSamplesPersistenceTest implements EvitaTestSupport {
	/**
	 * Prevents a checkpoint from hiding the WAL mutations that the recovery must replay.
	 *
	 * @param paths isolated storage directories
	 * @return the engine configuration with a long checkpoint interval
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths paths) {
		final EvitaConfiguration base = newTestEvitaConfigurationBuilder(paths).build();
		return EvitaConfiguration.builder(base).transaction(TransactionOptions.builder(base.transaction())
			.checkpointIntervalInMillis(3_600_000L).build()).build();
	}

	/**
	 * Checks exact text and order, including empty samples after replacement.
	 *
	 * @param engine the recovered engine
	 */
	private static void assertSamples(@Nonnull Evita engine) {
		engine.queryCatalog(TEST_CATALOG, session -> {
			final CatalogSchemaContract schema = session.getCatalogSchema();
			assertEquals(analyzerSamples(), schema.getFulltextAnalyzer("created").orElseThrow().getSamples());
			assertEquals(analyzerSamples(), schema.getFulltextAnalyzer("modified").orElseThrow().getSamples());
			assertEquals(List.of(), schema.getFulltextAnalyzer("cleared").orElseThrow().getSamples());
			assertEquals(List.of(), schema.getFulltextAnalyzer("empty").orElseThrow().getSamples());
			return null;
		});
	}

	/**
	 * Replays committed create, sample-only modify and clear mutations from a copy with a proven checkpoint gap.
	 *
	 * @throws IOException when the storage snapshot cannot be copied
	 */
	@Test
	@DisplayName("keeps exact sample boundaries through WAL recovery and a normal restart")
	void shouldReplaySamplesAfterRestart() throws IOException {
		final TestPaths paths = createTestPaths("AnalyzerSamplesPersistence");
		final TestPaths recovery = createTestPaths("AnalyzerSamplesRecovery");
		try {
			try (final Evita engine = new Evita(configuration(paths))) {
				engine.defineCatalog(TEST_CATALOG);
				engine.updateCatalog(TEST_CATALOG, session -> { session.goLiveAndClose(); });
				engine.updateCatalog(TEST_CATALOG, session -> {
					session.getCatalogSchema().openForWrite()
						.withFulltextAnalyzer("created", analyzer -> analyzer.basedOn("text")
							.samples(analyzerSamples().toArray(String[]::new)))
						.withFulltextAnalyzer("modified", analyzer -> analyzer.basedOn("text").samples("first"))
						.withFulltextAnalyzer("cleared", analyzer -> analyzer.basedOn("text").samples("first"))
						.withFulltextAnalyzer("empty", analyzer -> analyzer.basedOn("text"))
						.updateVia(session);
				});
				engine.updateCatalog(TEST_CATALOG, session -> {
					session.getCatalogSchema().openForWrite()
						.withFulltextAnalyzer("modified", analyzer -> analyzer.basedOn("text")
							.samples(analyzerSamples().toArray(String[]::new)))
						.withFulltextAnalyzer("cleared", analyzer -> analyzer.basedOn("text").samples())
						.updateVia(session);
				});
				final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
				assertTrue(catalog.getVersion() > catalog.getPersistenceService().getLastCatalogVersion());
				try (final Stream<Path> files = Files.walk(paths.storage())) {
					for (final Path path : files.toList()) {
						final Path destination = recovery.storage().resolve(paths.storage().relativize(path));
						if (Files.isDirectory(path)) {
							Files.createDirectories(destination);
						} else {
							Files.createDirectories(destination.getParent());
							Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
						}
					}
				}
				try (final Evita recovered = new Evita(configuration(recovery))) {
					recovered.waitUntilFullyInitialized();
					assertSamples(recovered);
				}
			}
			try (final Evita reopened = new Evita(configuration(paths))) {
				reopened.waitUntilFullyInitialized();
				assertSamples(reopened);
			}
		} finally {
			cleanupTestPaths(paths);
			cleanupTestPaths(recovery);
		}
	}
}
