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


package io.evitadb.index.fulltext.analysis;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.test.fulltext.AnalyzedTerm;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import org.apache.lucene.analysis.Analyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.SCHEMA;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Tests compiled lookup, prepared stop words and ownership when catalog close races compilation. */
@DisplayName("Full-text analyzer registry")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextAnalyzerRegistryTest {
	/** The production cache API, with the same real schema used by catalog-free index fixtures. */
	@Nonnull
	private static CompiledFulltextAnalyzer lookup(
		@Nonnull FulltextAnalyzerRegistry registry, @Nonnull String language
	) {
		return registry.getCompiledAnalyzer(SCHEMA, "text", Locale.forLanguageTag(language));
	}

	/** Prepared index stop words, compared by exactly the folding used by the translator. */
	@Nested
	@DisplayName("Folded stop words")
	class FoldedStopWords {
		/** Pins accented and bare forms, including the real-word collision that requires a postings check. */
		@Test
		@DisplayName("should recognize folded stop words and preserve ordinary words")
		void shouldRecognizeFoldedStopWords() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				for (final String word : List.of("neni", "Není", "JESTE", "byt")) {
					assertTrue(lookup(registry, "cs").foldedStopWords().contains(
						CompiledFulltextAnalyzer.foldStopWord(word)));
				}
				assertFalse(lookup(registry, "cs").foldedStopWords().contains("skladem"));
				assertTrue(lookup(registry, "cs").foldedStopWords().contains("neni"));
				assertFalse(lookup(registry, "cs").foldedStopWords().contains("není"));
				for (final String word : List.of("byc", "juz")) {
					assertTrue(lookup(registry, "pl").foldedStopWords().contains(word));
				}
				assertFalse(lookup(registry, "pl").foldedStopWords().contains("herbata"));
				for (final String word : List.of("dupa", "și", "şi")) {
					assertTrue(lookup(registry, "ro").foldedStopWords().contains(
						CompiledFulltextAnalyzer.foldStopWord(word)));
				}
				assertFalse(lookup(registry, "ro").foldedStopWords().contains("ceai"));
				for (final String word : List.of("fur", "für")) {
					assertTrue(lookup(registry, "de").foldedStopWords().contains(
						CompiledFulltextAnalyzer.foldStopWord(word)));
				}
				assertFalse(lookup(registry, "de").foldedStopWords().contains("schuhe"));
				assertTrue(lookup(registry, "sk").foldedStopWords().isEmpty());
				assertTrue(lookup(registry, "fi").foldedStopWords().isEmpty());
				assertThrows(UnsupportedOperationException.class,
					() -> lookup(registry, "cs").foldedStopWords().add("x"));
			}
		}
	}

	/** Schema/content lookup replaces the retired language-to-name assignment. */
	@Nested
	@DisplayName("Instance resolution")
	class InstanceResolution {
		/** Collections share a catalog cache; full locale is part of the content context. */
		@Test
		@DisplayName("should share compiled content and preserve language output across countries")
		void shouldShareCompiledContent() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				assertSame(lookup(registry, "cs-CZ"), lookup(registry, "cs-CZ"));
				assertEquals(collectTerms(lookup(registry, "cs").index(), "Černé boty"),
					collectTerms(lookup(registry, "cs-CZ").index(), "Černé boty"));
				assertEquals(List.of("kirjat"), collectTerms(lookup(registry, "fi").index(), "Kirjat")
					.stream().map(AnalyzedTerm::term).toList());
				for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de", "fi")) {
					final CompiledFulltextAnalyzer compiled = lookup(registry, language);
					assertNotSame(compiled.index(), compiled.word());
					assertFalse(collectTerms(compiled.index(), "Books").isEmpty());
					assertFalse(collectTerms(compiled.word(), "Books").isEmpty());
				}
				assertEquals(List.of("english", "book"), collectTerms(lookup(registry, "en").index(), "English Books")
					.stream().map(AnalyzedTerm::term).toList());
				assertEquals(List.of("english", "book"), List.of("English", "Books").stream()
					.flatMap(word -> collectTerms(lookup(registry, "en").word(), word).stream())
					.map(AnalyzedTerm::term).toList());
				for (final String name : List.of("no-such-chain", " ")) {
					assertThrows(EvitaInvalidUsageException.class,
						() -> registry.getCompiledAnalyzer(SCHEMA, name, Locale.ENGLISH));
				}
			}
		}
	}

	/** A compiler spy observes close ownership without a legacy constructor or test-only production API. */
	@Nested
	@DisplayName("Lifecycle")
	class Lifecycle {
		/** All retained handles and future lookups fail explicitly after catalog close. */
		@Test
		@DisplayName("should refuse lookup after close and tolerate repeated close")
		void shouldFailAfterClose() {
			final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());
			final CompiledFulltextAnalyzer compiled = lookup(registry, "cs");
			registry.close();
			assertDoesNotThrow(registry::close);
			assertThrows(EvitaInvalidUsageException.class, () -> lookup(registry, "cs"));
			assertThrows(EvitaInvalidUsageException.class, () -> collectTerms(compiled.index(), "boty"));
			assertThrows(EvitaInvalidUsageException.class, () -> collectTerms(compiled.word(), "boty"));
			assertThrows(EvitaInvalidUsageException.class, () -> collectTerms(compiled.splitting(), "boty"));
		}

		/** Close drains an empty cache while compilation is still private; the losing lookup must release it. */
		@Test
		@DisplayName("should release an analyzer built while closing exactly once")
		void shouldNotLeakAnalyzerBuiltWhileClosing() throws Exception {
			final CountDownLatch built = new CountDownLatch(1);
			final CountDownLatch finish = new CountDownLatch(1);
			final List<Analyzer> observed = new CopyOnWriteArrayList<>();
			try (
				final FulltextAnalyzerRegistry registry = observedRegistry(observed, built, finish);
				final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()
			) {
				final Future<?> lookup = executor.submit(() -> lookup(registry, "cs"));
				try {
					assertTrue(built.await(30, TimeUnit.SECONDS));
					registry.close();
				} finally {
					finish.countDown();
				}
				final ExecutionException failure = assertThrows(ExecutionException.class,
					() -> lookup.get(30, TimeUnit.SECONDS));
				assertInstanceOf(EvitaInvalidUsageException.class, failure.getCause());
				verify(observed.getFirst(), times(1)).close();
			}
		}

		/** A published entry belongs to close, which must never close it twice. */
		@Test
		@DisplayName("should release a published analyzer exactly once")
		void shouldReleaseAnAnalyzerPublishedBeforeCloseOnce() throws Exception {
			final List<Analyzer> observed = new CopyOnWriteArrayList<>();
			try (final FulltextAnalyzerRegistry registry = observedRegistry(
				observed, new CountDownLatch(0), new CountDownLatch(0))) {
				lookup(registry, "cs");
				registry.close();
				registry.close();
				verify(observed.getFirst(), times(1)).close();
			}
		}

		/** Two lookups compiling the same content publish one result; the loser closes the chains it built. */
		@Test
		@DisplayName("should publish one of two concurrent compilations and close the other's chains once")
		void shouldCloseTheLosingChainsOfAConcurrentCompilation() throws Exception {
			final CountDownLatch built = new CountDownLatch(2);
			final CountDownLatch finish = new CountDownLatch(1);
			final List<Analyzer> observed = new CopyOnWriteArrayList<>();
			try (
				final FulltextAnalyzerRegistry registry = observedRegistry(observed, built, finish);
				final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()
			) {
				final Future<CompiledFulltextAnalyzer> first = executor.submit(() -> lookup(registry, "cs"));
				final Future<CompiledFulltextAnalyzer> second = executor.submit(() -> lookup(registry, "cs"));
				try {
					// both lookups have built their own index chain before either publishes
					assertTrue(built.await(30, TimeUnit.SECONDS));
				} finally {
					finish.countDown();
				}
				final CompiledFulltextAnalyzer published = first.get(30, TimeUnit.SECONDS);
				assertSame(published, second.get(30, TimeUnit.SECONDS));
				assertEquals(2, observed.size());
				assertEquals(1, closeCount(observed.get(0)) + closeCount(observed.get(1)),
					"Exactly the losing index chain is closed at publication");
				assertFalse(collectTerms(published.index(), "boty").isEmpty(), "The published chain stays usable");

				registry.close();
				for (final Analyzer analyzer : observed) {
					verify(analyzer, times(1)).close();
				}
			}
		}
	}

	/** Counts the closes a spied analyzer has seen. */
	private static long closeCount(@Nonnull Analyzer analyzer) {
		return mockingDetails(analyzer).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName().equals("close"))
			.count();
	}

	/** Observes every index chain a real compiler builds and optionally pauses its return before cache publication. */
	@Nonnull
	private static FulltextAnalyzerRegistry observedRegistry(
		@Nonnull List<Analyzer> observed, @Nonnull CountDownLatch built, @Nonnull CountDownLatch finish
	) throws Exception {
		final AnalysisStepRegistry steps = new AnalysisStepRegistry();
		final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(steps);
		final FulltextChainCompiler compiler = spy(new FulltextChainCompiler(steps));
		doAnswer(invocation -> {
			final Analyzer analyzer = spy((Analyzer) invocation.callRealMethod());
			observed.add(analyzer);
			built.countDown();
			assertTrue(finish.await(30, TimeUnit.SECONDS));
			return analyzer;
		}).when(compiler).compile(any(ResolvedFulltextAnalyzer.class),
			org.mockito.ArgumentMatchers.<java.util.List<CompiledFulltextAnalyzer.StopWordPreparation>>any());
		final java.lang.reflect.Field field = FulltextAnalyzerRegistry.class.getDeclaredField("compiler");
		field.setAccessible(true);
		field.set(registry, compiler);
		return registry;
	}
}
