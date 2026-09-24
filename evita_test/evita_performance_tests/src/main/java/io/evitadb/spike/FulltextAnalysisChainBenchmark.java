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

import io.evitadb.index.fulltext.analysis.AnalyzerSlot;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.spike.footprint.FootprintSpikeSupport;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cost census of the shipped full-text analysis chains: what one `getIndexAnalyzer` / `getSearchAnalyzer`
 * call costs, per language and per side of the pipeline, in time, allocation and retained footprint.
 *
 * This is a **decision instrument kept for re-measurement**, not a guard. The number to re-check when a stemmer
 * table, a chain composition or a Lucene version changes is the query side's cost relative to the index side:
 * the query chains fold first and then fan each token out into every stem it could have had, which is real work
 * the index side does not do. The measured envelope it was accepted within is roughly 2× the index chain.
 *
 * Everything is driven through {@link FulltextAnalyzerRegistry} and {@link FulltextAnalyzer#analyze}, the exact
 * production entry points, so the NFC boundary normalization and the term-consumer path are inside every number.
 *
 * Three measurements per chain, swept over a 3-token bare-typed query (the query-side target workload — a query
 * is analyzed once per search request) and a ~50-word accented product description (the index-side shape):
 *
 * 1. **time** — average analysis time per call ({@code Mode.AverageTime});
 * 2. **allocation** — run with {@code -prof gc} and read {@code gc.alloc.rate.norm} (bytes per analyze call)
 *    plus {@code gc.count}/{@code gc.time} for collector pressure;
 * 3. **retained footprint** — the {@code main} below prints the JOL owned size of every chain's object graph,
 *    measured *unprimed* (see the comment in {@code main} for why), followed by the emitted-term counts, so
 *    fan-out is visible next to the costs.
 *
 * Run (footprint census first, then the JMH sweep; the add-opens are for JOL):
 * {@code java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
 * -cp evita_test/evita_performance_tests/target/benchmarks.jar
 * io.evitadb.spike.FulltextAnalysisChainBenchmark -prof gc}.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FulltextAnalysisChainBenchmark {

	/**
	 * Entity type the registry resolves against; no schema override is registered, so every lookup lands on the
	 * language default.
	 */
	private static final String ENTITY_TYPE = "PRODUCT";

	/**
	 * The query-side target workload per language: a short e-commerce query typed **without** diacritics, the
	 * way the asymmetric analyzer pairs assume users type.
	 */
	private static final Map<String, String> QUERY_TEXT = Map.of(
		"cs", "cerna kozena sedacka",
		"sk", "cierna kozena sedacka",
		"pl", "czarna skorzana sofa",
		"ro", "canapea neagra piele"
	);

	/**
	 * The index-side shape per language: a ~50-word accented product description, stop words included.
	 */
	private static final Map<String, String> DESCRIPTION_TEXT = Map.of(
		"cs",
		"Luxusní rohová sedačka s pravým koženým potahem je vhodná do každého moderního obývacího pokoje. "
			+ "Konstrukce z masivního bukového dřeva a vysoce odolné studené pěny zaručuje dlouhou životnost "
			+ "i při každodenním používání. Sedací souprava nabízí prostorný úložný prostor na lůžkoviny, "
			+ "nastavitelné opěrky hlavy a rozkládací funkci pro pohodlné spaní dvou osob. Povrch čistěte "
			+ "měkkým vlhkým hadříkem bez agresivních čisticích prostředků.",
		"sk",
		"Luxusná rohová sedačka s pravým koženým poťahom je vhodná do každej modernej obývacej izby. "
			+ "Konštrukcia z masívneho bukového dreva a vysoko odolnej studenej peny zaručuje dlhú životnosť "
			+ "aj pri každodennom používaní. Sedacia súprava ponúka priestranný úložný priestor na posteľnú "
			+ "bielizeň, nastaviteľné opierky hlavy a rozkladaciu funkciu pre pohodlné spanie dvoch osôb. "
			+ "Povrch čistite mäkkou vlhkou handričkou bez agresívnych čistiacich prostriedkov.",
		"pl",
		"Luksusowa narożna sofa z prawdziwą skórzaną tapicerką świetnie pasuje do każdego nowoczesnego "
			+ "salonu. Konstrukcja z masywnego drewna bukowego i wysoce odpornej zimnej pianki zapewnia "
			+ "długą żywotność nawet przy codziennym użytkowaniu. Zestaw wypoczynkowy oferuje przestronny "
			+ "schowek na pościel, regulowane zagłówki oraz funkcję rozkładania do wygodnego spania dla "
			+ "dwóch osób. Powierzchnię czyść miękką wilgotną szmatką bez agresywnych środków czyszczących.",
		"ro",
		"Canapeaua de colț de lux cu tapițerie din piele naturală se potrivește oricărui living modern. "
			+ "Construcția din lemn masiv de fag și spuma rece foarte rezistentă asigură o durată lungă de "
			+ "viață chiar și la utilizarea zilnică. Setul de canapele oferă un spațiu de depozitare "
			+ "generos pentru lenjerie, tetiere reglabile și funcție de extindere pentru dormitul confortabil "
			+ "a două persoane. Curățați suprafața cu o lavetă moale și umedă, fără detergenți agresivi."
	);

	/**
	 * The chains and the text being analyzed, built once per trial. The analyzers cache their stream components
	 * per thread, exactly as in production — so what the ops measure is the steady-state per-call cost, not
	 * chain construction.
	 */
	@State(Scope.Benchmark)
	public static class ChainState {

		/** Language whose analyzer pair the trial measures. */
		@Param({"cs", "sk", "pl", "ro"})
		public String language;

		/** Which input the trial analyzes — the 3-token bare query or the ~50-word accented description. */
		@Param({"QUERY", "DESCRIPTION"})
		public String textKind;

		/** Registry owning both chains; closed with the trial. */
		FulltextAnalyzerRegistry registry;
		/** The language's index-side chain. */
		FulltextAnalyzer index;
		/** The language's query-side chain. */
		FulltextAnalyzer search;
		/** The text every op analyzes. */
		String text;

		/**
		 * Resolves the pair and the trial's input text.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.registry = new FulltextAnalyzerRegistry();
			final Locale locale = new Locale(this.language);
			this.index = this.registry.getIndexAnalyzer(ENTITY_TYPE, locale);
			this.search = this.registry.getSearchAnalyzer(ENTITY_TYPE, locale);
			this.text = switch (this.textKind) {
				case "QUERY" -> QUERY_TEXT.get(this.language);
				case "DESCRIPTION" -> DESCRIPTION_TEXT.get(this.language);
				default -> throw new IllegalStateException("Unknown text kind: " + this.textKind);
			};
		}

		/**
		 * Releases the chains' per-thread stream components.
		 */
		@TearDown(Level.Trial)
		public void tearDown() {
			this.registry.close();
		}

	}

	/**
	 * One index-chain analysis of the trial text — the cost every stored value pays while being indexed, and
	 * the floor the query chain is compared against.
	 *
	 * @param state     the chains and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void indexChain(@Nonnull ChainState state, @Nonnull Blackhole blackhole) {
		state.index.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

	/**
	 * One query-chain analysis of the trial text — the cost a search request pays, including the stem-variant
	 * fan-out the index side does not run.
	 *
	 * @param state     the chains and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void searchChain(@Nonnull ChainState state, @Nonnull Blackhole blackhole) {
		state.search.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

	/**
	 * Prints the JOL retained-footprint census of every chain plus its emitted-term counts, then hands over to
	 * the JMH runner for the time/allocation sweep.
	 *
	 * @param args JMH runner arguments (benchmark regex, {@code -prof gc}, ...)
	 * @throws Exception when the JMH runner fails
	 */
	public static void main(String[] args) throws Exception {
		FootprintSpikeSupport.banner("Full-text analysis chain retained footprint (unprimed)");
		final Map<String, Integer> emittedTerms = new LinkedHashMap<>(16);
		for (final String language : new String[]{"cs", "sk", "pl", "ro"}) {
			final Locale locale = new Locale(language);
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry()) {
				final FulltextAnalyzer index = registry.getIndexAnalyzer(ENTITY_TYPE, locale);
				final FulltextAnalyzer search = registry.getSearchAnalyzer(ENTITY_TYPE, locale);
				// measure BEFORE the first analyze call: once a chain is used, Lucene's CloseableThreadLocal
				// maps the components under the Thread itself, and a graph walk from either analyzer then
				// swallows the whole thread-locals closure - both graphs collapse into one ~10 MB blob and the
				// comparison is meaningless. Unprimed, the walk sees exactly the configuration mass each chain
				// retains for life; the per-thread stream components created on first use are excluded and are
				// near-identical between the two sides anyway
				FootprintSpikeSupport.observation(
					language + " " + AnalyzerSlot.INDEX, FootprintSpikeSupport.ownedSize(index)
				);
				FootprintSpikeSupport.observation(
					language + " " + AnalyzerSlot.SEARCH, FootprintSpikeSupport.ownedSize(search)
				);
				emittedTerms.put(language + " index/query", index.getTerms(QUERY_TEXT.get(language)).size());
				emittedTerms.put(language + " search/query", search.getTerms(QUERY_TEXT.get(language)).size());
				emittedTerms.put(
					language + " index/description", index.getTerms(DESCRIPTION_TEXT.get(language)).size()
				);
				emittedTerms.put(
					language + " search/description", search.getTerms(DESCRIPTION_TEXT.get(language)).size()
				);
			}
		}
		System.out.println("  emitted terms: " + emittedTerms);
		System.out.println();
		org.openjdk.jmh.Main.main(args);
	}

}
