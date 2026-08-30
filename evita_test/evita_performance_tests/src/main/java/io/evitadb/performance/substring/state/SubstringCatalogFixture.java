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

package io.evitadb.performance.substring.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.trigram.TrigramCodec;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContains;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.performance.substring.state.SubstringCorpus.ATTRIBUTE_TITLE;
import static io.evitadb.performance.substring.state.SubstringCorpus.PRODUCT;

/**
 * One booted embedded evitaDB instance holding one arm of the substring A/B, together with everything the setup was
 * able to prove about it.
 *
 * # What the fixture guarantees before it hands anything out
 *
 * A benchmark whose fixture is silently wrong produces numbers that look exactly like a discovery, so every
 * assumption the matrix rests on is checked here and each failure aborts the trial rather than being logged:
 *
 * 1. **The arm took effect.** `TRIGRAM` must have a `TrigramIndex` on the attribute and `SCAN` must not. An arm that
 *    failed to configure itself would compare the scan against the scan and report a speedup of one.
 * 2. **The corpus is all-distinct.** The shared value tree's bucket count must equal the entity count, because both
 *    constants of `TrigramSubstringSearch` are expressed in distinct values.
 * 3. **Each pattern class lands on its intended posting width**, measured on the built index -
 *    see {@link SubstringPatternClass#verifyPostingWidth(int, int, int)}.
 * 4. **The query returns the corpus's own answer.** The end-to-end result is compared against
 *    {@link SubstringCorpus#expectedPrimaryKeysOf(SubstringPatternClass)}, an oracle derived from the generated values
 *    rather than from either execution path - so a disagreement between the accelerated path and the scan cannot pass
 *    as long as both are checked against it, and neither can a shared mistake.
 *
 * Whether the accelerated path was actually *taken* for a given class is recorded rather than asserted
 * ({@link PatternProfile#accelerated()}), because the cells below
 * {@link TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT} exist precisely to measure the decline.
 *
 * # Cost control
 *
 * Building a hundred-thousand-entity catalog costs tens of seconds and JMH instantiates a fresh `@State` per
 * parameter combination, so the booted instances live in a **static** holder keyed by arm, size and cache mode. Under
 * JMH's default forking every parameter combination gets its own JVM and the holder amortises only within it (which
 * is still worth having, since a benchmark class with several `@Benchmark` methods shares one fixture); under `-f 0`
 * the whole matrix runs in one JVM and the holder is what makes that affordable.
 *
 * Instances are closed by a JVM shutdown hook rather than by a `@TearDown`, because a fixture outlives the trial that
 * built it and no trial may close one another trial might still be using.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SubstringCatalogFixture {

	/**
	 * Name of the benchmarked catalog.
	 */
	public static final String CATALOG_NAME = "substringBenchmark";

	/**
	 * The booted instances of this JVM, keyed by what distinguishes them.
	 */
	private static final Map<FixtureKey, SubstringCatalogFixture> FIXTURES = new ConcurrentHashMap<>();

	/**
	 * Guards the one-time registration of the shutdown hook that closes {@link #FIXTURES}.
	 */
	private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();

	/**
	 * What this fixture is - arm, corpus size and cache mode.
	 */
	@Nonnull private final FixtureKey key;

	/**
	 * Storage root owned exclusively by this fixture, removed when it closes.
	 */
	@Nonnull private final Path storageDirectory;

	/**
	 * The embedded instance under measurement.
	 */
	@Nonnull private final Evita evita;

	/**
	 * The values that were inserted, and the oracle every parity check is made against.
	 */
	@Nonnull private final SubstringCorpus corpus;

	/**
	 * The shared value tree of the benchmarked attribute - the tree the trigram postings name value ids in.
	 */
	@Nonnull private final InvertedIndex sharedValueTree;

	/**
	 * The attribute's substring accelerator, `null` on the {@link SubstringIndexArm#SCAN} arm.
	 */
	@Nullable private final TrigramIndex trigramIndex;

	/**
	 * What the setup measured about each pattern class on this fixture.
	 */
	@Nonnull private final Map<SubstringPatternClass, PatternProfile> profiles;

	/**
	 * Adopts an already-built instance.
	 *
	 * @param key              what this fixture is
	 * @param storageDirectory the storage root to remove on close
	 * @param evita            the booted instance
	 * @param corpus           the inserted values
	 * @param sharedValueTree  the attribute's shared value tree
	 * @param trigramIndex     the attribute's accelerator, or `null` on the scan arm
	 * @param profiles         what the setup measured about each pattern class
	 */
	private SubstringCatalogFixture(
		@Nonnull FixtureKey key,
		@Nonnull Path storageDirectory,
		@Nonnull Evita evita,
		@Nonnull SubstringCorpus corpus,
		@Nonnull InvertedIndex sharedValueTree,
		@Nullable TrigramIndex trigramIndex,
		@Nonnull Map<SubstringPatternClass, PatternProfile> profiles
	) {
		this.key = key;
		this.storageDirectory = storageDirectory;
		this.evita = evita;
		this.corpus = corpus;
		this.sharedValueTree = sharedValueTree;
		this.trigramIndex = trigramIndex;
		this.profiles = profiles;
	}

	/**
	 * Returns the fixture for the requested combination, building and verifying it on first request in this JVM.
	 *
	 * @param arm         which schema the attribute is declared with
	 * @param entityCount how many entities - and distinct values - the catalog holds
	 * @param cacheMode   whether the instance runs the formula cache
	 * @return the shared fixture
	 */
	@Nonnull
	public static SubstringCatalogFixture obtain(
		@Nonnull SubstringIndexArm arm,
		int entityCount,
		@Nonnull SubstringCacheMode cacheMode
	) {
		registerShutdownHook();
		return FIXTURES.computeIfAbsent(
			new FixtureKey(arm, entityCount, cacheMode),
			SubstringCatalogFixture::build
		);
	}

	/**
	 * @return the embedded instance
	 */
	@Nonnull
	public Evita getEvita() {
		return this.evita;
	}

	/**
	 * @return the inserted values, and the oracle the parity checks were made against
	 */
	@Nonnull
	public SubstringCorpus getCorpus() {
		return this.corpus;
	}

	/**
	 * @return the shared value tree of the benchmarked attribute
	 */
	@Nonnull
	public InvertedIndex getSharedValueTree() {
		return this.sharedValueTree;
	}

	/**
	 * @return the attribute's substring accelerator
	 * @throws GenericEvitaInternalError when this fixture is the scan arm, which keeps none
	 */
	@Nonnull
	public TrigramIndex getTrigramIndexOrThrow() {
		if (this.trigramIndex == null) {
			throw new GenericEvitaInternalError(
				"The `" + this.key.arm() + "` arm keeps no trigram index - a benchmark that operates below the query "
					+ "API must be run on the `TRIGRAM` arm!",
				"The requested fixture has no trigram index!"
			);
		}
		return this.trigramIndex;
	}

	/**
	 * @param patternClass the class to describe
	 * @return what the setup measured about that class on this fixture
	 */
	@Nonnull
	public PatternProfile getProfile(@Nonnull SubstringPatternClass patternClass) {
		final PatternProfile profile = this.profiles.get(patternClass);
		if (profile == null) {
			throw new GenericEvitaInternalError(
				"`" + patternClass.name() + "` was not profiled by this fixture!",
				"The requested pattern class was not profiled!"
			);
		}
		return profile;
	}

	/**
	 * Builds the query the matrix measures: a plain `attributeContains` returning primary keys only.
	 *
	 * Assembled once and reused, because a measured invocation should pay for planning and execution rather than for
	 * rebuilding an identical constraint tree - which is also how a real client uses a prepared query.
	 *
	 * @param patternClass the class whose marker is searched for
	 * @param pageSize     how many records the page requests
	 * @return the query
	 */
	@Nonnull
	public static Query buildQuery(@Nonnull SubstringPatternClass patternClass, int pageSize) {
		return query(
			collection(PRODUCT),
			filterBy(attributeContains(ATTRIBUTE_TITLE, patternClass.getPattern())),
			require(page(1, pageSize))
		);
	}

	/**
	 * Boots one instance, fills it, and refuses to hand it back unless everything the matrix assumes about it holds.
	 *
	 * @param key what to build
	 * @return the verified fixture
	 */
	@Nonnull
	private static SubstringCatalogFixture build(@Nonnull FixtureKey key) {
		final long startedAt = System.currentTimeMillis();
		final SubstringCorpus corpus = new SubstringCorpus(key.entityCount());
		final Path storageDirectory;
		try {
			storageDirectory = Files.createTempDirectory("evita-substring-benchmark");
		} catch (IOException e) {
			throw new GenericEvitaInternalError(
				"The benchmark's storage directory cannot be created: " + e.getMessage(),
				"The benchmark's storage directory cannot be created!",
				e
			);
		}

		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(600_000)
						.transactionTimeoutInMilliseconds(600_000)
						.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(storageDirectory)
						.build()
				)
				.cache(key.cacheMode().toCacheOptions())
				.build()
		);
		evita.defineCatalog(CATALOG_NAME);
		evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute(
						ATTRIBUTE_TITLE, String.class,
						whichIs -> {
							if (key.arm() == SubstringIndexArm.TRIGRAM) {
								whichIs.filterable(FilterIndexCapability.SUBSTRING);
							} else {
								whichIs.filterable();
							}
						}
					)
					.updateVia(session);
				for (int i = 0; i < corpus.getEntityCount(); i++) {
					session.createNewEntity(PRODUCT, i + 1)
						.setAttribute(ATTRIBUTE_TITLE, corpus.getValue(i))
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);

		final GlobalEntityIndex globalIndex = resolveGlobalIndex(evita);
		final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(null, ATTRIBUTE_TITLE, null);
		final FilterIndex filterIndex = globalIndex.getFilterIndex(attributeIndexKey);
		if (filterIndex == null) {
			throw new GenericEvitaInternalError(
				"The `" + ATTRIBUTE_TITLE + "` attribute has no filter index - the schema was not applied!",
				"The benchmarked attribute has no filter index!"
			);
		}
		final InvertedIndex sharedValueTree = filterIndex.getInvertedIndex();
		final TrigramIndex trigramIndex = globalIndex.getTrigramIndex(attributeIndexKey);
		verifyArmTookEffect(key, trigramIndex);
		verifyCorpusIsDistinct(key, sharedValueTree);

		final Map<SubstringPatternClass, PatternProfile> profiles = profileEveryPatternClass(
			key, corpus, sharedValueTree, trigramIndex
		);
		verifyQueryAnswersTheOracle(evita, corpus);

		final SubstringCatalogFixture fixture = new SubstringCatalogFixture(
			key, storageDirectory, evita, corpus, sharedValueTree, trigramIndex, profiles
		);
		System.out.println(
			"[substring-fixture] built " + key + " in " + (System.currentTimeMillis() - startedAt) + " ms, "
				+ "distinctValues=" + sharedValueTree.getBucketCount()
				+ (trigramIndex == null ? ", trigrams=absent" : ", trigrams=" + trigramIndex.getTrigramCount())
		);
		for (final PatternProfile profile : profiles.values()) {
			System.out.println("[substring-fixture]   " + profile);
		}
		return fixture;
	}

	/**
	 * Reaches the live global entity index of the benchmarked collection.
	 *
	 * The route is the public one the functional suite uses - catalog, collection, global index - taken **after**
	 * `goLiveAndClose`, so the resolved index is the transactional one queries are served from. It stays valid for the
	 * whole run because the fixture performs no write after this point; a write would replace the index and leave this
	 * reference pointing at a superseded generation.
	 *
	 * @param evita the booted instance
	 * @return the collection's global entity index
	 */
	@Nonnull
	private static GlobalEntityIndex resolveGlobalIndex(@Nonnull Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(CATALOG_NAME);
		final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(PRODUCT);
		return collection.getGlobalIndex();
	}

	/**
	 * Refuses an arm that did not configure itself - a `TRIGRAM` arm without an accelerator, or a `SCAN` arm with one.
	 *
	 * @param key          what was built
	 * @param trigramIndex the accelerator that was found, or `null`
	 */
	private static void verifyArmTookEffect(@Nonnull FixtureKey key, @Nullable TrigramIndex trigramIndex) {
		if (key.arm() == SubstringIndexArm.TRIGRAM && trigramIndex == null) {
			throw new GenericEvitaInternalError(
				"The `TRIGRAM` arm declared `FilterIndexCapability.SUBSTRING` but the attribute has no trigram "
					+ "index - the arm would silently measure the scan and report a speedup of exactly one!",
				"The trigram arm has no trigram index!"
			);
		}
		if (key.arm() == SubstringIndexArm.SCAN && trigramIndex != null) {
			throw new GenericEvitaInternalError(
				"The `SCAN` arm declared no substring capability but the attribute HAS a trigram index - the "
					+ "baseline would be accelerated and every ratio measured against it would be meaningless!",
				"The scan arm has a trigram index!"
			);
		}
	}

	/**
	 * Refuses a corpus whose distinct value count is not its entity count.
	 *
	 * @param key             what was built
	 * @param sharedValueTree the attribute's shared value tree
	 */
	private static void verifyCorpusIsDistinct(@Nonnull FixtureKey key, @Nonnull InvertedIndex sharedValueTree) {
		final int bucketCount = sharedValueTree.getBucketCount();
		if (bucketCount != key.entityCount()) {
			throw new GenericEvitaInternalError(
				"The corpus of " + key.entityCount() + " entities produced " + bucketCount + " distinct values - "
					+ "both constants of `TrigramSubstringSearch` are expressed in DISTINCT values, so the matrix "
					+ "would be measured at a different point on the axis than it is labelled with!",
				"The benchmark corpus is not all-distinct!"
			);
		}
	}

	/**
	 * Measures each pattern class's real posting width on the built index and refuses any class that does not land
	 * where it claims.
	 *
	 * On the scan arm there is no index to measure against, so the widths are recorded as unknown and only the
	 * planting counts - which the corpus itself proves - carry over.
	 *
	 * @param key             what was built
	 * @param corpus          the inserted values
	 * @param sharedValueTree the attribute's shared value tree, whose normalizer the pattern goes through
	 * @param trigramIndex    the accelerator, or `null` on the scan arm
	 * @return what was measured, per class
	 */
	@Nonnull
	private static Map<SubstringPatternClass, PatternProfile> profileEveryPatternClass(
		@Nonnull FixtureKey key,
		@Nonnull SubstringCorpus corpus,
		@Nonnull InvertedIndex sharedValueTree,
		@Nullable TrigramIndex trigramIndex
	) {
		final Map<SubstringPatternClass, PatternProfile> profiles = new EnumMap<>(SubstringPatternClass.class);
		for (final SubstringPatternClass patternClass : SubstringPatternClass.values()) {
			final int expectedMatches = corpus.expectedPrimaryKeysOf(patternClass).length;
			if (trigramIndex == null) {
				profiles.put(
					patternClass,
					new PatternProfile(patternClass, expectedMatches, -1, -1, false)
				);
				continue;
			}
			// the pattern is normalized through the very normalizer that normalized every value the index was built
			// from - normalizing anywhere else is what would make the two sides disagree about which value holds what
			final String normalizedPattern = (String) sharedValueTree.getNormalizer().apply(patternClass.getPattern());
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(normalizedPattern);
			if (trigrams.length == 0) {
				throw new GenericEvitaInternalError(
					"`" + patternClass.name() + "` yields no trigram at all - its marker token is shorter than "
						+ TrigramCodec.MINIMAL_INDEXABLE_LENGTH + " code points and the accelerated path would "
						+ "decline it for a reason that has nothing to do with selectivity!",
					"A pattern class has no trigram!"
				);
			}
			int minimum = Integer.MAX_VALUE;
			int maximum = 0;
			for (final long trigram : trigrams) {
				final int cardinality = trigramIndex.cardinalityOf(trigram);
				minimum = Math.min(minimum, cardinality);
				maximum = Math.max(maximum, cardinality);
			}
			patternClass.verifyPostingWidth(minimum, maximum, key.entityCount());
			profiles.put(
				patternClass,
				new PatternProfile(
					patternClass, expectedMatches, minimum, maximum,
					// a trigram nobody posts against is answered before the gate is consulted at all, so a zero width
					// is an accelerated answer rather than a declined one
					minimum == 0 || TrigramSubstringSearch.isWorthAccelerating(minimum, key.entityCount())
				)
			);
		}
		return profiles;
	}

	/**
	 * Runs the measured query once per pattern class through the public API and insists it returns exactly the primary
	 * keys the corpus says it should.
	 *
	 * Done once here rather than per invocation: the point is to refuse to produce numbers at all if the two paths
	 * can disagree, not to pay for a comparison inside the measured operation.
	 *
	 * @param evita  the booted instance
	 * @param corpus the inserted values, and the oracle
	 */
	private static void verifyQueryAnswersTheOracle(@Nonnull Evita evita, @Nonnull SubstringCorpus corpus) {
		try (final EvitaSessionContract session = evita.createReadOnlySession(CATALOG_NAME)) {
			for (final SubstringPatternClass patternClass : SubstringPatternClass.values()) {
				final int[] expected = corpus.expectedPrimaryKeysOf(patternClass);
				final EvitaResponse<EntityReference> response = session.query(
					buildQuery(patternClass, Math.max(expected.length, 1)),
					EntityReference.class
				);
				final int[] actual = response.getPrimaryKeys();
				Arrays.sort(actual);
				if (response.getTotalRecordCount() != expected.length || !Arrays.equals(actual, expected)) {
					throw new GenericEvitaInternalError(
						"`attributeContains(" + ATTRIBUTE_TITLE + ", " + patternClass.getPattern() + ")` returned "
							+ response.getTotalRecordCount() + " entities where the corpus holds " + expected.length
							+ " - the execution disagrees with the values that were inserted, so no number this "
							+ "fixture produces can be trusted!",
						"The benchmarked query disagrees with the corpus!"
					);
				}
			}
		}
	}

	/**
	 * Registers, once per JVM, the hook that closes every fixture this JVM built.
	 */
	private static void registerShutdownHook() {
		if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
			Runtime.getRuntime().addShutdownHook(new Thread(SubstringCatalogFixture::closeAll, "substring-fixtures"));
		}
	}

	/**
	 * Closes every fixture and removes its storage. Failures are reported and swallowed - a shutdown hook that throws
	 * would hide whatever the run actually produced.
	 */
	private static void closeAll() {
		for (final SubstringCatalogFixture fixture : FIXTURES.values()) {
			try {
				fixture.evita.close();
			} catch (RuntimeException e) {
				System.err.println("[substring-fixture] " + fixture.key + " failed to close: " + e.getMessage());
			}
			try {
				FileUtils.deleteDirectory(fixture.storageDirectory.toFile());
			} catch (IOException e) {
				System.err.println(
					"[substring-fixture] " + fixture.key + " left " + fixture.storageDirectory + " behind: "
						+ e.getMessage()
				);
			}
		}
		FIXTURES.clear();
	}

	/**
	 * What distinguishes one booted instance from another.
	 *
	 * @param arm         which schema the benchmarked attribute is declared with
	 * @param entityCount how many entities - and distinct values - the catalog holds
	 * @param cacheMode   whether the instance runs the formula cache
	 */
	public record FixtureKey(
		@Nonnull SubstringIndexArm arm,
		int entityCount,
		@Nonnull SubstringCacheMode cacheMode
	) {

		@Override
		public String toString() {
			return this.arm + "/" + this.entityCount + "/cache-" + this.cacheMode;
		}

	}

	/**
	 * What the setup measured about one pattern class on one fixture - the record that lets a reader of the results
	 * tell which cell of the matrix a number belongs to.
	 *
	 * @param patternClass        the class described
	 * @param expectedMatchCount  how many entities the corpus says the pattern matches
	 * @param minimumPostingWidth the cheapest trigram's cardinality, which is what the selectivity gate reads, or `-1`
	 *                            on an arm that keeps no trigram index
	 * @param maximumPostingWidth the dearest trigram's cardinality, or `-1` on an arm that keeps no trigram index
	 * @param accelerated         whether `TrigramSubstringSearch#match` answers this class on this corpus instead of
	 *                            declining to the scan. `false` when the arm keeps no index and when the selectivity
	 *                            gate refuses; `true` for a zero posting width, which is answered from the cardinality
	 *                            probes before the gate is consulted at all
	 */
	public record PatternProfile(
		@Nonnull SubstringPatternClass patternClass,
		int expectedMatchCount,
		int minimumPostingWidth,
		int maximumPostingWidth,
		boolean accelerated
	) {

		@Override
		public String toString() {
			return this.patternClass + " matches=" + this.expectedMatchCount
				+ " postingWidth=" + this.minimumPostingWidth + ".." + this.maximumPostingWidth
				+ " accelerated=" + this.accelerated;
		}

	}

}
