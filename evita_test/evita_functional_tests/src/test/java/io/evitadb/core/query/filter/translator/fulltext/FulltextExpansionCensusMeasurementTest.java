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

package io.evitadb.core.query.filter.translator.fulltext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.*;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.*;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.fulltext.*;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static io.evitadb.core.query.filter.translator.fulltext.FulltextSurfaceTestSupport.unitWeights;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionCensusSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in expansion census over a stored catalog. All properties start with `fulltext.census.`:
 * `dataDir`, `catalog`, `entityType`, `locale`, `output`, `nameAttribute` are required for catalog modes;
 * `seed` defaults to 20261105; `mode` is candidates (default), topk, compare or clicks;
 * `caps` defaults to 10,20,50,100,200; `netLimits` defaults to 130000,170000,200000,220000,240000,290000.
 * `headFile` optionally supplies UTF-8 phrase;count CSV; `headFinished=true` treats its last words as finished
 * (submitted searches) instead of unfinished (autocomplete input). The caller must pass a storage COPY: opening
 * the engine may write bootstrap/export state. Compare uses only `output`, `reference` and `candidate`, with
 * queries.tsv already in output. Topk also requires an existing queries.tsv; candidates creates it only when absent.
 * `cohorts` (comma-separated) restricts candidates and topk to those cohorts; queries.tsv is still written in full.
 * Clicks ranks known targets of real searches: it reads `clicksFile` (see {@link #clicks}) and optionally restricts
 * every result to `filterAttribute` = `filterValue`, the way the compared system hides entities; `depth` (default
 * 100) is how many ranked results are searched for the target.
 *
 * Run `-Dfulltext.census.smoke=true -Dfulltext.census.output=/tmp/census-smoke` with the cap property at 10000
 * to build and reopen a tiny synthetic catalog, exercise all modes and leave reviewable artifacts. Smoke rejects
 * external dataDir/catalog/headFile and reduces cohort sizes. Ordinary suite discovery skips this class.
 *
 * queries.tsv holds raw/clean text as Base64 UTF-8 to preserve tabs and newlines. Failure details, if any, also
 * stay in files in the output directory.
 * Engine loggers are silenced for this opt-in run; failures contain only query hashes and exception class names.
 * The census never prints catalog text, queries, exception messages or causes. Measurement is single-JVM,
 * indicative wall time, not JMH. The owner schedules all real-catalog runs.
 */
@Isolated
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
@DisplayName("Stored-catalog expansion census (opt-in)")
class FulltextExpansionCensusMeasurementTest implements EvitaTestSupport {
	private static final String PREFIX = "fulltext.census.";
	private static final int WIDE_SAMPLE = 2_000;

	/** The sole external entry point; no engine is opened for files-only comparison. */
	@Test
	@DisplayName("Writes deterministic queries, expansion aggregates or top twenty comparisons")
	void shouldMeasureExpansionCensus() throws Exception {
		final boolean smoke = Boolean.getBoolean(PREFIX + "smoke");
		final String mode = System.getProperty(PREFIX + "mode", "candidates");
		final String output = System.getProperty(PREFIX + "output");
		final boolean compareConfigured = mode.equals("compare") && System.getProperty(PREFIX + "reference") != null &&
			System.getProperty(PREFIX + "candidate") != null;
		assumeTrue(output != null && (smoke || compareConfigured || !mode.equals("compare") && configuredCatalog()),
			"Set fulltext.census.output and catalog inputs, or smoke=true, to run the census");
		assertTrue(Set.of("candidates", "topk", "compare", "clicks").contains(mode), "Unknown census mode");
		final Path directory = Path.of(output);
		Files.createDirectories(directory);
		try (final QuietLogs ignored = new QuietLogs()) {
			if (smoke) {
				runSmoke(directory);
			} else if (mode.equals("compare")) {
				FulltextExpansionCensusReport.compare(directory, Path.of(required("reference")),
					Path.of(required("candidate")));
			} else {
				try (final Evita evita = open(Path.of(required("dataDir")))) {
					awaitLoaded(evita, required("catalog"));
					runCatalog(evita, required("catalog"), required("entityType"),
						Locale.forLanguageTag(required("locale")), required("nameAttribute"), directory, mode, false);
				}
			}
		} catch (Exception e) {
			// Library errors can quote the input. Save the complete failure only to the protected output directory.
			try (final PrintWriter failure =
				new PrintWriter(Files.newBufferedWriter(directory.resolve("failure.txt")))) {
				e.printStackTrace(failure);
			}
			throw new IllegalStateException("Census failed (" + e.getClass().getSimpleName() +
				"); details are in the output directory");
		}
	}

	/** Explicit opt-in never invents a production path or catalog. */
	private static boolean configuredCatalog() {
		return Arrays.stream(new String[]{"dataDir", "catalog", "entityType", "locale", "nameAttribute"})
			.allMatch(key -> System.getProperty(PREFIX + key) != null);
	}

	/** Reads a required property without including its potentially identifying value in an error. */
	private static String required(String key) {
		final String value = System.getProperty(PREFIX + key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing fulltext.census." + key);
		}
		return value;
	}

	/** Opens a caller-owned storage copy; exports stay inside that same isolated copy. */
	private static Evita open(Path storage) {
		return new Evita(EvitaConfiguration.builder()
			.server(ServerOptions.builder().queryTimeoutInMilliseconds(3_600_000)
				.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE).build())
			.storage(StorageOptions.builder().storageDirectory(storage).compress(true).build())
			.cache(CacheOptions.builder().build())
			.export(new FileSystemExportOptions(true, ExportOptions.DEFAULT_SIZE_LIMIT_BYTES,
				ExportOptions.DEFAULT_HISTORY_EXPIRATION_SECONDS, storage.resolve("export")))
			.build());
	}

	/** Engine activation has no completion future; park between state reads with an hour deadline. */
	private static void awaitLoaded(Evita evita, String catalog) {
		final long deadline = System.nanoTime() + 3_600_000_000_000L;
		while (evita.getCatalogState(catalog).orElseThrow().isTransitional()) {
			if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
				throw new IllegalStateException("Catalog activation interrupted or timed out");
			}
			LockSupport.parkNanos(100_000_000L);
		}
	}

	/** Creates only synthetic data at test-owned paths, closes it, and exercises the stored-catalog entry points. */
	private void runSmoke(Path output) throws Exception {
		assertTrue(System.getProperty(PREFIX + "dataDir") == null, "Smoke rejects an external dataDir");
		assertTrue(System.getProperty(PREFIX + "catalog") == null, "Smoke rejects an external catalog");
		assertTrue(System.getProperty(PREFIX + "headFile") == null, "Smoke rejects an external head file");
		final TestPaths paths = createTestPaths("FulltextExpansionCensusSmoke");
		try {
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog("censusSmoke");
				evita.updateCatalog("censusSmoke", session -> {
					session.defineEntitySchema("Item").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
						.withAttribute("name", String.class, a -> a.localized().searchable())
						.withAssociatedData("body", String.class, a -> a.localized().searchable().analyzer("html-text"))
						.updateVia(session);
					for (int i = 1; i <= 12; i++) {
						session.createNewEntity("Item", i)
							.setAttribute("name", Locale.ENGLISH, "bright winter jackets")
							.setAssociatedData("body", Locale.ENGLISH,
								"<p>bright winter jackets waterproof clothing travelling item1234</p>")
							.upsertVia(session);
					}
				});
			}
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				awaitLoaded(evita, "censusSmoke");
				runCatalog(evita, "censusSmoke", "Item", Locale.ENGLISH, "name", output, "candidates", true);
				runCatalog(evita, "censusSmoke", "Item", Locale.ENGLISH, "name", output, "topk", true);
			}
			final Path topk = output.resolve("topk-" + resolvedCap() + ".tsv");
			FulltextExpansionCensusReport.compare(output, topk, topk);
			assertTrue(Files.size(output.resolve("census-rows.tsv")) > 0);
			assertTrue(Files.readString(output.resolve("topk-comparison.md")).contains("1.000000"));
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** Binds real field generations and analyzer groups exactly as query planning does. */
	private static void runCatalog(Evita evita, String catalogName, String entityType, Locale locale,
		String nameAttribute, Path output, String mode, boolean smoke) throws Exception {
		if (mode.equals("candidates")) {
			assertEquals(10_000, resolvedCap(), "Candidates requires -Devita.fulltext.termsPerLeg=10000");
		}
		try (final EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
			final Path queryFile = output.resolve("queries.tsv");
			if (mode.equals("topk")) {
				assertTrue(Files.exists(queryFile), "Run candidates first to save queries.tsv");
				final List<Query> saved = FulltextExpansionCensusReport.readQueries(queryFile);
				topk(session, entityType, locale, output, selected(saved));
				return;
			}
			if (mode.equals("clicks")) {
				clicks(session, entityType, locale, output, Path.of(required("clicksFile")));
				return;
			}
			final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(catalogName);
			final GlobalEntityIndex global = (GlobalEntityIndex) catalog
				.getCollectionForEntityOrThrowException(entityType)
				.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
			assertNotNull(global, "Collection has no global index");
			final FulltextIndex index = global.getFulltextIndex(locale);
			assertNotNull(index, "Collection has no fulltext index for this locale");
			final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
			final List<Group> groups = groups(catalog, schema, index, locale);
			assertTrue(groups.stream().anyMatch(group -> group.ids().length > 0),
				"No current searchable field generations");
			final FulltextTypoParameters policy = evita.getFulltextTypoParameters();
			final List<Query> queries;
			if (Files.exists(queryFile)) {
				queries = FulltextExpansionCensusReport.readQueries(queryFile);
			} else {
				final Random random = new Random(Long.getLong(PREFIX + "seed", 20261105L));
				final Corpus corpus = corpus(session, entityType, locale, nameAttribute, groups, random, smoke);
				queries = queries(corpus, groups, index, policy, random, smoke);
				FulltextExpansionCensusReport.writeQueries(queryFile, queries);
			}
			final int[] caps = Arrays.stream(System.getProperty(PREFIX + "caps", "10,20,50,100,200").split(","))
				.map(String::trim).mapToInt(Integer::parseInt).toArray();
			final long[] limits = Arrays.stream(System.getProperty(PREFIX + "netLimits",
				"130000,170000,200000,220000,240000,290000").split(","))
				.map(String::trim).filter(s -> !s.isEmpty()).mapToLong(Long::parseLong).toArray();
			assertTrue(Arrays.stream(caps).allMatch(c -> c >= 1 && c <= 10_000), "Caps must be in [1,10000]");
			assertTrue(Arrays.stream(limits).allMatch(l -> l >= 0), "Net limits must be nonnegative");
			try (final FulltextExpansionCensusReport report = new FulltextExpansionCensusReport(output, caps, limits)) {
				for (final Query query : selected(queries)) {
					report.accept(query, expand(query, groups, index, policy, true));
				}
			}
		}
	}

	/** Keeps the queries of the cohorts named by `cohorts`; every query when the property is unset. */
	private static List<Query> selected(List<Query> queries) {
		final String cohorts = System.getProperty(PREFIX + "cohorts");
		if (cohorts == null || cohorts.isBlank()) {
			return queries;
		}
		final Set<String> wanted = new HashSet<>(Arrays.stream(cohorts.split(",")).map(String::trim).toList());
		return queries.stream().filter(query -> wanted.contains(query.cohort())).toList();
	}

	/** Reads the actual initialized constant, rather than re-reading a property that might have changed. */
	static int resolvedCap() throws ReflectiveOperationException {
		final Field field = FulltextTermExpansion.class.getDeclaredField("TERMS_PER_LEG");
		field.setAccessible(true);
		return field.getInt(null);
	}

	/** The profile's schema fields and their current physical generations. */
	record Group(CompiledFulltextAnalyzer analyzer, List<FulltextFieldDefinition> fields, int[] ids) { }
	/** Bounded name-phrase reservoir and occurrence counts; raw text stays in this test's memory/files. */
	record Corpus(Map<String, Long> counts, List<String> names, long nameCount) { }

	/** Reuses the translator's schema field selection without adding a production entry point for measurement. */
	static List<Group> groups(Catalog catalog, EntitySchemaContract schema, FulltextIndex index, Locale locale)
		throws ReflectiveOperationException {
		final FulltextFieldDefinition[] fields = (FulltextFieldDefinition[]) invoke("collectSearchableFields",
			new Class<?>[]{EntitySchemaContract.class, Scope.class}, schema, Scope.LIVE);
		final Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> grouped =
			CollectionUtils.createLinkedHashMap(4);
		final Map<String, CompiledFulltextAnalyzer> resolved = CollectionUtils.createHashMap(4);
		for (final FulltextFieldDefinition field : fields) {
			final CompiledFulltextAnalyzer analyzer = resolved.computeIfAbsent(field.analyzer(),
				name -> catalog.getFulltextAnalyzerRegistry().getCompiledAnalyzer(catalog.getSchema(), name, locale));
			grouped.computeIfAbsent(analyzer, ignored -> new ArrayList<>(4)).add(field);
		}
		final List<Group> result = new ArrayList<>(grouped.size());
		for (final Map.Entry<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> entry : grouped.entrySet()) {
			final List<FulltextFieldDefinition> current = entry.getValue().stream().filter(f -> {
				final int id = index.getFieldId(f.key());
				return id != FulltextIndex.UNKNOWN_FIELD_ID && index.isFieldCurrent(id,
					entry.getKey().fingerprint().index(), f.lengthPivot());
			}).toList();
			final int[] ids = current.stream().mapToInt(f -> index.getFieldId(f.key())).toArray();
			// Schema profiles decide shared splitting even when they have no current field generations.
			result.add(new Group(entry.getKey(), current, ids));
		}
		return result;
	}

	/**
	 * Invokes a private production helper, retaining its exact semantics and failing loudly after a signature change.
	 */
	static Object invoke(String name, Class<?>[] types, Object... arguments) throws ReflectiveOperationException {
		final Method method = TextMatchesTranslator.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(null, arguments);
	}

	/** Uses the translator's shared splitting decision across heterogeneous profiles. */
	private static boolean commonText(List<Group> groups) {
		return groups.stream().allMatch(g -> g.analyzer().queryText().equals(groups.getFirst().analyzer().queryText()));
	}

	/** Applies a production word or surface chain to every alternative of a shared word. */
	private static String[] terms(FulltextAnalyzer chain, TextMatchesTranslator.QueryToken token) {
		final Set<String> terms = CollectionUtils.createLinkedHashSet(4);
		for (final String raw : token.terms()) {
			chain.analyze(raw, (term, surface, start, end, increment) -> {
				if (!term.isEmpty()) {
					terms.add(term);
				}
			});
		}
		return terms.toArray(String[]::new);
	}

	/** Runs real expansion and hashes the selected terms; provenance uses only the production stem walker. */
	static List<Word> expand(Query query, List<Group> groups, FulltextIndex index, FulltextTypoParameters policy,
		boolean provenance) throws ReflectiveOperationException {
		final boolean common = commonText(groups);
		final CompiledFulltextAnalyzer first = groups.getFirst().analyzer();
		final FulltextAnalyzer splitting = common ? first.textSplitting() : first.splitting();
		final TextMatchesTranslator.QueryToken[] tokens = TextMatchesTranslator.analyze(splitting, query.text());
		final TextMatchesTranslator.QueryToken[] clean = TextMatchesTranslator.analyze(splitting, query.clean());
		final boolean unfinished = tokens.length > 0 && (boolean) invoke("isUnfinished",
			new Class<?>[]{String.class}, query.text()) && tokens[tokens.length - 1].endOffset() ==
			Normalizer.normalize(query.text(), Normalizer.Form.NFC).length();
		final FulltextTermExpansion expansion = new FulltextTermExpansion(policy);
		final List<Pending> pending = new ArrayList<>(tokens.length * groups.size());
		for (int w = 0; w < tokens.length; w++) {
			for (int g = 0; g < groups.size(); g++) {
				final Group group = groups.get(g);
				if (group.ids().length == 0) {
					continue;
				}
				for (int part = 0; part < tokens[w].wordCount(); part++) {
					final CompiledFulltextAnalyzer analyzer = group.analyzer();
					final TextMatchesTranslator.QueryToken token = tokens[w].wordAt(part);
					final String[] variants = terms(common ? analyzer.textSplitWord() : analyzer.word(), token);
					final String[] surfaces = terms(common ? analyzer.textSplitSurface() : analyzer.surface(), token);
					final boolean last = unfinished && w == tokens.length - 1 &&
						(part == 0 || part == tokens[w].wordCount() - 1);
					final boolean offsets = last && (boolean) invoke("finalWordOffsetsMatch",
						new Class<?>[]{
							CompiledFulltextAnalyzer.class, TextMatchesTranslator.QueryToken.class, boolean.class
						},
						analyzer, token, common);
					final String[] fragments = offsets
						? FulltextQueryFragment.prepare(analyzer, token, common) : new String[0];
					final boolean dropped = variants.length == 0 || analyzer.isFoldedStopWord(token.surfaceForm()) &&
						!(boolean) invoke("isKnownToIndex", new Class<?>[]{FulltextIndex.class,
							TextMatchesTranslator.QueryToken.class}, index,
							new TextMatchesTranslator.QueryToken(token.surfaceForm(), variants));
					final long start = System.nanoTime();
					List<ExpandedTerm> expanded = dropped && !expansion.expandsUnfinishedWord(fragments, last)
						? List.of() : expansion.expand(index, group.ids(), unitWeights(index), variants, surfaces,
							fragments, last);
					final long nanos = System.nanoTime() - start;
					if (dropped && expanded.stream().noneMatch(t -> t.exactness() == FulltextTermExactness.PREFIX)) {
						expanded = List.of();
					}
					final Set<String> targets = CollectionUtils.createLinkedHashSet(4);
					if (w < clean.length) {
						final TextMatchesTranslator.QueryToken target =
							clean[w].wordAt(Math.min(part, clean[w].wordCount() - 1));
						for (final String term : terms(common ? analyzer.textSplitWord() : analyzer.word(), target)) {
							targets.add(hash(term));
						}
					}
					pending.add(new Pending(w, part, g, targets, expanded, nanos, variants, fragments, last));
				}
			}
		}
		// Reuse the translator's cross-profile split-word suppression, retaining original provenance per call.
		for (int w = 0; w < tokens.length; w++) {
			if (tokens[w].wordCount() > 1) {
				final List<List<ExpandedTerm>> parts = new ArrayList<>(tokens[w].wordCount());
				for (int part = 0; part < tokens[w].wordCount(); part++) {
					parts.add(new ArrayList<>(8));
				}
				for (final Pending item : pending) {
					if (item.word() == w) {
						parts.get(item.part()).addAll(item.expanded());
					}
				}
				invoke("dropTermsPartsMatchExactly", new Class<?>[]{List.class}, parts);
				for (int i = 0; i < pending.size(); i++) {
					final Pending item = pending.get(i);
					if (item.word() == w) {
						pending.set(i, new Pending(w, item.part(), item.group(), item.targets(),
							item.expanded().stream().filter(parts.get(item.part())::contains).toList(),
							item.nanos(), item.variants(), item.fragments(), item.last()));
					}
				}
			}
		}
		final List<Word> result = new ArrayList<>(pending.size());
		for (final Pending item : pending) {
			final Map<String, Hit> hits = CollectionUtils.createLinkedHashMap(item.expanded().size());
			final Map<FulltextTermExactness, Set<String>> reachable = provenance
				? stemReachable(
					index, groups.get(item.group()).ids(), item.variants(), item.fragments(), item.last(), policy)
				: Map.of();
			for (final ExpandedTerm term : item.expanded()) {
				final String hash = hash(term.term());
				final Hit previous = hits.get(hash);
				final Map<Integer, Long> fields = previous == null ? CollectionUtils.createLinkedHashMap(2)
					: previous.fields();
				fields.put(term.fieldId(), (long) term.postings().size());
				hits.put(hash, new Hit(hash, term.distance(), term.exactness(), fields, item.targets().contains(hash),
					provenance && reachable.get(term.exactness()).contains(term.term())));
			}
			result.add(new Word(item.word(), item.part(), item.group(), item.targets(),
				new ArrayList<>(hits.values()), item.nanos()));
		}
		return result;
	}

	/** Expansion call retained until the production cross-profile split-word suppression has run. */
	private record Pending(int word, int part, int group, Set<String> targets, List<ExpandedTerm> expanded,
		long nanos, String[] variants, String[] fragments, boolean last) { }

	/** Collects unmerged stem reachability by matching class, without implementing any lane merge or cut. */
	private static Map<FulltextTermExactness, Set<String>> stemReachable(FulltextIndex index, int[] fields,
		String[] variants, String[] fragments, boolean unfinished, FulltextTypoParameters policy) {
		final Map<FulltextTermExactness, Set<String>> result = new EnumMap<>(FulltextTermExactness.class);
		for (final FulltextTermExactness leg : FulltextTermExactness.values()) {
			result.put(leg, CollectionUtils.createHashSet(32));
		}
		final FulltextTermExpansion expansion = new FulltextTermExpansion(policy);
		final boolean prefix = expansion.expandsUnfinishedWord(fragments, unfinished);
		final boolean open = prefix && Arrays.stream(fragments)
			.anyMatch(f -> f.codePointCount(0, f.length()) >= policy.openEndedStartLength());
		for (final String variant : variants) {
			for (final int field : fields) {
				if (FulltextTermExpansion.lookUpTerm(index, field, variant) != null) {
					result.get(FulltextTermExactness.EXACT).add(variant);
				}
			}
			final int edits = policy.maxEdits(variant);
			if (edits > 0) {
				final FulltextDictionaryWalker walker = new FulltextDictionaryWalker(variant, edits,
					policy.nonFuzzyPrefix(), policy.transpositions(), open);
				for (final int field : fields) {
					index.forEachTerm(field, walker, (term, postings, impacts, distance, frequency) -> {
						if (distance > 0) {
							result.get(FulltextTermExactness.FUZZY).add(term);
						}
						return true;
					});
				}
			}
		}
		if (prefix) {
			final Set<String> prefixes = CollectionUtils.createHashSet(variants.length + fragments.length);
			prefixes.addAll(List.of(variants));
			prefixes.addAll(List.of(fragments));
			for (final String fragment : prefixes) {
				if (!fragment.isEmpty()) {
					for (final int field : fields) {
						index.forEachTerm(field, fragment, (term, postings, impacts) -> {
							result.get(FulltextTermExactness.PREFIX).add(term);
							return true;
						});
					}
				}
			}
		}
		return result;
	}

	/** Scans stored searchable texts in primary-key order, using each field's real query splitting chain. */
	private static Corpus corpus(EvitaSessionContract session, String type, Locale locale, String nameAttribute,
		List<Group> groups, Random random, boolean smoke) {
		final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(type);
		final AttributeSchemaContract name = schema.getAttribute(nameAttribute).orElseThrow();
		final CompiledFulltextAnalyzer nameAnalyzer = groups.stream()
			.filter(g -> g.fields().stream().anyMatch(f -> f.key().equals(FulltextFieldKey.attribute(nameAttribute))))
			.findFirst().orElse(groups.getFirst()).analyzer();
		final Map<String, Long> counts = CollectionUtils.createHashMap(65_536);
		final List<String> names = new ArrayList<>(smoke ? 3 : 2_000);
		long nameCount = 0;
		final int total = session.queryEntityReference(query(collection(type),
			filterBy(entityLocaleEquals(locale)), require(page(1, 1)))).getTotalRecordCount();
		assertTrue(total > 0, "Collection is empty for the requested locale");
		final List<EntityReferenceContract> references = session.queryListOfEntityReferences(query(collection(type),
			filterBy(entityLocaleEquals(locale)), require(page(1, total))));
		for (int offset = 0; offset < references.size(); offset += 500) {
			final Integer[] batch = references.subList(offset, Math.min(offset + 500, references.size())).stream()
				.map(EntityReferenceContract::getPrimaryKey).toArray(Integer[]::new);
			final List<SealedEntity> entities = session.queryListOfSealedEntities(query(collection(type),
				filterBy(entityPrimaryKeyInSet(batch)), require(page(1, batch.length), entityFetch(
					attributeContentAll(), associatedDataContentAll(), referenceContentAllWithAttributes(),
					dataInLocales(locale)))));
			for (final SealedEntity entity : entities) {
				for (final Group group : groups) {
					for (final FulltextFieldDefinition definition : group.fields()) {
						final FulltextFieldKey key = definition.key();
						final List<String> texts = new ArrayList<>(4);
						switch (key.kind()) {
							case ATTRIBUTE -> addStrings(texts, entity.getAttribute(key.name(), locale));
							case ASSOCIATED_DATA -> {
								final Serializable value = entity.getAssociatedData(key.name(), locale);
								if (value != null) {
									texts.addAll(SearchableTextExtractor.extract(value,
										schema.getAssociatedData(key.name()).orElseThrow().getTextExpression(), type,
										key.name(), SearchableTextExtractor.ExtractionMode.STRICT));
								}
							}
							case REFERENCE_ATTRIBUTE -> {
								for (final ReferenceContract reference : entity.getReferences(key.referenceName())) {
									addStrings(texts, reference.getAttribute(key.name(), locale));
								}
							}
						}
						for (final String text : texts) {
							for (final String word : splitStored(group.analyzer().textSplitting(), text)) {
								counts.merge(word, 1L, Long::sum);
							}
						}
					}
				}
				final List<String> nameTexts = new ArrayList<>(2);
				addStrings(nameTexts, name.isLocalized() ? entity.getAttribute(nameAttribute, locale)
					: entity.getAttribute(nameAttribute));
				for (final String text : nameTexts) {
					final List<String> words = splitStored(nameAnalyzer.textSplitting(), text);
					if (!words.isEmpty()) {
						final int length = 1 + random.nextInt(Math.min(3, words.size()));
						final int start = random.nextInt(words.size() - length + 1);
						final String phrase = String.join(" ", words.subList(start, start + length));
						nameCount++;
						final int capacity = smoke ? 3 : 2_000;
						if (names.size() < capacity) {
							names.add(phrase);
						} else {
							final long selected = random.nextLong(nameCount);
							if (selected < capacity) {
								names.set((int) selected, phrase);
							}
						}
					}
				}
			}
		}
		assertFalse(counts.isEmpty(), "No stored searchable text was found");
		assertFalse(names.isEmpty(), "No name phrases were found");
		return new Corpus(counts, names, nameCount);
	}

	/** Handles both supported text attribute shapes, without converting arbitrary objects to text. */
	private static void addStrings(List<String> texts, Serializable value) {
		if (value instanceof String text) {
			texts.add(text);
		} else if (value instanceof String[] array) {
			for (final String text : array) {
				if (text != null) {
					texts.add(text);
				}
			}
		}
	}

	/** Uses the production splitting stream without the query word-count guard on long stored fields. */
	private static List<String> splitStored(FulltextAnalyzer splitting, String text) {
		final List<String> words = new ArrayList<>(Math.min(256, text.length() / 5 + 1));
		final int[] endOfOriginal = {-1};
		splitting.analyze(text, (term, surface, start, end, increment) -> {
			// The stream emits an original before its contained parts; sample that word only once.
			if (!term.isEmpty() && start >= endOfOriginal[0]) {
				words.add(term);
				endOfOriginal[0] = end;
			}
		});
		return words;
	}

	/** Creates all cohorts once; subsequent JVMs consume this saved set rather than resampling. */
	static List<Query> queries(Corpus corpus, List<Group> groups, FulltextIndex index,
		FulltextTypoParameters policy, Random random, boolean smoke) throws Exception {
		final List<Query> result = new ArrayList<>(smoke ? 200 : 100_000);
		final List<String> vocabulary = new ArrayList<>(corpus.counts().keySet());
		Collections.sort(vocabulary);
		final long[] cumulative = new long[vocabulary.size()];
		long total = 0;
		final List<String> frequent = new ArrayList<>(vocabulary.size());
		final List<String> fragmentWords = new ArrayList<>(vocabulary.size());
		for (int i = 0; i < vocabulary.size(); i++) {
			final String word = vocabulary.get(i);
			total += corpus.counts().get(word);
			cumulative[i] = total;
			if (corpus.counts().get(word) >= 5) {
				frequent.add(word);
			}
			if (word.length() >= 4 && word.chars().allMatch(Character::isLetter)) {
				fragmentWords.add(word);
			}
		}
		assertFalse(frequent.isEmpty(), "Vocabulary contains no word occurring at least five times");
		assertFalse(fragmentWords.isEmpty(), "Vocabulary contains no eligible fragment word");
		final CompiledFulltextAnalyzer analyzer = groups.getFirst().analyzer();
		for (int i = 0; i < (smoke ? 3 : 3_000); i++) {
			final long draw = random.nextLong(total) + 1;
			final int location = Arrays.binarySearch(cumulative, draw);
			addVariants(result, "by-token", vocabulary.get(location >= 0 ? location : -location - 1), analyzer, random);
			addVariants(result, "by-vocabulary", frequent.get(random.nextInt(frequent.size())), analyzer, random);
		}
		for (int i = 0; i < (smoke ? 3 : 2_000); i++) {
			final String phrase = corpus.names().get(i % corpus.names().size());
			add(result, "name-phrases", "clean", phrase + " ", phrase + " ", 1);
			addPhraseTypo(result, "name-phrases", phrase, analyzer, random, 1, true);
		}
		final List<String> widePool = new ArrayList<>(vocabulary.stream()
			.filter(w -> w.codePointCount(0, w.length()) >= 4).toList());
		Collections.shuffle(widePool, random);
		final Map<String, Long> widths = CollectionUtils.createLinkedHashMap(Math.min(WIDE_SAMPLE, widePool.size()));
		for (final String word : widePool.subList(0, Math.min(smoke ? 4 : WIDE_SAMPLE, widePool.size()))) {
			final String typo = oneTypo(word, analyzer, random);
			long sum = inexactPostings(
				expand(new Query("", "", "", word + " ", word, 1), groups, index, policy, false));
			sum = Math.max(sum, inexactPostings(expand(new Query("", "", "", typo + " ", word, 1),
				groups, index, policy, false)));
			final String fragment = word.substring(0,
				word.offsetByCodePoints(0, Math.min(7, word.codePointCount(0, word.length()))));
			sum = Math.max(sum, inexactPostings(expand(new Query("", "", "", fragment, word, 1),
				groups, index, policy, false)));
			widths.put(word, sum);
		}
		final List<String> wide = new ArrayList<>(widths.keySet());
		wide.sort(Comparator.<String>comparingLong(widths::get).reversed().thenComparing(Comparator.naturalOrder()));
		for (final String word : wide.subList(0, Math.min(smoke ? 2 : 500, wide.size()))) {
			add(result, "wide", "clean", word + " ", word + " ", 1);
			final String typo = oneTypo(word, analyzer, random);
			add(result, "wide", typo.equals(word) ? "typo-ineligible" : "one-typo", typo + " ", word + " ", 1);
			add(result, "wide", "fragment", word.substring(0,
				word.offsetByCodePoints(0, Math.min(7, word.codePointCount(0, word.length())))), word, 1);
		}
		for (int i = 0; i < (smoke ? 4 : 2_000); i++) {
			final String clean = fragmentWords.get(random.nextInt(fragmentWords.size()));
			final boolean typo = i % 2 == 1;
			final String word = typo ? oneTypo(clean, analyzer, random) : clean;
			// Deletion may shorten a four-letter base below the requested fragment floor; resample that base.
			if (word.length() < 4) {
				i--;
				continue;
			}
			final int length = 4 + random.nextInt(Math.min(7, word.length()) - 3);
			add(result, "fragments", typo ? "mistyped-fragment" : "clean-fragment",
				word.substring(0, length), clean, 1);
		}
		final String headFile = System.getProperty(PREFIX + "headFile");
		if (headFile != null) {
			// Submitted searches end with a finished word; autocomplete input leaves the last word open.
			final boolean finished = Boolean.getBoolean(PREFIX + "headFinished");
			final String end = finished ? " " : "";
			int line = 0;
			for (final String row : Files.readAllLines(Path.of(headFile), StandardCharsets.UTF_8)) {
				line++;
				if (row.isBlank() || line == 1 && row.equalsIgnoreCase("phrase;count")) {
					continue;
				}
				final int separator = row.lastIndexOf(';');
				if (separator < 1) {
					throw new IllegalArgumentException("Invalid head CSV at line " + line);
				}
				String phrase = row.substring(0, separator);
				if (phrase.startsWith("\"") && phrase.endsWith("\"")) {
					phrase = phrase.substring(1, phrase.length() - 1).replace("\"\"", "\"");
				}
				final long weight = Long.parseLong(row.substring(separator + 1).trim());
				assertTrue(weight > 0, "Head counts must be positive");
				add(result, "head", "clean", phrase + end, phrase + end, weight);
				addPhraseTypo(result, "head", phrase, analyzer, random, weight, finished);
			}
		}
		return result;
	}

	/** Wide ranking excludes exact matches: it deliberately seeks expansion cost, not popular clean lookups. */
	private static long inexactPostings(List<Word> words) {
		return words.stream().flatMap(w -> w.hits().stream()).filter(h -> h.leg() != FulltextTermExactness.EXACT)
			.mapToLong(Hit::postings).sum();
	}

	/** Adds every operation/position control for one sampled base; the first profile locates the suffix boundary. */
	private static void addVariants(List<Query> queries, String cohort, String word,
		CompiledFulltextAnalyzer analyzer, Random random) {
		final String[] stems = terms(analyzer.indexSurface(),
			new TextMatchesTranslator.QueryToken(word, new String[]{word}));
		for (final Variant variant : variants(word, stems.length == 0 ? word : stems[0], random)) {
			add(queries, cohort, variant.kind(), variant.text() + " ", word + " ", 1);
		}
	}

	/** Selects an actual one-edit stem/suffix mutation; no first-letter control enters the typical typo cohorts. */
	private static String oneTypo(String word, CompiledFulltextAnalyzer analyzer, Random random) {
		final String[] stems = terms(analyzer.indexSurface(),
			new TextMatchesTranslator.QueryToken(word, new String[]{word}));
		final List<Variant> choices = variants(word, stems.length == 0 ? word : stems[0], random).stream()
			.filter(v -> (v.kind().startsWith("stem-") || v.kind().startsWith("suffix-"))).toList();
		return choices.isEmpty() ? word : choices.get(random.nextInt(choices.size())).text();
	}

	/** Replaces one eligible phrase word while keeping the clean phrase as the target for every profile. */
	private static void addPhraseTypo(List<Query> queries, String cohort, String phrase,
		CompiledFulltextAnalyzer analyzer, Random random, long weight, boolean finished) {
		final TextMatchesTranslator.QueryToken[] tokens =
			TextMatchesTranslator.analyze(analyzer.textSplitting(), phrase);
		final List<TextMatchesTranslator.QueryToken> eligible = Arrays.stream(tokens)
			.filter(t -> t.surfaceForm().length() >= 4 && t.surfaceForm().codePoints().allMatch(Character::isLetter))
			.toList();
		if (eligible.isEmpty()) {
			add(queries, cohort, "typo-ineligible", phrase + (finished ? " " : ""), phrase, weight);
			return;
		}
		final TextMatchesTranslator.QueryToken token = eligible.get(random.nextInt(eligible.size()));
		final String raw = token.surfaceForm();
		final int start = token.endOffset() - raw.length();
		if (start < 0 || token.endOffset() > phrase.length() ||
			!phrase.substring(start, token.endOffset()).equals(raw)) {
			// Real input can hold characters the text transformation rewrites, shifting offsets: keep a counted
			// control.
			add(queries, cohort, "typo-unlocatable", phrase + (finished ? " " : ""), phrase, weight);
			return;
		}
		final String changed = phrase.substring(0, start) + oneTypo(raw, analyzer, random) +
			phrase.substring(start + raw.length()) + (finished ? " " : "");
		add(queries, cohort, "one-typo", changed, phrase + (finished ? " " : ""), weight);
	}

	/** Gives repeated samples distinct identities so their sampling frequency is retained in comparison files. */
	private static void add(List<Query> queries, String cohort, String kind, String text, String clean, long weight) {
		queries.add(new Query(hash(queries.size() + "\0" + cohort + "\0" + kind + "\0" + text + "\0" + clean),
			cohort, kind, text, clean, weight));
	}

	/** Public API queries use the real phase-one scorer and only primary keys leave the engine. */
	private static void topk(EvitaSessionContract session, String type, Locale locale, Path output, List<Query> queries)
		throws IOException, ReflectiveOperationException {
		try (final BufferedWriter writer = Files.newBufferedWriter(output.resolve("topk-" + resolvedCap() + ".tsv"))) {
			writer.write("queryHash\tprimaryKeys\n");
			for (final Query item : queries) {
				final List<EntityReferenceContract> found = session.queryListOfEntityReferences(query(collection(type),
					filterBy(entityLocaleEquals(locale), textMatches(item.text())), orderBy(relevanceText()),
					require(page(1, 20))));
				writer.write(item.id());
				writer.write('\t');
				writer.write(String.join(",", found.stream().map(e -> Integer.toString(e.getPrimaryKey())).toList()));
				writer.newLine();
			}
		}
	}

	/**
	 * Ranks the clicked target of every real search the way the ranked search orders it. Input rows are
	 * `id TAB finished(0/1) TAB targetPrimaryKey TAB Base64(UTF-8 text)`; a finished text gets the trailing space that
	 * closes its last word. Each distinct text is queried once, ordered by relevance, and the target's 1-based position
	 * within the first `depth` results is written as its rank: 0 when absent, -1 when the query fails (the exception's
	 * class name is kept, never its message). An absent target is queried once more restricted to its own primary key
	 * and without the visibility filter: `targetMatches` tells a target ranked below `depth` (1) from one the text
	 * does not match at all (0). Only ids, ranks, counts and primary keys leave the engine.
	 */
	private static void clicks(EvitaSessionContract session, String type, Locale locale, Path output, Path input)
		throws IOException, ReflectiveOperationException {
		final int depth = Integer.getInteger(PREFIX + "depth", 100);
		final String filterAttribute = System.getProperty(PREFIX + "filterAttribute");
		final String filterValue = System.getProperty(PREFIX + "filterValue");
		assertTrue((filterAttribute == null) == (filterValue == null), "Set filterAttribute and filterValue together");
		assertTrue(depth >= 1, "Depth must be positive");
		final Map<String, Ranked> ranked = new HashMap<>(4_096);
		final Path target = output.resolve("clicks-" + resolvedCap() + ".tsv");
		try (final BufferedWriter writer = Files.newBufferedWriter(target)) {
			writer.write("id\trank\ttotal\tfailure\ttargetMatches\tprimaryKeys\n");
			for (final String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
				if (row.isBlank()) {
					continue;
				}
				final String[] fields = row.split("\t", -1);
				assertEquals(4, fields.length, "Clicks rows need four columns");
				final String text = new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8) +
					(fields[1].equals("1") ? " " : "");
				final Ranked result = ranked.computeIfAbsent(text,
					t -> rank(session, type, locale, t, depth, filterAttribute, filterValue));
				final int targetPrimaryKey = Integer.parseInt(fields[2]);
				int rank = result.failure() == null ? 0 : -1;
				for (int i = 0; i < result.primaryKeys().length && rank == 0; i++) {
					if (result.primaryKeys()[i] == targetPrimaryKey) {
						rank = i + 1;
					}
				}
				final String targetMatches = rank != 0 ? ""
					: matches(session, type, locale, text, targetPrimaryKey) ? "1" : "0";
				writer.write(fields[0] + '\t' + rank + '\t' + result.total() + '\t' +
					(result.failure() == null ? "" : result.failure()) + '\t' + targetMatches + '\t' +
					String.join(",", Arrays.stream(result.primaryKeys()).mapToObj(Integer::toString).toList()));
				writer.newLine();
			}
		}
	}

	/** Runs one relevance-ordered search; a failure is reduced to the exception's class name. */
	private static Ranked rank(EvitaSessionContract session, String type, Locale locale, String text, int depth,
		String filterAttribute, String filterValue) {
		try {
			final EvitaResponse<EntityReferenceContract> response = session.query(query(collection(type),
				filterBy(entityLocaleEquals(locale), textMatches(text),
					filterAttribute == null ? null : attributeEquals(filterAttribute, filterValue)),
				orderBy(relevanceText()), require(page(1, depth))), EntityReferenceContract.class);
			final int[] primaryKeys = response.getRecordData().stream()
				.mapToInt(EntityReferenceContract::getPrimaryKey).toArray();
			return new Ranked(primaryKeys, response.getTotalRecordCount(), null);
		} catch (RuntimeException e) {
			return new Ranked(new int[0], 0, e.getClass().getSimpleName());
		}
	}

	/** Whether the text matches the target entity at all, regardless of rank and visibility; failures count as no. */
	private static boolean matches(EvitaSessionContract session, String type, Locale locale, String text, int target) {
		try {
			return session.query(query(collection(type), filterBy(entityLocaleEquals(locale),
				entityPrimaryKeyInSet(target), textMatches(text)), require(page(1, 1))),
				EntityReferenceContract.class).getTotalRecordCount() > 0;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** One search's ranked primary keys, its total match count, and the failing exception's class name if any. */
	private record Ranked(int[] primaryKeys, int total, String failure) { }

	/** Disables every existing logger as well as inherited logging; restores levels after the opt-in run. */
	private static final class QuietLogs implements AutoCloseable {
		private final Map<Logger, Level> levels = new IdentityHashMap<>();
		/** Query exceptions and slow-query loggers can include raw input, including in asynchronous engine work. */
		private QuietLogs() {
			final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
			for (final Logger logger : context.getLoggerList()) {
				this.levels.put(logger, logger.getLevel());
				logger.setLevel(Level.OFF);
			}
		}
		@Override
		public void close() { this.levels.forEach(Logger::setLevel); }
	}
}
