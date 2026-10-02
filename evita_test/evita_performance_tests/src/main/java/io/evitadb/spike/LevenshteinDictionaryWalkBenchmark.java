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

package io.evitadb.spike;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.test.fulltext.CzechLexicon;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Typo-tolerant term lookup: a Levenshtein automaton from `lucene-core` driving a guided walk over an evitaDB
 * `TransactionalBucketBPlusTree<String>` that holds the folded Czech vocabulary (`cs_CZ.dic`, ~241 000 keys), against
 * the linear scan of the same tree with the same acceptor.
 *
 * What the parameters mean:
 *
 * - `query` — the word as typed, already in the dictionary's space (lowercase, diacritics folded); `bnuda` is a
 *   transposition of `bunda`, `cerny` a common adjective with many neighbours;
 * - `maxEdits` — 1 or 2, the automaton's distance;
 * - `frozenPrefix` — 0 or 1 leading characters in which no edit is permitted;
 * - `readAhead` — 0 for a seek on every rejection (what Lucene does for a finite automaton), 63 (one leaf block) for
 *   a bounded sequential read before the seek. The functional test showed the read-ahead resolves 71–98 % of
 *   rejections without a root descent but does not gain time, because a linear leaf read costs as much as a descent;
 *   this benchmark is where that claim gets proper numbers.
 *
 * The scan arm ignores `frozenPrefix` and `readAhead` (they do not apply); JMH still runs it once per parameter set,
 * which is harmless and keeps the two arms side by side in one report. Run through JMH's own main class, because the
 * benchmarks jar declares a different one:
 *
 * ```shell
 * java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
 *   LevenshteinDictionaryWalkBenchmark -p query=kalhoty -p maxEdits=2 -p frozenPrefix=1
 * ```
 *
 * The lexicon is resolved by {@link CzechLexicon#load(boolean)}: classpath first, then the functional-test module's
 * resources on disk, overridable with `-Devita.lexicon.cs=<path>`.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class LevenshteinDictionaryWalkBenchmark {
	// odd on purpose: the tree derives its minimum block size as half of this and requires it to be strictly less
	private static final int LEAF_BLOCK_SIZE = 63;

	/**
	 * The dictionary is built once per trial; the walker is stateless between calls apart from its counters.
	 */
	@State(Scope.Benchmark)
	public static class DictionaryState {
		@Param({"kalhoty", "cerny", "bnuda", "mikina"})
		public String query;
		@Param({"1", "2"})
		public int maxEdits;
		@Param({"0", "1"})
		public int frozenPrefix;
		@Param({"0", "63"})
		public int readAhead;

		TransactionalBucketBPlusTree<String> dictionary;
		LevenshteinDictionaryWalker walker;
		int dictionarySize;

		@Setup(Level.Trial)
		public void setUp() {
			final Set<String> words = CzechLexicon.load(true);
			this.dictionary = new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
			int pk = 1;
			for (final String word : words) {
				this.dictionary.addRecord(word, pk++);
			}
			this.dictionarySize = words.size();
			this.walker = new LevenshteinDictionaryWalker(this.query, this.maxEdits, this.frozenPrefix, this.readAhead);
			// one dry run so the trial log carries the shape of the work being measured
			final int hits = this.walker.walk(this.dictionary).size();
			System.out.printf(
				Locale.ROOT,
				"# dictionary %d keys; `%s` maxEdits=%d frozenPrefix=%d readAhead=%d: %d hits, %d reads, %d seeks, " +
					"%d resolved by read-ahead%n",
				this.dictionarySize, this.query, this.maxEdits, this.frozenPrefix, this.readAhead,
				hits, this.walker.getReads(), this.walker.getSeeks(), this.walker.getReadAheadHits()
			);
		}
	}

	/**
	 * The guided walk: seek to the next acceptable string on every rejection, optionally after a bounded read-ahead.
	 */
	@Benchmark
	public void walk(@Nonnull DictionaryState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.walker.walk(state.dictionary));
	}

	/**
	 * The reference: every key of the tree through the widest acceptor. Linear in the dictionary.
	 */
	@Benchmark
	public void scan(@Nonnull DictionaryState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.walker.scan(state.dictionary));
	}
}
