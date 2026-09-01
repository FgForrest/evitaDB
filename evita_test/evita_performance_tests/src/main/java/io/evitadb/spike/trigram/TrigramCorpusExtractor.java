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

package io.evitadb.spike.trigram;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.exception.GenericEvitaInternalError;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * **A1 of the P8 trigram-substring-index plan, and the shared corpus extractor of the P1 fulltext line.** Boots an
 * embedded evitaDB against a catalog that already exists on disk and dumps every filter-indexed `String` attribute
 * value it holds as a TSV corpus, one line per *occurrence*:
 *
 * ```text
 * entityType TAB attributeName TAB locale TAB entityPrimaryKey TAB value
 * ```
 *
 * The corpus is the input of {@link TrigramCorpusStatistics} (A2) and, later, of the Stage-1 prototype benchmark.
 * Extracting it once and analyzing it many times is the point: booting a production-shaped catalog costs minutes,
 * and every re-analysis that has to repeat that boot is a re-analysis that does not get run.
 *
 * # What is extracted, and why that set
 *
 * Every attribute whose {@link AttributeSchemaContract#getPlainType()} is `String` and which carries a **filter
 * index** in some scope: `filterable`, or `unique` / `unique within locale`, because uniqueness implies
 * filterability (see `AttributeSchemaContract#isUniqueInScope`) and therefore a `FilterIndex` that
 * `attributeContains` could be accelerated against. A `sortable`-only attribute is excluded - a `SortIndex` is not
 * a filter index and no substring predicate is answered from it. This is deliberately wider than the literal
 * "filterable" wording of the plan, and it is the difference between extracting `code`, `ean` and `url` - which
 * the plan names as candidates and which are `unique`, not `filterable` - and silently missing them.
 *
 * The value written is the **raw** attribute value, never a normalized one. Normalization is the analyzer's
 * business: A2 has to measure several normalizations of the same corpus (NFD as stored today, and the
 * case-folded form issue #545 would store), and a corpus that had already been folded could not answer that.
 *
 * Array-typed attributes (`String[]`) contribute one line per element, because that is how the filter index sees
 * them - each element is an independently indexed value.
 *
 * # Selection and configuration
 *
 * Everything is driven by system properties so the same class serves P1 unchanged:
 *
 * | Property | Meaning |
 * |---|---|
 * | `evita.trigram.catalogName` | **required** - the catalog to read |
 * | `evita.trigram.dataDir` | **required** - storage directory containing a `<catalogName>/` subfolder |
 * | `evita.trigram.corpusFile` | **required** - TSV file to write |
 * | `evita.trigram.workDir` | working copy location; defaults to a fresh temp directory |
 * | `evita.trigram.copyData` | `false` opens `dataDir` in place instead of copying (default `true`) |
 * | `evita.trigram.compress` | storage compression of the snapshot (default `true`, see {@link #COMPRESS_PROPERTY}) |
 * | `evita.trigram.entityTypes` | comma-separated collection allow-list; default: every collection |
 * | `evita.trigram.attributes` | attribute allow-list, `name` or `entityType:name`; default: all of them |
 * | `evita.trigram.pageSize` | entities fetched per query round trip (default 1000) |
 *
 * The catalog is **copied** into the working directory before boot by default. Opening a snapshot in place is not
 * read-only in the sense that matters here: boot replays the write-ahead log and the storage layer may compact,
 * so the folder that was measured would no longer be the folder the next run measures. The copy costs disk and a
 * minute of wall time and buys a reproducible corpus; `evita.trigram.copyData=false` opts out for a snapshot that
 * is already disposable or too large to duplicate.
 *
 * Sessions are opened through `queryCatalog`, which is read-only, so nothing this class does writes to the
 * catalog through the API.
 *
 * # Running it
 *
 * ```shell
 * java -Xmx8g \
 *   --add-opens java.base/java.lang=ALL-UNNAMED \
 *   --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
 *   --add-opens java.base/java.math=ALL-UNNAMED \
 *   --add-opens java.base/java.util=ALL-UNNAMED \
 *   -Devita.trigram.catalogName=demo \
 *   -Devita.trigram.dataDir=/path/to/snapshot \
 *   -Devita.trigram.workDir=/path/to/work \
 *   -Devita.trigram.corpusFile=/path/to/demo-corpus.tsv \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.spike.trigram.TrigramCorpusExtractor
 * ```
 *
 * The `--add-opens` flags are the ones the module's shade manifest declares: Byte Buddy generates classes
 * reflectively while an Evita instance boots and fails without them.
 *
 * @author Claude (P8 trigram-substring-index spike), FG Forrest a.s. (c) 2026
 */
public class TrigramCorpusExtractor {

	/**
	 * System property naming the catalog to extract. The data directory must contain a subfolder with exactly
	 * this name.
	 */
	public static final String CATALOG_NAME_PROPERTY = "evita.trigram.catalogName";

	/**
	 * System property pointing at the storage directory that holds the `&lt;catalogName&gt;/` subfolder.
	 */
	public static final String DATA_DIR_PROPERTY = "evita.trigram.dataDir";

	/**
	 * System property naming the TSV file the corpus is written to.
	 */
	public static final String CORPUS_FILE_PROPERTY = "evita.trigram.corpusFile";

	/**
	 * System property overriding the working directory the catalog is copied into.
	 */
	public static final String WORK_DIR_PROPERTY = "evita.trigram.workDir";

	/**
	 * System property switching the pre-boot copy off, opening the data directory in place.
	 */
	public static final String COPY_DATA_PROPERTY = "evita.trigram.copyData";

	/**
	 * System property restricting extraction to a comma-separated list of entity types.
	 */
	public static final String ENTITY_TYPES_PROPERTY = "evita.trigram.entityTypes";

	/**
	 * System property restricting extraction to a comma-separated list of attributes, each either a bare
	 * attribute name (matching in every collection) or `entityType:attributeName`.
	 */
	public static final String ATTRIBUTES_PROPERTY = "evita.trigram.attributes";

	/**
	 * System property overriding how many entities are fetched per query round trip.
	 */
	public static final String PAGE_SIZE_PROPERTY = "evita.trigram.pageSize";

	/**
	 * System property matching {@link StorageOptions#compress()} to the snapshot being read.
	 *
	 * This defaults to `true`, unlike the engine itself. Compression is recorded per storage record, and a
	 * reader that has it switched off refuses a compressed record outright with
	 * `Record is compressed and ObservableInput has compression support disabled` - which surfaces as a
	 * *corrupted catalog*, not as a configuration complaint. A reader that has it switched on reads both
	 * forms, so `true` is the setting that opens the widest set of snapshots; the demo catalog is one that
	 * needs it.
	 */
	public static final String COMPRESS_PROPERTY = "evita.trigram.compress";

	/**
	 * Header line of the produced TSV, written so the file describes itself; readers skip a leading `#`.
	 */
	static final String CORPUS_HEADER = "#entityType\tattributeName\tlocale\tentityPrimaryKey\tvalue";

	/**
	 * How long the extractor waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * How often progress is reported while a collection is being walked.
	 */
	private static final int PROGRESS_LOG_INTERVAL = 50_000;

	/**
	 * Entry point. Any failure exits the JVM explicitly: a partially constructed Evita instance has already
	 * started non-daemon threads, so a failed boot would otherwise leave a hanging JVM behind that still holds
	 * the storage-folder locks and blocks every subsequent run against the same directories.
	 *
	 * @param args unused; configuration is taken from system properties
	 */
	public static void main(@Nonnull String[] args) {
		try {
			run();
		} catch (final Throwable e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Performs the extraction as described in the class JavaDoc.
	 */
	private static void run() throws IOException {
		final String catalogName = requiredProperty(CATALOG_NAME_PROPERTY);
		final Path dataDir = Path.of(requiredProperty(DATA_DIR_PROPERTY));
		final Path corpusFile = Path.of(requiredProperty(CORPUS_FILE_PROPERTY));
		final boolean copyData = Boolean.parseBoolean(System.getProperty(COPY_DATA_PROPERTY, "true"));
		final boolean compress = Boolean.parseBoolean(System.getProperty(COMPRESS_PROPERTY, "true"));
		final int pageSize = Integer.parseInt(System.getProperty(PAGE_SIZE_PROPERTY, "1000"));
		final Set<String> entityTypeFilter = parseListProperty(ENTITY_TYPES_PROPERTY);
		final Set<String> attributeFilter = parseListProperty(ATTRIBUTES_PROPERTY);

		final Path storageDir = copyData
			? copyCatalog(dataDir, catalogName)
			: dataDir;

		System.out.printf(
			"Trigram corpus extraction - catalog `%s` from `%s`%n", catalogName, storageDir
		);

		final long bootStart = System.nanoTime();
		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(
						StorageOptions.builder()
							.storageDirectory(storageDir)
							.compress(compress)
							.build()
					)
					.server(
						ServerOptions.builder()
							.queryTimeoutInMilliseconds(600_000)
							.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
							.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
							.build()
					)
					.build()
			)
		) {
			awaitLoaded(evita, catalogName);
			System.out.printf("Catalog booted in %,d ms.%n", (System.nanoTime() - bootStart) / 1_000_000);

			Files.createDirectories(corpusFile.toAbsolutePath().getParent());
			try (
				final BufferedWriter writer = Files.newBufferedWriter(
					corpusFile, StandardCharsets.UTF_8
				)
			) {
				writer.write(CORPUS_HEADER);
				writer.newLine();
				// the lambda is bound to an explicit Function first: `queryCatalog` is overloaded for both
				// Function and Consumer, and an inline lambda cannot pick between them
				final Function<EvitaSessionContract, Map<String, long[]>> extraction =
					session -> extractCatalog(session, entityTypeFilter, attributeFilter, pageSize, writer);
				final Map<String, long[]> perGroupCounts = evita.queryCatalog(catalogName, extraction);
				printSummary(perGroupCounts, corpusFile);
			}
		}
	}

	/* ===================================== extraction ============================================ */

	/**
	 * Walks every selected collection of the catalog and writes one TSV line per extracted value occurrence.
	 *
	 * @param session          read-only session over the booted catalog
	 * @param entityTypeFilter collections to visit; empty means every collection
	 * @param attributeFilter  attributes to extract; empty means every filter-indexed `String` attribute
	 * @param pageSize         entities per query round trip
	 * @param writer           destination of the TSV lines
	 * @return per `entityType/attributeName/locale` group, a two-slot array of `{occurrences, totalCodePoints}`
	 */
	@Nonnull
	private static Map<String, long[]> extractCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull Set<String> entityTypeFilter,
		@Nonnull Set<String> attributeFilter,
		int pageSize,
		@Nonnull BufferedWriter writer
	) {
		final Map<String, long[]> perGroupCounts = new TreeMap<>();
		final Set<String> entityTypes = new TreeSet<>(session.getAllEntityTypes());
		for (final String entityType : entityTypes) {
			if (!entityTypeFilter.isEmpty() && !entityTypeFilter.contains(entityType)) {
				continue;
			}
			final SealedEntitySchema entitySchema = session.getEntitySchemaOrThrowException(entityType);
			final Set<String> selectedAttributes = selectAttributes(entitySchema, attributeFilter);
			if (selectedAttributes.isEmpty()) {
				System.out.printf("  %-24s - no filter-indexed String attribute, skipped%n", entityType);
				continue;
			}
			System.out.printf(
				"  %-24s - extracting %s%n", entityType, String.join(", ", selectedAttributes)
			);
			extractCollection(session, entityType, selectedAttributes, pageSize, writer, perGroupCounts);
		}
		return perGroupCounts;
	}

	/**
	 * Pages through one collection, fetching all attributes in all locales, and writes the selected values.
	 *
	 * @param session            read-only session
	 * @param entityType         collection to walk
	 * @param selectedAttributes attribute names to extract
	 * @param pageSize           entities per query round trip
	 * @param writer             destination of the TSV lines
	 * @param perGroupCounts     accumulator of per-group occurrence and code-point counts
	 */
	private static void extractCollection(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Set<String> selectedAttributes,
		int pageSize,
		@Nonnull BufferedWriter writer,
		@Nonnull Map<String, long[]> perGroupCounts
	) {
		int pageNumber = 1;
		int fetched = 0;
		int total = Integer.MAX_VALUE;
		while (fetched < total) {
			final EvitaResponse<SealedEntity> response = session.query(
				Query.query(
					collection(entityType),
					require(
						entityFetch(attributeContentAll(), dataInLocalesAll()),
						page(pageNumber, pageSize)
					)
				),
				SealedEntity.class
			);
			total = response.getTotalRecordCount();
			final List<SealedEntity> entities = response.getRecordData();
			if (entities.isEmpty()) {
				break;
			}
			for (int i = 0; i < entities.size(); i++) {
				writeEntity(entities.get(i), entityType, selectedAttributes, writer, perGroupCounts);
			}
			fetched += entities.size();
			if (fetched % PROGRESS_LOG_INTERVAL < pageSize && fetched < total) {
				System.out.printf("      %,d / %,d entities%n", fetched, total);
			}
			pageNumber++;
		}
		System.out.printf("      %,d / %,d entities - done%n", fetched, total);
	}

	/**
	 * Writes every selected attribute value of one entity, expanding array attributes element by element.
	 *
	 * @param entity             the fetched entity
	 * @param entityType         collection the entity belongs to
	 * @param selectedAttributes attribute names to extract
	 * @param writer             destination of the TSV lines
	 * @param perGroupCounts     accumulator of per-group occurrence and code-point counts
	 */
	private static void writeEntity(
		@Nonnull SealedEntity entity,
		@Nonnull String entityType,
		@Nonnull Set<String> selectedAttributes,
		@Nonnull BufferedWriter writer,
		@Nonnull Map<String, long[]> perGroupCounts
	) {
		final Integer primaryKey = entity.getPrimaryKey();
		if (primaryKey == null) {
			throw new GenericEvitaInternalError(
				"Entity of type `" + entityType + "` was fetched without a primary key!",
				"Entity was fetched without a primary key!"
			);
		}
		for (final AttributeValue attributeValue : entity.getAttributeValues()) {
			if (attributeValue.dropped() || !selectedAttributes.contains(attributeValue.key().attributeName())) {
				continue;
			}
			final Serializable value = attributeValue.value();
			if (value == null) {
				continue;
			}
			final String attributeName = attributeValue.key().attributeName();
			final Locale locale = attributeValue.key().locale();
			if (value instanceof final String text) {
				writeLine(writer, entityType, attributeName, locale, primaryKey, text, perGroupCounts);
			} else if (value instanceof final String[] texts) {
				for (int i = 0; i < texts.length; i++) {
					if (texts[i] != null) {
						writeLine(writer, entityType, attributeName, locale, primaryKey, texts[i], perGroupCounts);
					}
				}
			} else {
				// the attribute was selected because its schema declares a String plain type, so anything else
				// arriving here means the schema and the stored value disagree - a defect, not a case to skip
				throw new GenericEvitaInternalError(
					"Attribute `" + entityType + "." + attributeName + "` is declared as String but carries `" +
						value.getClass().getName() + "`!",
					"Attribute declared as String carries a different type!"
				);
			}
		}
	}

	/**
	 * Writes one TSV line and folds its length into the per-group counters.
	 *
	 * @param writer         destination of the TSV line
	 * @param entityType     collection the value belongs to
	 * @param attributeName  attribute the value belongs to
	 * @param locale         locale of the value, `null` for a non-localized attribute
	 * @param primaryKey     primary key of the owning entity
	 * @param value          the raw, un-normalized value
	 * @param perGroupCounts accumulator of per-group occurrence and code-point counts
	 */
	private static void writeLine(
		@Nonnull BufferedWriter writer,
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		int primaryKey,
		@Nonnull String value,
		@Nonnull Map<String, long[]> perGroupCounts
	) {
		final String localeTag = locale == null ? "" : locale.toLanguageTag();
		try {
			writer.write(entityType);
			writer.write('\t');
			writer.write(attributeName);
			writer.write('\t');
			writer.write(localeTag);
			writer.write('\t');
			writer.write(Integer.toString(primaryKey));
			writer.write('\t');
			writer.write(escape(value));
			writer.newLine();
		} catch (final IOException e) {
			throw new UncheckedIOException("Failed to write the corpus line!", e);
		}
		final long[] counters = perGroupCounts.computeIfAbsent(
			entityType + '\t' + attributeName + '\t' + localeTag, key -> new long[2]
		);
		counters[0]++;
		counters[1] += TrigramCodec.codePointCount(value);
	}

	/* ===================================== selection ============================================= */

	/**
	 * Picks the attributes of one collection that carry a filter index and hold `String` values.
	 *
	 * @param entitySchema    schema of the collection
	 * @param attributeFilter caller-supplied allow-list; empty means "every qualifying attribute"
	 * @return names of the attributes to extract, sorted
	 */
	@Nonnull
	private static Set<String> selectAttributes(
		@Nonnull SealedEntitySchema entitySchema,
		@Nonnull Set<String> attributeFilter
	) {
		final Set<String> selected = new TreeSet<>();
		final Map<String, ? extends AttributeSchemaContract> attributes = entitySchema.getAttributes();
		for (final Map.Entry<String, ? extends AttributeSchemaContract> entry : attributes.entrySet()) {
			final AttributeSchemaContract attributeSchema = entry.getValue();
			if (!String.class.isAssignableFrom(attributeSchema.getPlainType())) {
				continue;
			}
			// uniqueness implies filterability, so a unique attribute has a filter index just like a filterable
			// one - and `code` / `ean` / `url`, the plan's own candidate attributes, are unique rather than
			// filterable in the demo schema
			final boolean filterIndexed = attributeSchema.isFilterableInAnyScope()
				|| attributeSchema.isUniqueInAnyScope()
				|| attributeSchema.isUniqueWithinLocaleInAnyScope();
			if (!filterIndexed) {
				continue;
			}
			if (!attributeFilter.isEmpty()
				&& !attributeFilter.contains(entry.getKey())
				&& !attributeFilter.contains(entitySchema.getName() + ':' + entry.getKey())) {
				continue;
			}
			selected.add(entry.getKey());
		}
		return selected;
	}

	/* ======================================= support ============================================= */

	/**
	 * Copies the named catalog out of the snapshot into a disposable working directory, so that boot-time WAL
	 * recovery and storage compaction cannot alter the snapshot every later run has to start from.
	 *
	 * Only the `&lt;catalogName&gt;/` subfolder of the working directory is deleted beforehand - never the
	 * working directory itself, which may be a location the caller keeps other things in.
	 *
	 * @param dataDir     snapshot directory holding the catalog
	 * @param catalogName catalog to copy
	 * @return the working directory to boot against
	 */
	@Nonnull
	private static Path copyCatalog(@Nonnull Path dataDir, @Nonnull String catalogName) throws IOException {
		final String workDirProperty = System.getProperty(WORK_DIR_PROPERTY);
		final Path workDir = workDirProperty == null || workDirProperty.isBlank()
			? Files.createTempDirectory("evita-trigram-corpus")
			: Path.of(workDirProperty);
		final Path source = dataDir.resolve(catalogName);
		if (!Files.isDirectory(source)) {
			throw new GenericEvitaInternalError(
				"Data directory `" + dataDir + "` holds no `" + catalogName + "/` subfolder!",
				"Data directory holds no subfolder for the requested catalog!"
			);
		}
		final Path target = workDir.resolve(catalogName);
		System.out.printf("Copying catalog `%s` to `%s`...%n", catalogName, target);
		final long copyStart = System.nanoTime();
		FileUtils.deleteDirectory(target.toFile());
		FileUtils.copyDirectory(source.toFile(), target.toFile());
		System.out.printf("Copy finished in %,d ms.%n", (System.nanoTime() - copyStart) / 1_000_000);
		return workDir;
	}

	/**
	 * Waits until the named catalog finishes loading. Catalogs load on background threads, so the first lookup
	 * after the constructor returns hands back an unusable placeholder rather than a loaded catalog.
	 *
	 * A catalog that failed to load is also an unusable placeholder, and waiting out the full timeout on one
	 * turns an instant, self-describing failure into a quarter-hour of silence - so the `CORRUPTED` state is
	 * checked for explicitly and reported with the exception that caused it.
	 *
	 * @param evita       the booted instance
	 * @param catalogName catalog to wait for
	 */
	private static void awaitLoaded(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long deadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
		while (System.nanoTime() < deadline) {
			final CatalogContract candidate = evita.getCatalogInstance(catalogName)
				.orElseThrow(() -> new IllegalArgumentException("Catalog `" + catalogName + "` not found!"));
			if (candidate instanceof Catalog) {
				return;
			}
			if (candidate instanceof final UnusableCatalog unusable
				&& unusable.getCatalogState() == CatalogState.CORRUPTED) {
				throw new IllegalStateException(
					"Catalog `" + catalogName + "` failed to load and is CORRUPTED - if the cause mentions " +
						"compression, the snapshot was written with `" + COMPRESS_PROPERTY + "` enabled.",
					unusable.getRepresentativeException()
				);
			}
			try {
				Thread.sleep(500L);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog `" + catalogName + "`!", e);
			}
		}
		throw new IllegalStateException(
			"Catalog `" + catalogName + "` did not become usable within the load timeout!"
		);
	}

	/**
	 * Escapes the TSV field separators so a value carrying a tab or a newline cannot corrupt the corpus.
	 * {@link TrigramCorpusStatistics#unescape(String)} is the exact inverse.
	 *
	 * @param value raw value
	 * @return the escaped value
	 */
	@Nonnull
	static String escape(@Nonnull String value) {
		if (value.indexOf('\\') < 0 && value.indexOf('\t') < 0
			&& value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		final StringBuilder escaped = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			final char character = value.charAt(i);
			switch (character) {
				case '\\' -> escaped.append("\\\\");
				case '\t' -> escaped.append("\\t");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				default -> escaped.append(character);
			}
		}
		return escaped.toString();
	}

	/**
	 * Reads a required system property.
	 *
	 * @param propertyName name of the property
	 * @return its value
	 */
	@Nonnull
	private static String requiredProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set - see " +
					TrigramCorpusExtractor.class.getSimpleName() + " JavaDoc for the full list.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Parses a comma-separated allow-list property into a set, treating an unset property as "no restriction".
	 *
	 * @param propertyName name of the property
	 * @return the parsed entries, empty when the property is unset
	 */
	@Nonnull
	private static Set<String> parseListProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		final Set<String> entries = new TreeSet<>();
		final String[] parts = value.split(",");
		for (int i = 0; i < parts.length; i++) {
			final String trimmed = parts[i].trim();
			if (!trimmed.isEmpty()) {
				entries.add(trimmed);
			}
		}
		return entries;
	}

	/**
	 * Prints what was extracted, so a run that produced a surprising corpus says so before the analyzer runs.
	 *
	 * @param perGroupCounts per-group occurrence and code-point counters
	 * @param corpusFile     the file that was written
	 */
	private static void printSummary(@Nonnull Map<String, long[]> perGroupCounts, @Nonnull Path corpusFile) {
		System.out.printf("%n=== EXTRACTED CORPUS: %s ===%n", corpusFile);
		System.out.printf(
			"%-24s %-24s %-8s %12s %12s%n", "entityType", "attribute", "locale", "values", "avg length"
		);
		final List<Map.Entry<String, long[]>> groups = new ArrayList<>(perGroupCounts.entrySet());
		groups.sort(Map.Entry.comparingByKey());
		long totalValues = 0L;
		for (int i = 0; i < groups.size(); i++) {
			final Map.Entry<String, long[]> group = groups.get(i);
			final String[] key = group.getKey().split("\t", -1);
			final long[] counters = group.getValue();
			totalValues += counters[0];
			System.out.printf(
				"%-24s %-24s %-8s %,12d %12.1f%n",
				key[0], key[1], key[2].isEmpty() ? "-" : key[2],
				counters[0], (double) counters[1] / counters[0]
			);
		}
		System.out.printf("%n%,d value occurrences in %,d groups.%n", totalValues, groups.size());
		System.out.println(
			"Run TrigramCorpusStatistics over this file next; `avg length` is in Unicode code points of the " +
				"RAW value (the analyzer re-measures it after normalization)."
		);
	}
}
