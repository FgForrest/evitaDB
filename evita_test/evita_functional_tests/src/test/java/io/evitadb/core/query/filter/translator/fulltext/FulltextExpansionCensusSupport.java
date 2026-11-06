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
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.test.fulltext.SyntheticTypos;
import io.evitadb.test.fulltext.EditDistances;
import io.evitadb.utils.StringUtils;
import io.evitadb.test.fulltext.SyntheticTypos.TypoKind;
import io.evitadb.utils.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pure input generation, cap replay, query-wide net simulation and top-k comparison for the census. */
final class FulltextExpansionCensusSupport {
	/** Utility class. */
	private FulltextExpansionCensusSupport() { }

	/** One saved query; clean text remains the target under every analyzer profile. */
	record Query(String id, String cohort, String kind, String text, String clean, long weight) {
		@Override
		public String toString() { return "Census query " + this.id; }
	}
	/** One synthetic spelling, with position and operation kept separate from missing diacritics. */
	record Variant(String kind, String text) { }
	/** A distinct term in production leg order, holding every field view together. */
	record Hit(String hash, int distance, FulltextTermExactness leg, Map<Integer, Long> fields,
		boolean target, boolean stemReachable) {
		/** Sums field document frequencies, including repeated documents across fields. */
		long postings() { return this.fields.values().stream().mapToLong(Long::longValue).sum(); }
	}
	/** One shared query word (or contained part) and profile group. */
	record Word(int word, int part, int group, Set<String> targets, List<Hit> hits, long nanos) { }

	/** Stable opaque identities; no text is emitted by a record's generated toString. */
	static String hash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable");
		}
	}

	/** Samples the specification's position classes relative to the clean stem's common prefix. */
	static List<Variant> variants(String original, String stem, Random random) {
		final List<Variant> result = new ArrayList<>(18);
		result.add(new Variant("clean", original));
		final String word = CompiledFulltextAnalyzer.foldStopWord(original);
		result.add(new Variant("missing-diacritics", StringUtils.removeDiacritics(original)));
		if (word.length() < 4 || !word.chars().allMatch(Character::isLetter)) {
			return result;
		}
		int shared = 0;
		while (shared < word.length() && shared < stem.length() && word.charAt(shared) == stem.charAt(shared)) {
			shared++;
		}
		for (int region = 0; region < 3; region++) {
			final String position = switch (region) {
				case 0 -> "first";
				case 1 -> "stem";
				case 2 -> "suffix";
				default -> throw new IllegalStateException("Unknown typo region");
			};
			final int from = region == 0 ? 0 : region == 1 ? 1 : Math.max(1, shared);
			final int to = region == 0 ? 1 : region == 1 ? shared : word.length();
			if (from >= to) {
				continue;
			}
			for (final TypoKind kind : TypoKind.values()) {
				// A swap must remain in the selected region, and equal neighbours are not an edit.
				final List<Integer> positions = new ArrayList<>(to - from);
				for (int p = from; p < to; p++) {
					if (kind != TypoKind.TRANSPOSITION || p + 1 < (region == 0 ? word.length() : to) &&
						word.charAt(p) != word.charAt(p + 1)) {
						positions.add(p);
					}
				}
				if (positions.isEmpty()) {
					continue;
				}
				final String changed = SyntheticTypos.apply(word, kind,
					positions.get(random.nextInt(positions.size())), random);
				if (changed != null) {
					result.add(new Variant(position + "-" + kind.name().toLowerCase(Locale.ROOT), changed));
				}
			}
			if (word.length() >= 8 && to - from >= 2) {
				// Two distinct substitutions cannot cancel or become a single transposition.
				final int first = from + random.nextInt(to - from);
				int second = from + random.nextInt(to - from - 1);
				if (second >= first) {
					second++;
				}
				final String once = SyntheticTypos.apply(word, TypoKind.SUBSTITUTION, first, random);
				String twice;
				do {
					twice = SyntheticTypos.apply(Objects.requireNonNull(once), TypoKind.SUBSTITUTION, second, random);
				} while (twice == null || EditDistances.osaDistance(word, twice) != 2);
				result.add(new Variant("two-" + position + "-substitutions", twice));
			}
		}
		return result;
	}

	/** Cuts each already merged production leg independently; all field views of a stem survive together. */
	static List<Word> cap(List<Word> words, int cap) {
		final List<Word> result = new ArrayList<>(words.size());
		for (final Word word : words) {
			final int[] counts = new int[FulltextTermExactness.values().length];
			final List<Hit> kept = new ArrayList<>(word.hits().size());
			for (final Hit hit : word.hits()) {
				if (hit.leg() == FulltextTermExactness.EXACT || counts[hit.leg().ordinal()]++ < cap) {
					kept.add(hit);
				}
			}
			result.add(new Word(word.word(), word.part(), word.group(), word.targets(), kept, word.nanos()));
		}
		return result;
	}

	/** Reserves all exact postings, then visits words, groups and prefix/typo legs in production order. */
	static List<Word> net(List<Word> words, long limit, boolean stopAtLimit) {
		long sum = 0;
		for (final Word word : words) {
			for (final Hit hit : word.hits()) {
				if (hit.leg() == FulltextTermExactness.EXACT) {
					sum += hit.postings();
				}
			}
		}
		boolean stopped = false;
		final List<Word> result = new ArrayList<>(words.size());
		for (final Word word : words) {
			final List<Hit> kept = new ArrayList<>(word.hits().size());
			for (final Hit hit : word.hits()) {
				if (hit.leg() == FulltextTermExactness.EXACT) {
					kept.add(hit);
				} else if (!stopped && hit.postings() <= limit - sum) {
					kept.add(hit);
					sum += hit.postings();
				} else if (stopAtLimit) {
					stopped = true;
				}
			}
			result.add(new Word(word.word(), word.part(), word.group(), word.targets(), kept, word.nanos()));
		}
		return result;
	}

	/** Total S of a complete query, never a union cardinality. */
	static long postings(List<Word> words) {
		return words.stream().flatMap(w -> w.hits().stream()).mapToLong(Hit::postings).sum();
	}

	/** Counts word/profile targets reached by at least one clean stem. */
	static long recalled(List<Word> words) {
		return words.stream().filter(w -> !w.targets().isEmpty() && w.hits().stream().anyMatch(Hit::target)).count();
	}

	/** Intersection at 20 divided by the larger result size (up to 20); two empty lists agree. */
	static double overlap(List<Integer> reference, List<Integer> candidate) {
		final Set<Integer> expected = CollectionUtils.createHashSet(20);
		expected.addAll(reference.subList(0, Math.min(20, reference.size())));
		final Set<Integer> actual = CollectionUtils.createHashSet(20);
		actual.addAll(candidate.subList(0, Math.min(20, candidate.size())));
		final int denominator = Math.max(expected.size(), actual.size());
		expected.retainAll(actual);
		return denominator == 0 ? 1.0 : (double) expected.size() / denominator;
	}

	/** Ordering equality includes short/empty result lists. */
	static boolean identical(List<Integer> reference, List<Integer> candidate) {
		return reference.subList(0, Math.min(20, reference.size()))
			.equals(candidate.subList(0, Math.min(20, candidate.size())));
	}
}
