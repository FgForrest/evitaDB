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

import io.evitadb.api.configuration.FulltextOptions;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import java.util.function.Consumer;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.analysis.*;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.spi.analysis.*;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.util.AttributeSource.State;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;

/** Real extension providers shared by fragment policy and surface expansion tests. */
final class FulltextSurfaceTestSupport {
	/** Utility class. */
	private FulltextSurfaceTestSupport() { }
	/** Builds a real cached analyzer using a discovered provider plus the controlled extension. */
	static void withAnalyzer(TransformingWords provider, Consumer<CompiledFulltextAnalyzer> test) {
		try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(steps(provider))) {
			final CatalogSchemaContract schema = schema(provider);
			test.accept(cache.getCompiledAnalyzer(schema, "extension", Locale.ENGLISH));
		}
	}

	/** Creates a profile containing the extension after ordinary lowercasing. */
	static CatalogSchemaContract schema(TransformingWords provider) {
		return new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
			.withFulltextAnalyzer("extension", a -> a.words(lowercase(), wordsStep(provider.getName()))).toInstance();
	}

	/** Discovers production providers and installs one real test step. */
	static AnalysisStepRegistry steps(TransformingWords provider) {
		final List<AnalysisStepFactory> factories = new ArrayList<>(ServiceLoader.load(AnalysisStepFactory.class)
			.stream().map(ServiceLoader.Provider::get).toList());
		factories.add(provider);
		return new AnalysisStepRegistry(factories);
	}

	/** Reads the cached fragment exactly as the translator does. */
	static String[] fragments(CompiledFulltextAnalyzer analyzer, String word) {
		return FulltextQueryFragment.prepare(analyzer,
			new TextMatchesTranslator.QueryToken(word, new String[]{word}), false);
	}

	/** Expands actual word and surface outputs, including prefix policy, over the indexed value. */
	static List<ExpandedTerm> expand(
		CompiledFulltextAnalyzer analyzer, String query, String indexed, String[] fragments
	) {
		final FulltextIndex index = new FulltextIndex();
		index.addValue(FulltextFieldKey.attribute("title"), 1, analyzer, 25, null, indexed);
		return new FulltextTermExpansion(new FulltextTypoParameters(new FulltextOptions())).expand(index, new int[]{0},
			collectTerms(analyzer.word(), query).stream().map(t -> t.term()).toArray(String[]::new),
			collectTerms(analyzer.surface(), query).stream().map(t -> t.term()).toArray(String[]::new),
			fragments, true);
	}

	/** A provider retaining the SPI's participation default. */
	static class TransformingWords implements FulltextWordsAnalysisStepFactory {
		private final String mode;
		private int creates;
		/** Selects stop, synonym, common-stem or disjoint-stem transformation. */
		TransformingWords(String mode) { this.mode = mode; }
		/** Counts actual operation construction. */
		int createCount() { return this.creates; }
		@Nonnull
		@Override
		public String getName() { return "test-fragment-policy-" + this.mode; }
		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) { }
		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return this.mode.equals("synonym") ? Set.of(FulltextAnalysisSide.QUERY)
				: Set.of(FulltextAnalysisSide.values());
		}
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
			this.creates++;
			return new FulltextWordsAnalysisOperation() {
				@Nonnull
				@Override
				public Set<FulltextAnalysisTrait> getTraits(@Nonnull FulltextAnalysisSide side) {
					return (TransformingWords.this.mode.equals("collapse") ||
						TransformingWords.this.mode.equals("namespace"))
						? Set.of(FulltextAnalysisTrait.STEMS) : Set.of();
				}
				@Nonnull
				@Override
				public TokenStream wrap(@Nonnull TokenStream words, @Nonnull FulltextAnalysisSide side) {
					return new TokenFilter(words) {
						private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
						private final PositionIncrementAttribute increment =
							addAttribute(PositionIncrementAttribute.class);
						private State pending;
						@Override
						public void reset() throws IOException { super.reset(); this.pending = null; }
						@Override
						public boolean incrementToken() throws IOException {
							if (this.pending != null) {
								restoreState(this.pending);
								this.pending = null;
								this.term.setEmpty().append("automobile");
								this.increment.setPositionIncrement(0);
								return true;
							}
							while (this.input.incrementToken()) {
								final String mode = TransformingWords.this.mode;
								if (mode.equals("stop") && this.term.toString().equals("that")) { continue; }
								if (mode.equals("collapse")) { this.term.setEmpty().append("root"); }
								if (mode.equals("namespace")) {
									final String value = this.term.toString();
									this.term.setEmpty().append("w").append(value.substring(4));
								}
								if (mode.equals("synonym") && this.term.toString().equals("car")) {
									this.pending = captureState();
								}
								return true;
							}
							return false;
						}
					};
				}
			};
		}
	}

	/** An extension declaring stop/synonym semantics before operation creation. */
	static final class OmittedFragmentWords extends TransformingWords {
		/** Selects the transformation omitted from fragment preparation. */
		OmittedFragmentWords(String mode) { super(mode); }
		@Override
		public boolean participatesInQueryFragment() { return false; }
	}
}
