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

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.FulltextResourceSchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.test.fulltext.AnalyzedTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.SCHEMA;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.retainedAnalyzerCount;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises content sharing, schema identity and eviction on the production compiled analyzer API. */
@DisplayName("Compiled fulltext analyzer cache")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextCompiledAnalyzerCacheTest {
	/** The chains compiled for the query side of one analyzer. */
	@Nested
	@DisplayName("Query chains")
	class QueryChains {
		/** Text-aware splitting keeps original offsets and surfaces; repeating character removal is idempotent. */
		@Test
		@DisplayName("should keep original surfaces through the text phase and apply it idempotently per word")
		void shouldPreserveOriginalSurfaceAndIdempotentTextPhase() {
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CatalogSchemaContract schema = schema(b -> b
					.withFulltextAnalyzer("joined", a -> a.text(removeCharacters("-")).words(lowercase())));
				final CompiledFulltextAnalyzer compiled = cache.getCompiledAnalyzer(schema, "joined", Locale.ENGLISH);
				final List<AnalyzedTerm> split = collectTerms(compiled.textSplitting(), "USB-C x-y");
				assertEquals(List.of("USB-C", "x-y"), split.stream().map(t -> t.surfaceForm()).toList(),
					"Text filters must retain surfaces from the original query");
				assertEquals(List.of(0, 6), split.stream().map(t -> t.startOffset()).toList());
				assertEquals(List.of(5, 9), split.stream().map(t -> t.endOffset()).toList());
				for (final AnalyzedTerm term : split) {
					final List<String> once = collectTerms(compiled.word(), term.surfaceForm()).stream()
						.map(t -> t.term()).toList();
					final List<String> twice = collectTerms(compiled.word(), term.term()).stream()
						.map(t -> t.term()).toList();
					assertEquals(once, twice, "Reapplying remove-characters to a transformed word must be a no-op");
				}
			}
		}
	}

	/** Cache lookups across schema changes. */
	@Nested
	@DisplayName("Schema changes")
	class SchemaChanges {
		/** Identical definitions in distinct schema objects share chains, independently of collection ownership. */
		@Test
		@DisplayName("should share identical content across schema instances and collections")
		void shouldShareContentAcrossSchemaInstances() {
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CatalogSchemaContract first = schema(b -> b.withDescription("first collection"));
				final CatalogSchemaContract second = schema(b -> b.withDescription("second collection"));
				assertNotSame(first, second);
				assertSame(cache.getCompiledAnalyzer(first, "text", Locale.ENGLISH).splitting(),
					cache.getCompiledAnalyzer(first, "html-text", Locale.ENGLISH).splitting());
				assertSame(cache.getCompiledAnalyzer(first, "text", Locale.ENGLISH),
					cache.getCompiledAnalyzer(second, "text", Locale.ENGLISH));
			}
		}

		/** Catalog version copies reuse definitions; only replacement resources may force content resolution. */
		@Test
		@DisplayName("should revalidate rebuilt catalogs by definition identity without resolving content")
		void shouldReuseDefinitionsAcrossCatalogCopies() {
			final CatalogSchemaContract base = schema(b -> b
				.withFulltextLanguage("en", language -> language.wordNumberSplit())
				.withFulltextResource("stop", wordList("unused"))
				.withFulltextAnalyzer("parent", a -> a.basedOn("text"))
				.withFulltextAnalyzer("names", a -> a.basedOn("parent")));
			// The resource spy is solely an observer of resolution's content read.
			final FulltextResourceSchemaContract resource = spy(base.getFulltextResources().get("stop"));
			final CatalogSchema first = CatalogSchema._internalBuildWithFulltext(base,
				base.getFulltextLanguages(), Map.of("stop", resource), base.getFulltextAnalyzers(),
				EmptyEntitySchemaAccessor.INSTANCE);
			final CatalogSchema rebuilt = CatalogSchema._internalBuildWithUpdatedEntitySchemaAccessor(
				first, EmptyEntitySchemaAccessor.INSTANCE);
			assertNotSame(first, rebuilt);
			assertNotSame(first.getFulltextResources(), rebuilt.getFulltextResources());
			assertSame(first.getFulltextAnalyzers().get("names"), rebuilt.getFulltextAnalyzers().get("names"));
			assertSame(first.getFulltextAnalyzers().get("parent"), rebuilt.getFulltextAnalyzers().get("parent"));
			assertSame(first.getFulltextLanguages().get("en"), rebuilt.getFulltextLanguages().get("en"));
			assertSame(resource, rebuilt.getFulltextResources().get("stop"));
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer compiled = cache.getCompiledAnalyzer(first, "names", Locale.ENGLISH);
				clearInvocations(resource);
				assertSame(compiled, cache.getCompiledAnalyzer(rebuilt, "names", Locale.ENGLISH));
				verify(resource, never()).getContent();
				final FulltextResourceSchemaContract replacement = spy(
					FulltextResourceSchema._internalBuild("stop", wordList("unused")));
				final CatalogSchema replaced = CatalogSchema._internalBuildWithFulltext(rebuilt,
					rebuilt.getFulltextLanguages(), Map.of("stop", replacement), rebuilt.getFulltextAnalyzers(),
					EmptyEntitySchemaAccessor.INSTANCE);
				assertSame(compiled, cache.getCompiledAnalyzer(replaced, "names", Locale.ENGLISH));
				verify(replacement).getContent();
			}
		}

		/** Synonym content affects query alternatives while leaving stored postings compatible. */
		@Test
		@DisplayName("should apply a query-only edit without changing the index fingerprint")
		void shouldApplyQueryOnlyEdit() {
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CatalogSchemaContract first = synonymsSchema("bicycle");
				final CatalogSchemaContract second = synonymsSchema("cycle");
				assertEquals(first.version(), second.version(), "Versions cannot identify a schema's content.");
				final CompiledFulltextAnalyzer before = cache.getCompiledAnalyzer(first, "names", Locale.ENGLISH);
				final CompiledFulltextAnalyzer after = cache.getCompiledAnalyzer(second, "names", Locale.ENGLISH);
				assertNotSame(before, after);
				assertEquals(before.fingerprint(), after.fingerprint());
				assertTrue(collectTerms(before.word(), "bike")
					.stream().anyMatch(term -> term.term().equals("bicycle")));
				assertTrue(collectTerms(after.word(), "bike").stream().anyMatch(term -> term.term().equals("cycle")));
			}
		}

		/** Stop-list edits affect the index identity and prepared folded words. */
		@Test
		@DisplayName("should change the index fingerprint and collect prepared stop words")
		void shouldChangeIndexIdentity() {
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer first = cache.getCompiledAnalyzer(
					schema(b -> b.withFulltextResource("stop", wordList("Není"))
						.withFulltextAnalyzer("names", a -> a.words(lowercase(), stopWords(resource("stop"))))),
					"names", Locale.forLanguageTag("cs"));
				final CompiledFulltextAnalyzer second = cache.getCompiledAnalyzer(
					schema(b -> b.withFulltextResource("stop", wordList("Jiný"))
						.withFulltextAnalyzer("names", a -> a.words(lowercase(), stopWords(resource("stop"))))),
					"names", Locale.forLanguageTag("cs"));
				assertNotEquals(first.fingerprint(), second.fingerprint());
				assertTrue(first.foldedStopWords().contains("neni"));
				assertTrue(collectTerms(first.index(), "není").isEmpty());
			}
		}
	}

	/** Bounded retention does not invalidate handles already held by queries. */
	@Nested
	@DisplayName("Lifecycle")
	class Lifecycle {
		/** Hundreds of edits retain only the schema window and its referenced definitions. */
		@Test
		@DisplayName("should bound repeated edits without closing evicted analyzers")
		void shouldBoundEditsWithoutClosingHandles() {
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CatalogSchemaContract oldSchema = synonymsSchema("first");
				final CompiledFulltextAnalyzer held = cache.getCompiledAnalyzer(oldSchema, "names", Locale.ENGLISH);
				for (int i = 0; i < 200; i++) {
					cache.getCompiledAnalyzer(synonymsSchema("word" + i), "names", Locale.ENGLISH);
				}
				assertTrue(retainedAnalyzerCount(cache) <= 8);
				assertTrue(collectTerms(held.word(), "bike").stream().anyMatch(term -> term.term().equals("first")));
				assertTrue(collectTerms(cache.getCompiledAnalyzer(oldSchema, "names", Locale.ENGLISH).word(), "bike")
					.stream().anyMatch(term -> term.term().equals("first")));
			}
		}

		/** A slow provider on a different key cannot serialize an already compiled lookup. */
		@Test
		@DisplayName("should serve hits while another key compiles")
		void shouldServeHitsWhileAnotherKeyCompiles() throws Exception {
			final CountDownLatch compiling = new CountDownLatch(1);
			final CountDownLatch finish = new CountDownLatch(1);
			final List<AnalysisStepFactory> providers = new ArrayList<>(ServiceLoader.load(AnalysisStepFactory.class)
				.stream().map(ServiceLoader.Provider::get).toList());
			providers.add(new BlockingWords(compiling, finish));
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry(providers));
				 final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				final CompiledFulltextAnalyzer ready = cache.getCompiledAnalyzer(SCHEMA, "text", Locale.ENGLISH);
				final java.util.concurrent.Future<?> blocked = executor.submit(() -> cache.getCompiledAnalyzer(
					schema(b -> b.withFulltextAnalyzer("blocked", a -> a.words(wordsStep("test-blocking-words")))),
					"blocked", Locale.ENGLISH));
				try {
					assertTrue(compiling.await(10, TimeUnit.SECONDS));
					assertSame(ready, executor.submit(() -> cache.getCompiledAnalyzer(SCHEMA, "text", Locale.ENGLISH))
						.get(2, TimeUnit.SECONDS));
					assertNotNull(executor.submit(() -> cache.getCompiledAnalyzer(SCHEMA, "html-text", Locale.ENGLISH))
						.get(2, TimeUnit.SECONDS));
				} finally {
					finish.countDown();
				}
				blocked.get(10, TimeUnit.SECONDS);
			}
		}

		/** A close racing a provider still compiling must reclaim its late publication and fail the lookup. */
		@Test
		@DisplayName("should reclaim a compiled analyzer published while closing")
		void shouldReclaimLatePublication() throws Exception {
			final CountDownLatch compiling = new CountDownLatch(1);
			final CountDownLatch finish = new CountDownLatch(1);
			final List<AnalysisStepFactory> providers = new ArrayList<>(ServiceLoader.load(AnalysisStepFactory.class)
				.stream().map(ServiceLoader.Provider::get).toList());
			providers.add(new BlockingWords(compiling, finish));
			final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry(providers));
			try (final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				final java.util.concurrent.Future<?> lookup = executor.submit(() -> cache.getCompiledAnalyzer(
					schema(b -> b.withFulltextAnalyzer("blocked", a -> a.words(wordsStep("test-blocking-words")))),
					"blocked", Locale.ENGLISH));
				assertTrue(compiling.await(10, TimeUnit.SECONDS));
				try {
					// the compiling lookup holds no lock, so the close completes while the compilation is still running
					executor.submit(cache::close).get(10, TimeUnit.SECONDS);
				} finally {
					finish.countDown();
				}
				final ExecutionException error = assertThrows(ExecutionException.class,
					() -> lookup.get(10, TimeUnit.SECONDS));
				assertInstanceOf(EvitaInvalidUsageException.class, error.getCause());
				assertEquals(0, retainedAnalyzerCount(cache));
			} finally {
				finish.countDown();
				cache.close();
			}
		}

		/** Catalog close releases all still-retained chains and refuses further lookup. */
		@Test
		@DisplayName("should close retained chains and refuse lookups")
		void shouldCloseRetainedChains() {
			final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());
			final CompiledFulltextAnalyzer held = cache.getCompiledAnalyzer(SCHEMA, "text", Locale.ENGLISH);
			cache.close();
			cache.close();
			assertThrows(EvitaInvalidUsageException.class, () -> cache.getCompiledAnalyzer(SCHEMA, "text",
				Locale.ENGLISH));
			assertThrows(EvitaInvalidUsageException.class, () -> collectTerms(held.index(), "bike"));
			assertThrows(EvitaInvalidUsageException.class, () -> collectTerms(held.word(), "bike"));
			assertThrows(EvitaInvalidUsageException.class, () -> collectTerms(held.splitting(), "bike"));
		}
	}

	/** A real provider pausing index compilation so close can raise its publication fence. */
	private record BlockingWords(
		@Nonnull CountDownLatch compiling, @Nonnull CountDownLatch finish
	) implements FulltextWordsAnalysisStepFactory {
		@Nonnull
		@Override
		public String getName() { return "test-blocking-words"; }
		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) { }
		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.INDEX); }
		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() { return Set.of(); }
		@Override
		public int getBehaviourVersion() { return 1; }
		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			this.compiling.countDown();
			try {
				if (!this.finish.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Compilation was never released.");
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			return (words, side) -> words;
		}
	}

	/** Builds a query-only synonym definition with controlled content. */
	@Nonnull
	private static CatalogSchemaContract synonymsSchema(@Nonnull String alternative) {
		return schema(b -> b.withFulltextResource("syn", synonymList(synonymGroup("bike", alternative)))
			.withFulltextAnalyzer("names", a -> a.words(lowercase(), synonyms(resource("syn")))));
	}

	/** Builds a distinct real catalog schema. */
	@Nonnull
	private static CatalogSchemaContract schema(@Nonnull Consumer<InternalCatalogSchemaBuilder> edit) {
		final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(SCHEMA);
		edit.accept(builder);
		return builder.toInstance();
	}
}
