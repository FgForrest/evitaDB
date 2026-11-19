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

import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.utils.CollectionUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;

import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionCensusSupport.*;

/** File formats and aggregate-only reporting, with no dependency on a loaded catalog. */
final class FulltextExpansionCensusReport implements AutoCloseable {
	private final Path output;
	private final int[] caps;
	private final long[] limits;
	private final BufferedWriter rows;
	private final Map<String, Metrics> metrics = new TreeMap<>();
	private final Map<String, NetMetrics> nets = new TreeMap<>();
	private long queries;
	private long saturatedLegs;
	private final Map<String, List<WeightedValue>> wordTimings = new TreeMap<>();
	private final Map<String, List<WeightedValue>> queryPostings = new TreeMap<>();

	/** Opens output rows, retaining aggregates rather than all queries' candidate lists. */
	FulltextExpansionCensusReport(Path output, int[] caps, long[] limits) throws IOException {
		this.output = output;
		this.caps = IntStream.concat(Arrays.stream(caps), IntStream.of(10_000)).distinct().sorted().toArray();
		this.limits = limits;
		this.rows = Files.newBufferedWriter(output.resolve("census-rows.tsv"), StandardCharsets.UTF_8);
		this.rows.write("queryHash\tcohort\tkind\tweight\tword\tpart\tprofileGroup\ttargetHashes\t" +
			"expansionNanos\tcandidatesInLegOrder\ttargetPositions\n");
	}

	/** Writes Base64 text only to the explicit query artifact, without normalizing the user's head phrases. */
	static void writeQueries(Path file, List<Query> queries) throws IOException {
		try (final BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			writer.write("queryHash\tcohort\tkind\tweight\ttextBase64\tcleanBase64\n");
			for (final Query query : queries) {
				writer.write(query.id() + "\t" + query.cohort() + "\t" + query.kind() + "\t" + query.weight() +
					"\t" + encode(query.text()) + "\t" + encode(query.clean()) + "\n");
			}
		}
	}

	/** Reads the identical saved set in every JVM; malformed rows never appear in an exception. */
	static List<Query> readQueries(Path file) throws IOException {
		final List<Query> result = new ArrayList<>(10_000);
		final Set<String> identities = CollectionUtils.createHashSet(10_000);
		try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			if (!"queryHash\tcohort\tkind\tweight\ttextBase64\tcleanBase64".equals(reader.readLine())) {
				throw new IllegalArgumentException("Invalid queries.tsv header");
			}
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				final String[] cells = line.split("\t", -1);
				if (cells.length != 6 || !cells[0].matches("[0-9a-f]{64}") || !identities.add(cells[0]) ||
					!cells[1].matches("[a-z-]+") || !cells[2].matches("[a-z-]+")) {
					throw new IllegalArgumentException("Invalid or duplicate queries.tsv row");
				}
				final long weight = Long.parseLong(cells[3]);
				if (weight <= 0) {
					throw new IllegalArgumentException("Nonpositive queries.tsv weight");
				}
				result.add(new Query(cells[0], cells[1], cells[2], decode(cells[4]), decode(cells[5]), weight));
			}
		}
		if (result.isEmpty()) {
			throw new IllegalArgumentException("Empty queries.tsv");
		}
		return result;
	}

	/** Encodes arbitrary Unicode, tabs and newlines in a single TSV cell. */
	private static String encode(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	/** Decodes the query artifact without echoing a malformed value. */
	private static String decode(String value) {
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}

	/** Records uncapped candidates, then replays requested caps and query-wide drop rules. */
	void accept(Query query, List<Word> words) throws IOException {
		this.queries++;
		final Map<Integer, Long> perWord = CollectionUtils.createLinkedHashMap(words.size());
		for (final Word word : words) {
			perWord.merge(word.word(), word.nanos(), Long::sum);
		}
		final List<WeightedValue> timings = this.wordTimings.computeIfAbsent(query.cohort() + "|" + query.kind(),
			ignored -> new ArrayList<>(1_000));
		perWord.values().forEach(nanos -> timings.add(new WeightedValue(nanos, query.weight())));
		for (final Word word : words) {
			writeRow(query, word);
			for (final FulltextTermExactness leg : List.of(FulltextTermExactness.PREFIX, FulltextTermExactness.FUZZY)) {
				if (word.hits().stream().filter(h -> h.leg() == leg).count() >= 10_000) {
					this.saturatedLegs++;
				}
			}
		}
		for (final int cap : this.caps) {
			final List<Word> capped = cap(words, cap);
			for (final String leg : List.of("EXACT", "PREFIX", "FUZZY", "ALL")) {
				final List<WeightedValue> sums = this.queryPostings.computeIfAbsent(query.cohort() + "|" +
					query.kind() + "|" + leg + "|" + cap, ignored -> new ArrayList<>(1_000));
				sums.add(new WeightedValue(capped.stream().flatMap(w -> w.hits().stream())
					.filter(h -> included(h, leg)).mapToLong(Hit::postings).sum(), query.weight()));
			}
			for (int i = 0; i < words.size(); i++) {
				final Word original = words.get(i);
				final Word kept = capped.get(i);
				for (final String leg : List.of("EXACT", "PREFIX", "FUZZY", "ALL")) {
					final List<Hit> selected = kept.hits().stream().filter(h -> included(h, leg)).toList();
					final Metrics metric = this.metrics.computeIfAbsent(query.cohort() + "|" + query.kind() + "|" +
						leg + "|" + cap, ignored -> new Metrics());
					metric.words += query.weight();
					final boolean eligible = !original.targets().isEmpty();
					metric.eligible += eligible ? query.weight() : 0;
					metric.recalled += eligible && selected.stream().anyMatch(Hit::target) ? query.weight() : 0;
					metric.zeroEdit += eligible && selected.stream().anyMatch(h -> h.target() && h.distance() == 0)
						? query.weight() : 0;
					final long size = original.hits().stream().filter(h -> leg.equals("ALL")
						? h.leg() != FulltextTermExactness.EXACT : h.leg().name().equals(leg)).count();
					final boolean exceeds = leg.equals("ALL") ? Arrays.stream(FulltextTermExactness.values())
						.filter(l -> l != FulltextTermExactness.EXACT)
						.anyMatch(l -> original.hits().stream().filter(h -> h.leg() == l).count() > cap)
						: !leg.equals("EXACT") && size > cap;
					metric.exceeds += exceeds ? query.weight() : 0;
					metric.postings.add(
						new WeightedValue(selected.stream().mapToLong(Hit::postings).sum(), query.weight()));
					metric.nanos.add(new WeightedValue(original.nanos(), query.weight()));
					metric.hits += selected.size() * query.weight();
					metric.laneOnly += selected.stream().filter(h -> !h.stemReachable()).count() * query.weight();
					metric.laneTarget += eligible && selected.stream().anyMatch(Hit::target) &&
						selected.stream().filter(Hit::target).noneMatch(Hit::stemReachable) ? query.weight() : 0;
				}
			}
			final long summed = postings(capped);
			for (final long limit : this.limits) {
				final NetMetrics metric = this.nets.computeIfAbsent(query.cohort() + "|" + query.kind() + "|" +
					cap + "|" + limit, ignored -> new NetMetrics());
				metric.queries += query.weight();
				if (summed > limit) {
					metric.bites += query.weight();
					metric.eligible += capped.stream().filter(w -> !w.targets().isEmpty()).count() * query.weight();
					metric.before += recalled(capped) * query.weight();
					metric.skip += recalled(net(capped, limit, false)) * query.weight();
					metric.stop += recalled(net(capped, limit, true)) * query.weight();
				}
			}
		}
	}

	/** Per-leg S and recall include exact terms, with ALL covering the complete word/group expansion. */
	private static boolean included(Hit hit, String leg) {
		return leg.equals("ALL") || hit.leg() == FulltextTermExactness.EXACT || hit.leg().name().equals(leg);
	}

	/** Serializes all per-field frequencies and target positions in one row per word/part/profile. */
	private void writeRow(Query query, Word word) throws IOException {
		final StringBuilder hits = new StringBuilder(Math.max(64, word.hits().size() * 120));
		final StringBuilder positions = new StringBuilder(64);
		final int[] counts = new int[FulltextTermExactness.values().length];
		for (final Hit hit : word.hits()) {
			final int position = ++counts[hit.leg().ordinal()];
			if (hit.target()) {
				positions.append(hit.leg()).append(':').append(position).append(':').append(hit.hash()).append(';');
			}
			hits.append(hit.leg()).append(':').append(position).append(':').append(hit.hash()).append(':')
				.append(hit.distance()).append(':').append(hit.stemReachable() ? "stem" : "lane-only").append(':');
			for (final Map.Entry<Integer, Long> field : hit.fields().entrySet()) {
				hits.append(field.getKey()).append('=').append(field.getValue()).append(',');
			}
			hits.append(';');
		}
		this.rows.write(query.id() + "\t" + query.cohort() + "\t" + query.kind() + "\t" + query.weight() + "\t" +
			word.word() + "\t" + word.part() + "\t" + word.group() + "\t" + String.join(",", word.targets()) +
			"\t" + word.nanos() + "\t" + hits + "\t" + positions + "\n");
	}

	/** Finishes aggregate files after closing detailed rows, so BUILD SUCCESS implies complete artifacts. */
	@Override
	public void close() throws IOException {
		this.rows.close();
		try (final BufferedWriter writer = Files.newBufferedWriter(this.output.resolve("census-summary.md"))) {
			writer.write("# Expansion census\n\nQueries: " + this.queries + ". Legs reaching the 10,000 bound: " +
				this.saturatedLegs + ". Those legs are censored, not proof of an exhaustive dictionary.\n\n");
			writer.write("The saved query set is reused verbatim. Seed defaults to 20261105. By-token sampling uses " +
				"stored-text occurrence counts; by-vocabulary samples uniformly from distinct words seen at least " +
				"five times. Each has 3,000 clean bases plus synthetic controls. Name phrases are a uniform " +
				"reservoir of 2,000 names, each contributing a random 1–3 consecutive-word run (short corpora repeat " +
				"the reservoir). Wide ranks up to 2,000 uniformly sampled distinct words of 4+ code points, " +
				"including codes, by the maximum inexact S of a finished clean word, one synthetic typo and a " +
				"7-letter-or-shorter fragment; it keeps up to 500 bases. Fragments contain 2,000 samples, half clean " +
				"and half mistyped, of 4–7 letters; truncation can remove the edit. Smoke reduces all sizes.\n\n");
			writer.write("Every eligible sampled base has substitution, insertion, deletion and transposition " +
				"variants in the stem and suffix, plus first-letter controls. Impossible positions/swaps are absent. " +
				"Two edits are distinct substitutions on bases of at least eight letters. Edits reuse the study's " +
				"BMP folded-word model with Latin replacement letters; other bases keep clean and missing-diacritics " +
				"controls. The first profile's first index stem locates the common-prefix boundary; targets are " +
				"clean query stems separately under every profile. Missing diacritics is a separate control; " +
				"zeroEditRecall checks recovery at distance zero. Head rows retain their CSV count as weight; all " +
				"other samples weigh one. Ineligible head/name phrases keep an explicit typo-ineligible control.\n\n");
			writer.write("Recall means at least one clean stem is retained per word/part/profile; empty target sets " +
				"are excluded. Each leg's S and recall include exact terms; ALL includes every leg. Quantiles are " +
				"weighted nearest-rank, with no blending across cohorts or typo classes. Lane-only means the " +
				"production stem lookup/prefix scan/walker cannot reach that stem in the same matching class. Rows " +
				"preserve production leg order, field frequencies and 1-based target positions (absent means " +
				"unreached).\n\n");
			writer.write("Cap replay truncates the recorded high-cap merged legs. At a lower production cap, a " +
				"discarded prefix can compete again in the typo leg; replay cannot reproduce that promotion. Use the " +
				"separate public-API topk JVMs to measure actual capped engine results. The query-wide net reserves " +
				"all exact postings first, then visits word, profile, part and prefix/typo leg order. A stem's field " +
				"views are retained/dropped together. Stop-at-limit stops on the first overflow across the whole " +
				"query. Exact terms survive even when exact S alone exceeds the limit.\n\n");
			writer.write("Expansion timings are indicative single-JVM wall time, not JMH. They time real expansion " +
				"only, excluding analysis, provenance walks and reporting. They are per word/part/profile call, " +
				"repeated across cap/leg rows for comparison; allocation, JIT and GC are uncontrolled.\n\n");
			writer.write("Summed postings S of the whole query (all words, parts, profiles and fields):\n\n" +
				"| cohort | kind | leg | cap | S p50 | S p90 | S p99 | S max |\n" +
				"|---|---|---|---:|---:|---:|---:|---:|\n");
			for (final Map.Entry<String, List<WeightedValue>> entry : this.queryPostings.entrySet()) {
				final List<WeightedValue> values = entry.getValue();
				values.sort(Comparator.comparingLong(WeightedValue::value));
				writer.write("| " + entry.getKey().replace("|", " | ") + " | " + percentile(values, .50) + " | " +
					percentile(values, .90) + " | " + percentile(values, .99) + " | " + percentile(values, 1) + " |\n");
			}
			writer.write("\nWord/part/profile distributions and target recall (S here is local to that call):\n\n");
			writer.write("| cohort | kind | leg | cap | words (weighted) | targets | recall | zeroEditRecall | " +
				"over-cap share | S p50 | S p90 | S p99 | S max | ns p50 | ns p90 | ns p99 | lane-only share | " +
				"lane-only target share |\n" +
				"|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
			for (final Map.Entry<String, Metrics> entry : this.metrics.entrySet()) {
				final Metrics m = entry.getValue();
				m.postings.sort(Comparator.comparingLong(WeightedValue::value));
				m.nanos.sort(Comparator.comparingLong(WeightedValue::value));
				writer.write("| " + entry.getKey().replace("|", " | ") + " | " + m.words + " | " + m.eligible + " | " +
					ratio(m.recalled, m.eligible) + " | " + ratio(m.zeroEdit, m.eligible) + " | " +
					ratio(m.exceeds, m.words) + " | " + percentile(m.postings, .50) + " | " +
					percentile(m.postings, .90) + " | " +
					percentile(m.postings, .99) + " | " + percentile(m.postings, 1) + " | " +
					percentile(m.nanos, .50) + " | " + percentile(m.nanos, .90) + " | " + percentile(m.nanos, .99) +
					" | " + ratio(m.laneOnly, m.hits) + " | " + ratio(m.laneTarget, m.recalled) + " |\n");
			}
			writer.write("\nExpansion wall time per shared query word (summed over its profiles and parts):\n\n" +
				"| cohort | kind | ns p50 | ns p90 | ns p99 |\n|---|---|---:|---:|---:|\n");
			for (final Map.Entry<String, List<WeightedValue>> entry : this.wordTimings.entrySet()) {
				final List<WeightedValue> values = entry.getValue();
				values.sort(Comparator.comparingLong(WeightedValue::value));
				writer.write("| " + entry.getKey().replace("|", " | ") + " | " + percentile(values, .50) + " | " +
					percentile(values, .90) + " | " + percentile(values, .99) + " |\n");
			}
			writer.write("\n| cohort | kind | cap | net limit | queries (weighted) | bite share | targets on bitten " +
				"queries | recall before net | recall skip-and-continue | recall stop-at-limit |\n" +
				"|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
			for (final Map.Entry<String, NetMetrics> entry : this.nets.entrySet()) {
				final NetMetrics m = entry.getValue();
				writer.write("| " + entry.getKey().replace("|", " | ") + " | " + m.queries + " | " +
					ratio(m.bites, m.queries) + " | " + m.eligible + " | " + ratio(m.before, m.eligible) + " | " +
					ratio(m.skip, m.eligible) + " | " + ratio(m.stop, m.eligible) + " |\n");
			}
		}
	}

	/** A zero denominator is undefined, not a perfect or failed recall. */
	private static String ratio(long numerator, long denominator) {
		return denominator == 0 ? "n/a" : String.format(Locale.ROOT, "%.6f", (double) numerator / denominator);
	}

	/** Weighted nearest-rank quantile of a sorted sample. */
	private static long percentile(List<WeightedValue> values, double quantile) {
		final long total = values.stream().mapToLong(WeightedValue::weight).sum();
		final long rank = Math.max(1, (long) Math.ceil(total * quantile));
		long count = 0;
		for (final WeightedValue value : values) {
			count += value.weight();
			if (count >= rank) {
				return value.value();
			}
		}
		return 0;
	}

	/** Reads at most twenty unique primary keys per identity; rejects missing, duplicate or extraneous queries. */
	private static Map<String, List<Integer>> readTopk(Path file) throws IOException {
		final Map<String, List<Integer>> result = CollectionUtils.createLinkedHashMap(10_000);
		try (final BufferedReader reader = Files.newBufferedReader(file)) {
			if (!"queryHash\tprimaryKeys".equals(reader.readLine())) {
				throw new IllegalArgumentException("Invalid topk header");
			}
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				final String[] cells = line.split("\t", -1);
				if (cells.length != 2 || !cells[0].matches("[0-9a-f]{64}")) {
					throw new IllegalArgumentException("Invalid topk row");
				}
				final List<Integer> keys = cells[1].isEmpty() ? List.of() : Arrays.stream(cells[1].split(","))
					.map(Integer::parseInt).toList();
				if (keys.size() > 20 || keys.stream().distinct().count() != keys.size() ||
					result.put(cells[0], keys) != null) {
					throw new IllegalArgumentException("Invalid or duplicate topk identity/list");
				}
			}
		}
		return result;
	}

	/**
	 * Files-only weighted agreement by cohort and kind; no catalog or engine is created here. A cohort-restricted
	 * topk covers a subset of queries.tsv: both files must hold the same identities, every one of them saved there.
	 */
	static void compare(Path output, Path reference, Path candidate) throws IOException {
		final List<Query> queries = readQueries(output.resolve("queries.tsv"));
		final Map<String, List<Integer>> expected = readTopk(reference);
		final Map<String, List<Integer>> actual = readTopk(candidate);
		final Set<String> identities = CollectionUtils.createHashSet(queries.size());
		queries.forEach(q -> identities.add(q.id()));
		if (!identities.containsAll(expected.keySet()) || !actual.keySet().equals(expected.keySet())) {
			throw new IllegalArgumentException("Topk files must contain the same queries.tsv identities");
		}
		final Map<String, double[]> aggregates = new TreeMap<>();
		for (final Query query : queries) {
			if (!expected.containsKey(query.id())) {
				continue;
			}
			for (final String group : List.of(query.cohort(), query.cohort() + " / " + query.kind())) {
				final double[] sum = aggregates.computeIfAbsent(group, ignored -> new double[4]);
				sum[0]++;
				sum[1] += query.weight();
				sum[2] += overlap(expected.get(query.id()), actual.get(query.id())) * query.weight();
				sum[3] += identical(expected.get(query.id()), actual.get(query.id())) ? query.weight() : 0;
			}
		}
		try (final BufferedWriter writer = Files.newBufferedWriter(output.resolve("topk-comparison.md"))) {
			writer.write("# Top twenty agreement\n\nOverlap is intersection divided by the larger list size, capped " +
				"at 20; two empty results agree. Head counts weight the means inside that cohort.\n\n" +
				"| cohort / kind | queries | weight | overlap at 20 | identical ordering share |\n" +
				"|---|---:|---:|---:|---:|\n");
			for (final Map.Entry<String, double[]> entry : aggregates.entrySet()) {
				final double[] sum = entry.getValue();
				writer.write(String.format(Locale.ROOT, "| %s | %.0f | %.0f | %.6f | %.6f |%n",
					entry.getKey(), sum[0], sum[1], sum[2] / sum[1], sum[3] / sum[1]));
			}
		}
	}

	/** Weighted scalar sample for nearest-rank quantiles. */
	private record WeightedValue(long value, long weight) { }
	/** Per cohort/kind/leg/cap statistics. */
	private static final class Metrics {
		private long words, eligible, recalled, zeroEdit, exceeds, hits, laneOnly, laneTarget;
		private final List<WeightedValue> postings = new ArrayList<>(1_000);
		private final List<WeightedValue> nanos = new ArrayList<>(1_000);
	}
	/** Per cohort/kind/cap/net-limit statistics, recall conditioned on the net biting. */
	private static final class NetMetrics {
		private long queries, bites, eligible, before, skip, stop;
	}
}
