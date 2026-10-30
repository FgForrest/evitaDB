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

package io.evitadb.performance.fulltext.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.analysis.AnalyzedTermConsumer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.relevance;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.textMatches;

/**
 * The catalog `TextMatchesQueryBenchmark` queries and the queries it asks - opened or built once per JVM, then sampled,
 * profiled and verified before any cell may use them.
 *
 * # Where the catalog comes from
 *
 * - **Stored**: an existing storage directory holding a catalog whose fields are already searchable. The phase-1
 *   measurement points it at a production catalog rebuilt with `searchable()` on the chosen fields - declaring the
 *   flag over stored data indexes nothing that is already there (#409), so the rebuild has to declare it. The instance
 *   runs read-only, with its work and export directories in a temporary folder, but opening a catalog still writes
 *   the engine's bootstrap file into the storage directory: point it at a rebuilt copy, never at a pristine backup.
 * - **Synthetic**: {@link SyntheticFulltextCorpus}, built in a temporary directory, for checking the harness on any
 *   machine. Its numbers measure nothing worth quoting.
 *
 * # Where the queries come from
 *
 * A query is one to {@link #MAX_TOKEN_COUNT} words that occur together in one searchable attribute of one entity, so
 * the strict match set is never empty. The entities are drawn with a constant seed, so every fork of a run samples
 * the same queries from the same catalog. Each word is classified into a {@link FulltextFrequencyClass} by the
 * documents it matches as a query token, and a query takes all its words from one class. Every combination of word
 * count and class gets a pool of up to {@link #POOL_SIZE} queries. Words come from attributes only - replicating the
 * text expressions associated data is extracted with is not worth it for sampling - but every query still searches the
 * associated data, because a `textMatches` without `inField` searches every searchable field.
 *
 * # What every query is checked for
 *
 * The fixture analyzes each query text the way `TextMatchesTranslator` does - the search analyzer while the schema
 * still assigns the analyzer the index was built with, the index's own otherwise - and walks the same posting lists to
 * compute the strict match set and the postings the scorer reads. Its match count must equal the `totalRecordCount`
 * the real query returns, or the fixture refuses to hand itself over: a mismatch means this replica of the translator
 * has drifted, and the posting counts printed next to the measurements would be fiction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FulltextCatalogFixture {

	/**
	 * How many queries each combination of word count and frequency class samples at most.
	 */
	public static final int POOL_SIZE = 32;
	/**
	 * The longest query the fixture samples, in words.
	 */
	public static final int MAX_TOKEN_COUNT = 3;
	/**
	 * How many entities the sampling reads at most before it settles for the pools it has filled.
	 */
	private static final int MAX_SAMPLED_ENTITIES = 50_000;
	/**
	 * Seed of the entity draw.
	 */
	private static final long SAMPLING_SEED = 42L;
	/**
	 * How many of the texts that miss their own entity the fixture summary prints.
	 */
	private static final int MAX_PRINTED_MISSES = 20;
	/**
	 * Every fixture this JVM opened or built, by what distinguishes it.
	 */
	private static final Map<FixtureKey, FulltextCatalogFixture> FIXTURES = new ConcurrentHashMap<>();
	/**
	 * Whether the hook closing {@link #FIXTURES} at JVM exit is registered.
	 */
	private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();

	/**
	 * What this fixture was obtained for.
	 */
	@Getter private final FixtureKey key;
	/**
	 * The temporary folder holding the work and export directories, and the whole catalog in synthetic mode.
	 */
	@Nonnull private final Path temporaryDirectory;
	/**
	 * The running instance.
	 */
	@Getter private final Evita evita;
	/**
	 * Name of the queried catalog.
	 */
	@Getter private final String catalogName;
	/**
	 * Name of the queried entity collection.
	 */
	@Getter private final String entityType;
	/**
	 * The locale every query searches in.
	 */
	@Getter private final Locale locale;
	/**
	 * The sampled queries, by word count and frequency class.
	 */
	@Nonnull private final Map<PoolKey, List<FulltextQueryProfile>> pools;

	/**
	 * Creates the fixture.
	 *
	 * @param key                what the fixture was obtained for
	 * @param temporaryDirectory the folder removed when the fixture closes
	 * @param evita              the running instance
	 * @param catalogName        name of the queried catalog
	 * @param entityType         name of the queried entity collection
	 * @param locale             the locale every query searches in
	 * @param pools              the verified queries, by word count and frequency class
	 */
	private FulltextCatalogFixture(
		@Nonnull FixtureKey key,
		@Nonnull Path temporaryDirectory,
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Locale locale,
		@Nonnull Map<PoolKey, List<FulltextQueryProfile>> pools
	) {
		this.key = key;
		this.temporaryDirectory = temporaryDirectory;
		this.evita = evita;
		this.catalogName = catalogName;
		this.entityType = entityType;
		this.locale = locale;
		this.pools = pools;
	}

	/**
	 * Returns the fixture for the key, opening or building it on the first request in this JVM.
	 *
	 * @param key what the fixture is for
	 * @return the verified fixture
	 */
	@Nonnull
	public static FulltextCatalogFixture obtain(@Nonnull FixtureKey key) {
		registerShutdownHook();
		return FIXTURES.computeIfAbsent(key, FulltextCatalogFixture::build);
	}

	/**
	 * Returns the queries of one cell.
	 *
	 * @param tokenCount     how many words the queries have
	 * @param frequencyClass which class their words are drawn from
	 * @return the queries, never empty
	 * @throws GenericEvitaInternalError when the sampling found no such query in this catalog
	 */
	@Nonnull
	public List<FulltextQueryProfile> getPool(int tokenCount, @Nonnull FulltextFrequencyClass frequencyClass) {
		final List<FulltextQueryProfile> pool = this.pools.get(new PoolKey(tokenCount, frequencyClass));
		if (pool == null || pool.isEmpty()) {
			throw new GenericEvitaInternalError(
				"The sampling found no query of " + tokenCount + " " + frequencyClass + " words in `"
					+ this.catalogName + "` within " + MAX_SAMPLED_ENTITIES
					+ " entities - the cell cannot be measured!",
				"The sampling found no query for the cell!"
			);
		}
		return pool;
	}

	/**
	 * Builds the query a benchmark invocation sends.
	 *
	 * @param text     the query text
	 * @param ranked   whether the result is ordered by `relevance()`, which is what runs the phase-1 scorer
	 * @param pageSize how many entity references the response materializes
	 * @return the query
	 */
	@Nonnull
	public Query buildQuery(@Nonnull String text, boolean ranked, int pageSize) {
		return ranked ?
			query(
				collection(this.entityType),
				filterBy(entityLocaleEquals(this.locale), textMatches(text)),
				orderBy(relevance()),
				require(page(1, pageSize))
			) :
			query(
				collection(this.entityType),
				filterBy(entityLocaleEquals(this.locale), textMatches(text)),
				require(page(1, pageSize))
			);
	}

	/**
	 * Opens or builds the instance, samples the queries and verifies every one of them.
	 *
	 * @param key what the fixture is for
	 * @return the verified fixture
	 */
	@Nonnull
	private static FulltextCatalogFixture build(@Nonnull FixtureKey key) {
		final long startedAt = System.currentTimeMillis();
		final Path temporaryDirectory = createTemporaryDirectory();
		final Evita evita;
		final String catalogName;
		final String entityType;
		final Locale locale;
		if (key.isSynthetic()) {
			evita = new Evita(createConfiguration(temporaryDirectory.resolve("data"), temporaryDirectory, false));
			new SyntheticFulltextCorpus().populate(evita);
			catalogName = SyntheticFulltextCorpus.CATALOG_NAME;
			entityType = SyntheticFulltextCorpus.ENTITY_TYPE;
			locale = SyntheticFulltextCorpus.LOCALE;
		} else {
			evita = new Evita(createConfiguration(Path.of(key.storageDirectory()), temporaryDirectory, true));
			catalogName = key.catalogName();
			entityType = key.entityType();
			locale = Locale.forLanguageTag(key.locale());
			// a stored catalog loads on a background thread - until it is alive the instance is a placeholder
			EvitaCatalogReusableSetup.awaitCatalogAlive(evita, catalogName);
		}
		final long openedAt = System.currentTimeMillis();

		try {
			final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(catalogName);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			final GlobalEntityIndex globalIndex = collection.getGlobalIndex();
			final FulltextIndex fulltextIndex = globalIndex.getFulltextIndex(locale);
			if (fulltextIndex == null) {
				throw new GenericEvitaInternalError(
					"Collection `" + entityType + "` of `" + catalogName + "` keeps no fulltext index for locale `"
						+ locale + "`. Its fields are not searchable, or were declared searchable over stored data, "
						+ "which indexes nothing (#409) - rebuild the catalog with the fields searchable.",
					"The benchmarked collection keeps no fulltext index for the locale!"
				);
			}
			final QueryReplica replica = new QueryReplica(
				catalog.getFulltextAnalyzerRegistry(), collection.getSchema(), fulltextIndex, locale,
				globalIndex.getAllPrimaryKeys().getLast() + 1
			);
			final Map<PoolKey, List<FulltextQueryProfile>> pools;
			final int sampledEntities;
			final Collection<String> missedTexts;
			try (final EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
				final Sampler sampler = new Sampler(
					session, collection.getSchema(), locale, replica, globalIndex.getAllPrimaryKeys().getArray()
				);
				sampledEntities = sampler.sample();
				pools = sampler.getPools();
				missedTexts = sampler.getMissedTexts();
				verifyAgainstRealQueries(session, pools, entityType, locale);
			}

			final FulltextCatalogFixture fixture = new FulltextCatalogFixture(
				key, temporaryDirectory, evita, catalogName, entityType, locale, pools
			);
			System.out.println(
				"[fulltext-fixture] " + key + ": opened in " + (openedAt - startedAt) + " ms, sampled and verified in "
					+ (System.currentTimeMillis() - openedAt) + " ms; catalog=" + catalogName + " entityType="
					+ entityType + " locale=" + locale + " documents=" + globalIndex.getAllPrimaryKeys().size()
					+ " searchedFields=" + Arrays.toString(replica.getFieldKeys()) + " queryAnalyzer="
					+ replica.getAnalyzerName() + " sampledEntities=" + sampledEntities + " missedTexts="
					+ missedTexts.size()
			);
			int printedMisses = 0;
			for (final String missedText : missedTexts) {
				if (printedMisses++ == MAX_PRINTED_MISSES) {
					break;
				}
				System.out.println("[fulltext-fixture]   missed (pk / attribute: text): " + missedText);
			}
			for (final Map.Entry<PoolKey, List<FulltextQueryProfile>> entry : pools.entrySet()) {
				System.out.println(
					"[fulltext-fixture]   " + entry.getKey() + ": " + entry.getValue().size() + " queries"
				);
			}
			return fixture;
		} catch (RuntimeException e) {
			evita.close();
			deleteQuietly(temporaryDirectory);
			throw e;
		}
	}

	/**
	 * Sends every sampled query through the real planner, plain and ranked, and refuses the fixture when the match
	 * count the replica computed differs from the one the engine returns.
	 *
	 * @param session    the session the queries run in
	 * @param pools      the sampled queries
	 * @param entityType name of the queried entity collection
	 * @param locale     the locale every query searches in
	 * @throws GenericEvitaInternalError when a count differs, or a ranked query returns no entity
	 */
	private static void verifyAgainstRealQueries(
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<PoolKey, List<FulltextQueryProfile>> pools,
		@Nonnull String entityType,
		@Nonnull Locale locale
	) {
		for (final List<FulltextQueryProfile> pool : pools.values()) {
			for (final FulltextQueryProfile profile : pool) {
				final EvitaResponse<EntityReference> plain = session.query(
					query(
						collection(entityType),
						filterBy(entityLocaleEquals(locale), textMatches(profile.text())),
						require(page(1, 1))
					),
					EntityReference.class
				);
				final EvitaResponse<EntityReference> ranked = session.query(
					query(
						collection(entityType),
						filterBy(entityLocaleEquals(locale), textMatches(profile.text())),
						orderBy(relevance()),
						require(page(1, 1))
					),
					EntityReference.class
				);
				if (plain.getTotalRecordCount() != profile.matchedDocuments()
					|| ranked.getTotalRecordCount() != profile.matchedDocuments()
					|| ranked.getRecordData().isEmpty()) {
					throw new GenericEvitaInternalError(
						"The fixture's replica of the translator computed " + profile.matchedDocuments()
							+ " matches for \"" + profile.text() + "\", the engine returned "
							+ plain.getTotalRecordCount() + " (ranked " + ranked.getTotalRecordCount() + ", "
							+ ranked.getRecordData().size() + " on the first page) - the replica has drifted from "
							+ "`TextMatchesTranslator` and its posting counts cannot be trusted!",
						"The fixture's replica of the translator has drifted!"
					);
				}
			}
		}
	}

	/**
	 * Creates the configuration of the instance.
	 *
	 * @param storageDirectory   the directory holding the catalog
	 * @param temporaryDirectory the folder for the work and export directories
	 * @param readOnly           whether the instance refuses every write - true for a stored catalog
	 * @return the configuration
	 */
	@Nonnull
	private static EvitaConfiguration createConfiguration(
		@Nonnull Path storageDirectory,
		@Nonnull Path temporaryDirectory,
		boolean readOnly
	) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.queryTimeoutInMilliseconds(600_000)
					.transactionTimeoutInMilliseconds(600_000)
					.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
					.readOnly(readOnly)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(storageDirectory)
					.workDirectory(temporaryDirectory.resolve("work"))
					// a catalog written with compression has compressed records only an engine with compression
					// enabled can read; uncompressed records read unchanged
					.compress(true)
					.build()
			)
			// the formula cache would answer a repeated query without planning it; fulltext formulas are never
			// cached, but the formulas around them would be, and the benchmark asks every query many times
			.cache(CacheOptions.builder().enabled(false).build())
			// the export directory defaults to `./export` relative to the working directory and is locked
			// exclusively, so two benchmark JVMs started from one folder would refuse each other
			.export(
				new FileSystemExportOptions(
					true,
					ExportOptions.DEFAULT_SIZE_LIMIT_BYTES,
					ExportOptions.DEFAULT_HISTORY_EXPIRATION_SECONDS,
					temporaryDirectory.resolve("export")
				)
			)
			.build();
	}

	/**
	 * Creates the temporary folder of one fixture.
	 *
	 * @return the created folder
	 */
	@Nonnull
	private static Path createTemporaryDirectory() {
		try {
			return Files.createTempDirectory("evita-fulltext-benchmark");
		} catch (IOException e) {
			throw new GenericEvitaInternalError(
				"The benchmark's temporary directory cannot be created: " + e.getMessage(),
				"The benchmark's temporary directory cannot be created!",
				e
			);
		}
	}

	/**
	 * Removes a folder, reporting a failure instead of throwing it.
	 *
	 * @param directory the folder to remove
	 */
	private static void deleteQuietly(@Nonnull Path directory) {
		try {
			FileUtils.deleteDirectory(directory.toFile());
		} catch (IOException e) {
			System.err.println("[fulltext-fixture] left " + directory + " behind: " + e.getMessage());
		}
	}

	/**
	 * Registers, once per JVM, the hook that closes every fixture this JVM opened.
	 */
	private static void registerShutdownHook() {
		if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
			Runtime.getRuntime().addShutdownHook(new Thread(FulltextCatalogFixture::closeAll, "fulltext-fixtures"));
		}
	}

	/**
	 * Closes every fixture and removes its temporary folder. Failures are reported and swallowed - a shutdown hook that
	 * throws would hide whatever the run actually produced.
	 */
	private static void closeAll() {
		for (final FulltextCatalogFixture fixture : FIXTURES.values()) {
			try {
				fixture.evita.close();
			} catch (RuntimeException e) {
				System.err.println("[fulltext-fixture] " + fixture.key + " failed to close: " + e.getMessage());
			}
			deleteQuietly(fixture.temporaryDirectory);
		}
		FIXTURES.clear();
	}

	/**
	 * What distinguishes one opened instance from another.
	 *
	 * @param storageDirectory the directory holding a stored catalog, or an empty string for the synthetic one
	 * @param catalogName      name of the stored catalog; ignored for the synthetic one
	 * @param entityType       name of the queried collection of the stored catalog; ignored for the synthetic one
	 * @param locale           language tag of the searched locale of the stored catalog; ignored for the synthetic one
	 */
	public record FixtureKey(
		@Nonnull String storageDirectory,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull String locale
	) {

		/**
		 * Validates that a stored catalog is fully named.
		 */
		public FixtureKey {
			if (!storageDirectory.isBlank() && (catalogName.isBlank() || entityType.isBlank() || locale.isBlank())) {
				throw new GenericEvitaInternalError(
					"A stored catalog needs `catalogName`, `entityType` and `locale` next to `storageDirectory`!"
				);
			}
		}

		/**
		 * Returns whether the fixture is the generated catalog.
		 *
		 * @return true when no storage directory was given
		 */
		public boolean isSynthetic() {
			return this.storageDirectory.isBlank();
		}

		@Nonnull
		@Override
		public String toString() {
			return isSynthetic() ?
				"synthetic" :
				this.storageDirectory + "/" + this.catalogName + "/" + this.entityType + "/" + this.locale;
		}

	}

	/**
	 * One cell's pool of queries.
	 *
	 * @param tokenCount     how many words its queries have
	 * @param frequencyClass which class their words are drawn from
	 */
	private record PoolKey(int tokenCount, @Nonnull FulltextFrequencyClass frequencyClass) {

		@Nonnull
		@Override
		public String toString() {
			return this.tokenCount + " x " + this.frequencyClass;
		}

	}

	/**
	 * One word of a text as the query analyzer produced it.
	 *
	 * @param surfaceForm the word as written
	 * @param terms       the distinct terms the analyzer emitted at its position, variant stems included
	 */
	private record Token(@Nonnull String surfaceForm, @Nonnull String[] terms) {
	}

	/**
	 * The fixture's replica of what `TextMatchesTranslator` does with a query text: which analyzer reads it, which
	 * fields it searches, and which posting lists its tokens resolve to.
	 */
	private static final class QueryReplica {
		/**
		 * The analyzer the translator reads the text with.
		 */
		@Nonnull private final FulltextAnalyzer analyzer;
		/**
		 * The searched fields that the index knows.
		 */
		@Nonnull private final FulltextFieldKey[] fieldKeys;
		/**
		 * Index ids of {@link #fieldKeys}, in the same order.
		 */
		@Nonnull private final int[] fieldIds;
		/**
		 * The fulltext index of the searched locale.
		 */
		@Nonnull private final FulltextIndex fulltextIndex;
		/**
		 * One more than the highest primary key of the collection - the size of a document set.
		 */
		private final int documentSetSize;
		/**
		 * How many documents each word matches, by surface form. Only the count is kept: a set per word would hold a
		 * bitmap per distinct word of the sampled text.
		 */
		@Nonnull private final Map<String, Integer> documentCounts = CollectionUtils.createHashMap(4_096);

		/**
		 * Resolves the analyzer and the searched fields as the translator does.
		 *
		 * @param analyzerRegistry the catalog's analyzer registry
		 * @param entitySchema     schema of the queried collection
		 * @param fulltextIndex    the fulltext index of the searched locale
		 * @param locale           the searched locale
		 * @param documentSetSize  one more than the highest primary key of the collection
		 */
		QueryReplica(
			@Nonnull FulltextAnalyzerRegistry analyzerRegistry,
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull FulltextIndex fulltextIndex,
			@Nonnull Locale locale,
			int documentSetSize
		) {
			final String entityType = entitySchema.getName();
			final String indexAnalyzerName = fulltextIndex.getAnalyzerName();
			// the search half reads the text only while the schema still assigns the analyzer the index was built
			// with; otherwise the index's own analyzer reads it - see `TextMatchesTranslator#resolveTokens`
			this.analyzer = analyzerRegistry.getIndexAnalyzer(entityType, locale).getAnalyzerName()
				.equals(indexAnalyzerName) ?
				analyzerRegistry.getSearchAnalyzer(entityType, locale) :
				analyzerRegistry.getIndexAnalyzerByName(indexAnalyzerName);
			final List<FulltextFieldKey> searchedFields = collectSearchableFields(entitySchema);
			final List<FulltextFieldKey> knownFields = new ArrayList<>(searchedFields.size());
			final int[] knownFieldIds = new int[searchedFields.size()];
			for (final FulltextFieldKey field : searchedFields) {
				// a field the index never saw, or retired, resolves to no id and contributes nothing
				final int fieldId = fulltextIndex.getFieldId(field);
				if (fieldId != FulltextIndex.UNKNOWN_FIELD_ID) {
					knownFieldIds[knownFields.size()] = fieldId;
					knownFields.add(field);
				}
			}
			this.fieldKeys = knownFields.toArray(FulltextFieldKey[]::new);
			this.fieldIds = Arrays.copyOf(knownFieldIds, knownFields.size());
			this.fulltextIndex = fulltextIndex;
			this.documentSetSize = documentSetSize;
		}

		/**
		 * Returns the searched fields that the index knows.
		 *
		 * @return the field keys
		 */
		@Nonnull
		FulltextFieldKey[] getFieldKeys() {
			return this.fieldKeys;
		}

		/**
		 * Returns the name of the analyzer the query text is read with.
		 *
		 * @return the analyzer name
		 */
		@Nonnull
		String getAnalyzerName() {
			return this.analyzer.getAnalyzerName();
		}

		/**
		 * Splits a text into tokens, grouping the terms the analyzer emits at one position, as the translator does.
		 *
		 * @param text the text
		 * @return the tokens in text order
		 */
		@Nonnull
		Token[] analyze(@Nonnull String text) {
			final TokenCollector collector = new TokenCollector();
			this.analyzer.analyze(text, collector);
			return collector.getTokens();
		}

		/**
		 * Returns how many documents a word matches as a query token, computing it on the first request.
		 *
		 * @param token the word as the analyzer produced it
		 * @return the number of documents holding any of its terms in any searched field
		 */
		int getDocumentCount(@Nonnull Token token) {
			final Integer known = this.documentCounts.get(token.surfaceForm());
			if (known != null) {
				return known;
			}
			final int documentCount = matchTokenDocuments(token, new long[1]).cardinality();
			this.documentCounts.put(token.surfaceForm(), documentCount);
			return documentCount;
		}

		/**
		 * Computes the profile of a query text as the planner will execute it.
		 *
		 * @param text           the query text
		 * @param frequencyClass the class its words were drawn from
		 * @return the profile, or `null` when a token of the text resolves to no document - the sampled words all
		 * resolve to some, so this means the joined text analyzes differently from the words it was joined from
		 */
		@Nullable
		FulltextQueryProfile profile(@Nonnull String text, @Nonnull FulltextFrequencyClass frequencyClass) {
			final Token[] tokens = analyze(text);
			if (tokens.length == 0) {
				return null;
			}
			final long[] summedPostings = new long[1];
			BitSet matched = null;
			for (final Token token : tokens) {
				final BitSet tokenDocuments = matchTokenDocuments(token, summedPostings);
				if (tokenDocuments.isEmpty()) {
					return null;
				}
				if (matched == null) {
					matched = tokenDocuments;
				} else {
					matched.and(tokenDocuments);
				}
			}
			return new FulltextQueryProfile(
				text, frequencyClass, tokens.length, summedPostings[0], Objects.requireNonNull(matched).cardinality()
			);
		}

		/**
		 * Computes the documents holding any term of the token in any searched field - the union the strict match
		 * intersects.
		 *
		 * @param token          the token
		 * @param summedPostings a one-element accumulator the length of every posting list read is added to
		 * @return the matching documents, by primary key
		 */
		@Nonnull
		private BitSet matchTokenDocuments(@Nonnull Token token, @Nonnull long[] summedPostings) {
			final BitSet documents = new BitSet(this.documentSetSize);
			for (final int fieldId : this.fieldIds) {
				for (final String term : token.terms()) {
					final Bitmap postings = this.fulltextIndex.getPostings(fieldId, term);
					summedPostings[0] += postings.size();
					for (final int primaryKey : postings.getArray()) {
						documents.set(primaryKey);
					}
				}
			}
			return documents;
		}

		/**
		 * Lists the fields a `textMatches` without `inField` searches in the live scope - see
		 * `TextMatchesTranslator#collectSearchableFields`.
		 *
		 * @param entitySchema schema of the queried collection
		 * @return the fields: entity attributes, reference attributes and associated data, in that order
		 */
		@Nonnull
		private static List<FulltextFieldKey> collectSearchableFields(@Nonnull EntitySchemaContract entitySchema) {
			final List<FulltextFieldKey> fields = new ArrayList<>(8);
			for (final AttributeSchemaContract attributeSchema : entitySchema.getAttributes().values()) {
				if (attributeSchema.isSearchableInScope(Scope.LIVE)) {
					fields.add(FulltextFieldKey.attribute(attributeSchema.getName()));
				}
			}
			for (final ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
				for (final AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
					if (attributeSchema.isSearchableInScope(Scope.LIVE)) {
						fields.add(
							FulltextFieldKey.referenceAttribute(referenceSchema.getName(), attributeSchema.getName())
						);
					}
				}
			}
			for (final AssociatedDataSchemaContract associatedDataSchema : entitySchema.getAssociatedData().values()) {
				if (associatedDataSchema.isSearchableInScope(Scope.LIVE)) {
					fields.add(FulltextFieldKey.associatedData(associatedDataSchema.getName()));
				}
			}
			return fields;
		}

	}

	/**
	 * Draws entities, reads the words of their searchable attributes and fills the pools.
	 */
	private static final class Sampler {
		/**
		 * The session the entities are read in.
		 */
		@Nonnull private final EvitaSessionContract session;
		/**
		 * Name of the queried entity collection.
		 */
		@Nonnull private final String entityType;
		/**
		 * The searched locale.
		 */
		@Nonnull private final Locale locale;
		/**
		 * The searchable attributes the words are read from.
		 */
		@Nonnull private final String[] attributeNames;
		/**
		 * The replica that classifies the words and profiles the queries.
		 */
		@Nonnull private final QueryReplica replica;
		/**
		 * Primary keys of every entity of the collection.
		 */
		@Nonnull private final int[] primaryKeys;
		/**
		 * The pools being filled, every combination present from the start.
		 */
		@Nonnull private final Map<PoolKey, List<FulltextQueryProfile>> pools;
		/**
		 * Texts already pooled, so no pool holds one query twice.
		 */
		@Nonnull private final Set<String> pooledTexts = CollectionUtils.createHashSet(
			POOL_SIZE * MAX_TOKEN_COUNT * FulltextFrequencyClass.values().length
		);
		/**
		 * Texts whose words, drawn from one value, match no entity together - `primary key / attribute: text`, in
		 * the order they were met. Kept out of the pools: a ranked query with no match measures nothing.
		 */
		@Nonnull private final Map<String, String> missedTexts = CollectionUtils.createLinkedHashMap(16);
		/**
		 * The entity and window draw.
		 */
		@Nonnull private final Random random = new Random(SAMPLING_SEED);

		/**
		 * Prepares the sampling.
		 *
		 * @param session      the session the entities are read in
		 * @param entitySchema schema of the queried collection
		 * @param locale       the searched locale
		 * @param replica      the replica that classifies the words and profiles the queries
		 * @param primaryKeys  primary keys of every entity of the collection
		 */
		Sampler(
			@Nonnull EvitaSessionContract session,
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull Locale locale,
			@Nonnull QueryReplica replica,
			@Nonnull int[] primaryKeys
		) {
			this.session = session;
			this.entityType = entitySchema.getName();
			this.locale = locale;
			this.attributeNames = entitySchema.getAttributes().values().stream()
				.filter(attribute -> attribute.isSearchableInScope(Scope.LIVE))
				.map(AttributeSchemaContract::getName)
				.toArray(String[]::new);
			if (this.attributeNames.length == 0) {
				throw new GenericEvitaInternalError(
					"Collection `" + this.entityType + "` has no searchable attribute to sample query words from!"
				);
			}
			this.replica = replica;
			this.primaryKeys = primaryKeys;
			this.pools = CollectionUtils.createLinkedHashMap(MAX_TOKEN_COUNT * FulltextFrequencyClass.values().length);
			for (int tokenCount = 1; tokenCount <= MAX_TOKEN_COUNT; tokenCount++) {
				for (final FulltextFrequencyClass frequencyClass : FulltextFrequencyClass.values()) {
					this.pools.put(new PoolKey(tokenCount, frequencyClass), new ArrayList<>(POOL_SIZE));
				}
			}
		}

		/**
		 * Draws entities until every pool is full or {@link #MAX_SAMPLED_ENTITIES} entities have been read.
		 *
		 * @return how many entities were read
		 */
		int sample() {
			int sampled = 0;
			while (sampled < MAX_SAMPLED_ENTITIES && !allPoolsFull()) {
				final int primaryKey = this.primaryKeys[this.random.nextInt(this.primaryKeys.length)];
				sampled++;
				final SealedEntity entity = this.session.getEntity(
					this.entityType, primaryKey, attributeContent(this.attributeNames), dataInLocales(this.locale)
				).orElse(null);
				if (entity == null) {
					continue;
				}
				for (final String attributeName : this.attributeNames) {
					final Serializable value = entity.getAttribute(attributeName, this.locale);
					if (value instanceof String text) {
						sampleText(text, primaryKey, attributeName);
					} else if (value instanceof String[] texts) {
						for (final String text : texts) {
							sampleText(text, primaryKey, attributeName);
						}
					}
				}
			}
			return sampled;
		}

		/**
		 * Returns the filled pools.
		 *
		 * @return the pools, by word count and frequency class
		 */
		@Nonnull
		Map<PoolKey, List<FulltextQueryProfile>> getPools() {
			return this.pools;
		}

		/**
		 * Returns the texts kept out of the pools because their words match no entity together.
		 *
		 * @return `primary key / attribute: text` of every such text, in the order they were met
		 */
		@Nonnull
		Collection<String> getMissedTexts() {
			return this.missedTexts.values();
		}

		/**
		 * Offers one query of every length and class to the pools from the words of one text.
		 *
		 * @param text          one value of a searchable attribute
		 * @param primaryKey    the entity the value belongs to, reported when its words miss it
		 * @param attributeName the attribute holding the value, reported when its words miss it
		 */
		private void sampleText(@Nonnull String text, int primaryKey, @Nonnull String attributeName) {
			final Map<FulltextFrequencyClass, List<String>> wordsByClass = new EnumMap<>(FulltextFrequencyClass.class);
			final Set<String> seen = CollectionUtils.createHashSet(64);
			for (final Token token : this.replica.analyze(text)) {
				if (seen.add(token.surfaceForm())) {
					final FulltextFrequencyClass frequencyClass = FulltextFrequencyClass.of(
						this.replica.getDocumentCount(token)
					);
					if (frequencyClass != null) {
						wordsByClass.computeIfAbsent(frequencyClass, fc -> new ArrayList<>(16))
							.add(token.surfaceForm());
					}
				}
			}
			for (final Map.Entry<FulltextFrequencyClass, List<String>> entry : wordsByClass.entrySet()) {
				final List<String> words = entry.getValue();
				for (int tokenCount = 1; tokenCount <= MAX_TOKEN_COUNT && tokenCount <= words.size(); tokenCount++) {
					final List<FulltextQueryProfile> pool = this.pools.get(new PoolKey(tokenCount, entry.getKey()));
					if (pool.size() < POOL_SIZE) {
						// a window of the class's words in text order - all from one value, so the strict match
						// set should hold at least this entity
						final int start = this.random.nextInt(words.size() - tokenCount + 1);
						final String queryText = String.join(" ", words.subList(start, start + tokenCount));
						if (!this.pooledTexts.contains(queryText) && !this.missedTexts.containsKey(queryText)) {
							final FulltextQueryProfile profile = this.replica.profile(queryText, entry.getKey());
							if (profile != null && profile.tokenCount() == tokenCount) {
								if (profile.matchedDocuments() == 0) {
									// the value's own entity does not hold the words together - an index-side
									// analysis that differs from the query side looks exactly like this, so the
									// text is reported rather than measured
									this.missedTexts.put(
										queryText, primaryKey + " / " + attributeName + ": " + queryText
									);
								} else {
									pool.add(profile);
									this.pooledTexts.add(queryText);
								}
							}
						}
					}
				}
			}
		}

		/**
		 * Returns whether every pool holds {@link #POOL_SIZE} queries.
		 *
		 * @return true when the sampling can stop
		 */
		private boolean allPoolsFull() {
			for (final List<FulltextQueryProfile> pool : this.pools.values()) {
				if (pool.size() < POOL_SIZE) {
					return false;
				}
			}
			return true;
		}

	}

	/**
	 * Groups the terms an analyzer emits into tokens - a term with a position increment of zero belongs to the token
	 * before it, which is how the search analyzer emits variant stems. A copy of
	 * `TextMatchesTranslator.TokenCollector`, which is private to the translator.
	 */
	private static final class TokenCollector implements AnalyzedTermConsumer {
		/**
		 * The finished tokens.
		 */
		private final List<Token> tokens = new ArrayList<>(8);
		/**
		 * The distinct terms of the token being collected.
		 */
		private final List<String> currentTerms = new ArrayList<>(4);
		/**
		 * The surface form of the token being collected, `null` before its first term.
		 */
		@Nullable private String currentSurfaceForm;

		@Override
		public void accept(
			@Nonnull String term,
			@Nonnull Supplier<String> surfaceForm,
			int startOffset,
			int endOffset,
			int positionIncrement
		) {
			if (positionIncrement > 0) {
				flush();
			}
			if (this.currentSurfaceForm == null) {
				this.currentSurfaceForm = surfaceForm.get();
			}
			if (!this.currentTerms.contains(term)) {
				this.currentTerms.add(term);
			}
		}

		/**
		 * Returns the collected tokens, finishing the one being collected.
		 *
		 * @return the tokens in text order
		 */
		@Nonnull
		Token[] getTokens() {
			flush();
			return this.tokens.toArray(Token[]::new);
		}

		/**
		 * Finishes the token being collected, if any.
		 */
		private void flush() {
			if (!this.currentTerms.isEmpty()) {
				this.tokens.add(
					new Token(Objects.requireNonNull(this.currentSurfaceForm), this.currentTerms.toArray(String[]::new))
				);
				this.currentTerms.clear();
			}
			this.currentSurfaceForm = null;
		}

	}

}
