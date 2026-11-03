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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisOperation;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTextAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import javax.annotation.Nonnull;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles step lists into one Lucene analysis chain for one side of the pipeline: the text phase of an analyzer, the
 * word splitting of the language and the words phase of the analyzer, in this order. Each step resolves through the
 * catalog's {@link AnalysisStepRegistry} to its {@link FulltextAnalysisStepFactory}, whose type says the phase it
 * belongs to; a text or words step that does not act on the side being compiled is left out of the chain. The
 * operations are created once per compilation; the compiled `Analyzer` asks them for new Lucene objects whenever
 * Lucene builds the components for a thread.
 */
public final class FulltextChainCompiler {
	/**
	 * The registry the steps resolve through.
	 */
	@Nonnull private final AnalysisStepRegistry registry;

	/**
	 * Creates a compiler resolving steps through the catalog's registry.
	 *
	 * @param registry the catalog's analysis step registry
	 */
	public FulltextChainCompiler(@Nonnull AnalysisStepRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Compiles the chain of one side.
	 *
	 * @param text      the steps of the analyzer's text phase, possibly empty
	 * @param splitting the word splitting of the language: the tokenizer, then any further splitting steps
	 * @param words     the steps of the analyzer's words phase, possibly empty
	 * @param side      the side the chain is built for
	 * @param context   the locale and the resources the steps' parameters may name
	 * @return the compiled chain; the caller owns it and has to close it
	 * @throws EvitaInvalidUsageException when a step is unknown, has invalid parameters, belongs to another phase, or
	 *                                    the splitting does not start with a tokenizer
	 */
	@Nonnull
	public Analyzer compile(
		@Nonnull List<AnalysisStep> text,
		@Nonnull List<AnalysisStep> splitting,
		@Nonnull List<AnalysisStep> words,
		@Nonnull FulltextAnalysisSide side,
		@Nonnull FulltextAnalysisContext context
	) {
		final List<FulltextTextAnalysisOperation> textOperations = new ArrayList<>(text.size());
		for (final AnalysisStep step : text) {
			final FulltextTextAnalysisStepFactory factory = resolve(step, FulltextTextAnalysisStepFactory.class, "text");
			if (factory.getSides().contains(side)) {
				textOperations.add(factory.create(step.parameters(), context));
			}
		}
		if (splitting.isEmpty()) {
			throw new EvitaInvalidUsageException("Word splitting must start with a tokenizer.");
		}
		final FulltextTokenizerAnalysisOperation tokenizer = resolve(
			splitting.get(0), FulltextTokenizerAnalysisStepFactory.class, "tokenizer"
		).create(splitting.get(0).parameters(), context);
		final List<FulltextSplittingAnalysisOperation> splittingOperations = new ArrayList<>(splitting.size() - 1);
		for (final AnalysisStep step : splitting.subList(1, splitting.size())) {
			splittingOperations.add(
				resolve(step, FulltextSplittingAnalysisStepFactory.class, "further splitting").create(
					step.parameters(), context
				)
			);
		}
		final List<FulltextWordsAnalysisOperation> wordOperations = new ArrayList<>(words.size());
		for (final AnalysisStep step : words) {
			final FulltextWordsAnalysisStepFactory factory = resolve(step, FulltextWordsAnalysisStepFactory.class, "words");
			if (factory.getSides().contains(side)) {
				wordOperations.add(factory.create(step.parameters(), context));
			}
		}
		return new CompiledChain(
			textOperations.toArray(FulltextTextAnalysisOperation[]::new),
			tokenizer,
			splittingOperations.toArray(FulltextSplittingAnalysisOperation[]::new),
			wordOperations.toArray(FulltextWordsAnalysisOperation[]::new),
			side
		);
	}

	/**
	 * Resolves a step and checks that its factory belongs to the expected phase.
	 *
	 * @param step     the step
	 * @param expected the factory type of the expected phase
	 * @param position the expected phase or position, for the error
	 * @param <T>      the factory type of the expected phase
	 * @return the factory
	 * @throws EvitaInvalidUsageException when the step is unknown, has invalid parameters or belongs elsewhere
	 */
	@Nonnull
	private <T extends FulltextAnalysisStepFactory> T resolve(
		@Nonnull AnalysisStep step,
		@Nonnull Class<T> expected,
		@Nonnull String position
	) {
		final FulltextAnalysisStepFactory factory = this.registry.resolveFulltext(step);
		if (!expected.isInstance(factory)) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + step.name() + "` is " + describe(factory) + ", it cannot serve as " +
					("tokenizer".equals(position) ? "the tokenizer" : "a " + position + " step") + "."
			);
		}
		return expected.cast(factory);
	}

	/**
	 * Names the phase a factory belongs to, for errors.
	 *
	 * @param factory the factory
	 * @return the phase, as a user reads it
	 */
	@Nonnull
	private static String describe(@Nonnull FulltextAnalysisStepFactory factory) {
		return switch (factory) {
			case FulltextTextAnalysisStepFactory ignored -> "a text step";
			case FulltextTokenizerAnalysisStepFactory ignored -> "a tokenizer";
			case FulltextSplittingAnalysisStepFactory ignored -> "a further splitting step";
			case FulltextWordsAnalysisStepFactory ignored -> "a words step";
		};
	}

	/**
	 * The compiled chain: the operations of the phases that act on its side, asked for new Lucene objects per thread.
	 */
	private static final class CompiledChain extends Analyzer {
		/**
		 * The operations of the text phase, in execution order.
		 */
		@Nonnull private final FulltextTextAnalysisOperation[] text;
		/**
		 * The tokenizer.
		 */
		@Nonnull private final FulltextTokenizerAnalysisOperation tokenizer;
		/**
		 * The further splitting steps, in execution order.
		 */
		@Nonnull private final FulltextSplittingAnalysisOperation[] splitting;
		/**
		 * The operations of the words phase, in execution order.
		 */
		@Nonnull private final FulltextWordsAnalysisOperation[] words;
		/**
		 * The side the chain is built for.
		 */
		@Nonnull private final FulltextAnalysisSide side;

		/**
		 * Creates the chain over checked operations.
		 *
		 * @param text      the operations of the text phase acting on `side`
		 * @param tokenizer the tokenizer
		 * @param splitting the further splitting steps
		 * @param words     the operations of the words phase acting on `side`
		 * @param side      the side the chain is built for
		 */
		CompiledChain(
			@Nonnull FulltextTextAnalysisOperation[] text,
			@Nonnull FulltextTokenizerAnalysisOperation tokenizer,
			@Nonnull FulltextSplittingAnalysisOperation[] splitting,
			@Nonnull FulltextWordsAnalysisOperation[] words,
			@Nonnull FulltextAnalysisSide side
		) {
			this.text = text;
			this.tokenizer = tokenizer;
			this.splitting = splitting;
			this.words = words;
			this.side = side;
		}

		@Override
		protected Reader initReader(String fieldName, Reader reader) {
			Reader wrapped = reader;
			for (final FulltextTextAnalysisOperation operation : this.text) {
				wrapped = operation.wrap(wrapped, this.side);
			}
			return wrapped;
		}

		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			final Tokenizer source = this.tokenizer.createTokenizer();
			TokenStream stream = source;
			for (final FulltextSplittingAnalysisOperation operation : this.splitting) {
				stream = operation.wrap(stream);
			}
			for (final FulltextWordsAnalysisOperation operation : this.words) {
				stream = operation.wrap(stream, this.side);
			}
			return new TokenStreamComponents(source, stream);
		}
	}
}
