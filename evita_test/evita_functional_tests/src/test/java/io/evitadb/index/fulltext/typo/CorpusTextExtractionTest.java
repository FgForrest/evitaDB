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

package io.evitadb.index.fulltext.typo;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.associatedDataContent;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Extracts the text of a real catalog to a flat file that {@link CorpusTypoMeasurementTest} reads. The catalog is
 * supplied externally and never committed, so every location is a system property and the test is skipped when
 * they are missing:
 *
 * - `fulltext.corpus.dataDir` — storage directory holding the catalog folder; open a copy, the engine may write
 * - `fulltext.corpus.catalog` — catalog name
 * - `fulltext.corpus.output` — the flat file to write
 * - `fulltext.corpus.entityType` — the collection, default `article`
 * - `fulltext.corpus.attributes` — localized text attributes, default `title,perex`
 * - `fulltext.corpus.associatedData` — text associated data, default `body`
 * - `fulltext.corpus.locale` — the locale of the attributes, default `cs`
 *
 * The output holds one line per non-empty field: `primaryKey TAB field TAB text`, the text HTML-stripped (the
 * markup is not what a search chain will see, P1 measurements part 5) and with tabs and line breaks replaced by
 * spaces.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
@DisplayName("Extraction of a real catalog's text for the typo measurements")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class CorpusTextExtractionTest {
	private static final int BATCH_SIZE = 1_000;

	@Test
	@DisplayName("Writes title, perex and stripped body of every entity to a flat file")
	void shouldExtractCorpusText() throws IOException {
		final String dataDir = System.getProperty("fulltext.corpus.dataDir");
		final String catalog = System.getProperty("fulltext.corpus.catalog");
		final String output = System.getProperty("fulltext.corpus.output");
		assumeTrue(
			dataDir != null && catalog != null && output != null,
			"set fulltext.corpus.dataDir, fulltext.corpus.catalog and fulltext.corpus.output to run"
		);
		final String entityType = System.getProperty("fulltext.corpus.entityType", "article");
		final String[] attributes = System.getProperty("fulltext.corpus.attributes", "title,perex").split(",");
		final String[] associatedData = System.getProperty("fulltext.corpus.associatedData", "body").split(",");
		final Locale locale = Locale.forLanguageTag(System.getProperty("fulltext.corpus.locale", "cs"));
		final Path storage = Path.of(dataDir);

		final long start = System.nanoTime();
		long entities = 0;
		long fields = 0;
		long characters = 0;
		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.server(
						ServerOptions.builder()
							.queryTimeoutInMilliseconds(3_600_000)
							.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
							.build()
					)
					.storage(StorageOptions.builder().storageDirectory(storage).compress(true).build())
					.cache(CacheOptions.builder().build())
					// the export folder is locked exclusively; keep it inside the copy, away from a running server
					.export(
						new FileSystemExportOptions(
							true,
							ExportOptions.DEFAULT_SIZE_LIMIT_BYTES,
							ExportOptions.DEFAULT_HISTORY_EXPIRATION_SECONDS,
							storage.resolve("export")
						)
					)
					.build()
			);
			final EvitaSessionContract session = awaitLoaded(evita, catalog).createReadOnlySession(catalog);
			final BufferedWriter writer = Files.newBufferedWriter(Path.of(output), StandardCharsets.UTF_8)
		) {
			System.out.printf(
				Locale.ROOT, "catalog opened in %d ms; schema of %s: %s%n",
				(System.nanoTime() - start) / 1_000_000L, entityType,
				session.getEntitySchemaOrThrowException(entityType)
			);
			final int total = session.queryEntityReference(
				Query.query(collection(entityType), require(page(1, 1)))
			).getTotalRecordCount();
			final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
				Query.query(collection(entityType), require(page(1, total)))
			);
			for (int offset = 0; offset < references.size(); offset += BATCH_SIZE) {
				final int limit = Math.min(BATCH_SIZE, references.size() - offset);
				final Integer[] batch = new Integer[limit];
				for (int i = 0; i < limit; i++) {
					batch[i] = references.get(offset + i).getPrimaryKey();
				}
				final List<SealedEntity> fetched = session.queryListOfSealedEntities(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(batch)),
						require(
							page(1, limit),
							entityFetch(
								attributeContent(attributes),
								associatedDataContent(associatedData),
								dataInLocales(locale)
							)
						)
					)
				);
				for (final SealedEntity entity : fetched) {
					entities++;
					final int pk = entity.getPrimaryKeyOrThrowException();
					for (final String attribute : attributes) {
						final Serializable value = entity.getAttributeSchema(attribute)
							.<Serializable>map(schema -> schema.isLocalized()
								? entity.getAttribute(attribute, locale) : entity.getAttribute(attribute))
							.orElse(null);
						characters += write(writer, pk, attribute, value);
						fields += value == null ? 0 : 1;
					}
					for (final String name : associatedData) {
						final Serializable value = entity.getAssociatedDataSchema(name)
							.<Serializable>map(schema -> schema.isLocalized()
								? entity.getAssociatedData(name, locale) : entity.getAssociatedData(name))
							.orElse(null);
						characters += write(writer, pk, name, value);
						fields += value == null ? 0 : 1;
					}
				}
				if ((offset / BATCH_SIZE) % 50 == 0) {
					System.out.printf(Locale.ROOT, "%,d / %,d entities%n", offset + limit, references.size());
				}
			}
		}
		System.out.printf(
			Locale.ROOT, "extracted %,d entities, %,d fields, %,d characters in %d s to %s%n",
			entities, fields, characters, (System.nanoTime() - start) / 1_000_000_000L, output
		);
		assertTrue(entities > 0, "the collection `" + entityType + "` is empty");
	}

	/**
	 * Waits until the catalog leaves its transitional state: the engine constructor returns while the catalog is
	 * still being activated in the background, and querying it then throws. There is no completion signal to wait
	 * on, so this polls, as `IsolatedWarmupLoadBenchmark#awaitCatalogAlive` does.
	 *
	 * @param evita   the engine
	 * @param catalog the catalog name
	 * @return the engine, for chaining
	 */
	@Nonnull
	private static Evita awaitLoaded(@Nonnull Evita evita, @Nonnull String catalog) {
		final long deadline = System.nanoTime() + 3_600_000_000_000L;
		while (evita.getCatalogState(catalog).orElseThrow().isTransitional()) {
			assertTrue(System.nanoTime() < deadline, "catalog `" + catalog + "` did not load within an hour");
			try {
				Thread.sleep(500L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the catalog to load.", e);
			}
		}
		return evita;
	}

	/**
	 * Writes one field as a line of the flat file.
	 *
	 * @param writer the flat file
	 * @param pk     primary key of the entity
	 * @param field  name of the field
	 * @param value  the value, skipped when null or blank
	 * @return number of characters written
	 * @throws IOException when the file cannot be written
	 */
	private static int write(
		@Nonnull BufferedWriter writer,
		int pk,
		@Nonnull String field,
		@Nullable Serializable value
	) throws IOException {
		if (value == null) {
			return 0;
		}
		final String text = strip(value.toString()).replaceAll("[\\t\\r\\n]+", " ").trim();
		if (text.isEmpty()) {
			return 0;
		}
		writer.write(Integer.toString(pk));
		writer.write('\t');
		writer.write(field);
		writer.write('\t');
		writer.write(text);
		writer.write('\n');
		return text.length();
	}

	/**
	 * Removes HTML markup, attributes included, and decodes entities.
	 *
	 * @param html the raw value
	 * @return the text a reader would see
	 */
	@Nonnull
	private static String strip(@Nonnull String html) {
		try (final Reader reader = new HTMLStripCharFilter(new StringReader(html))) {
			final StringBuilder text = new StringBuilder(html.length());
			final char[] buffer = new char[8_192];
			for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
				text.append(buffer, 0, read);
			}
			return text.toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
