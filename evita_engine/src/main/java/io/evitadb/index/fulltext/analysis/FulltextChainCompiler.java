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
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer.StopWordPreparation;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextAnalysisTrait;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisOperation;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTextAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordPreparation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsPlacement;
import io.evitadb.spi.analysis.FulltextWordsPlacement.PlacedStep;
import io.evitadb.utils.CollectionUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.IOUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles step lists into one Lucene analysis chain for one side of the pipeline: the text phase of an analyzer, the
 * word splitting of the language and the words phase of the analyzer, in this order. Each step resolves through the
 * catalog's {@link AnalysisStepRegistry} to its {@link FulltextAnalysisStepFactory}, whose type says the phase it
 * belongs to; a text or words step that does not act on the side being compiled is left out of the chain. The
 * operations are created once per compilation; the compiled `Analyzer` asks them for new Lucene objects whenever
 * Lucene builds the components for a thread.
 *
 * Two things tie a words step to the steps before and after it on the side:
 *
 * - its resources are prepared by them: the context the step is created with runs a word of a resource through the
 *   words steps already created (NFC first, as the text is), so a list written as the user types meets the words
 *   as the chain leaves them;
 * - its place is checked: once every words step of the side exists, each one gets the whole phase with the traits of
 *   its steps and refuses a neighbour it cannot work with.
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
	 * Compiles the chain of one side of a resolved analyzer.
	 *
	 * @param analyzer the analyzer resolved for the locale of the field
	 * @param side     the side the chain is built for
	 * @return the compiled chain; the caller owns it and has to close it
	 * @throws EvitaInvalidUsageException when a step is unknown, has invalid parameters, belongs to another phase,
	 *                                    refuses its place, or the splitting does not start with a tokenizer
	 */
	@Nonnull
	public Analyzer compile(@Nonnull ResolvedFulltextAnalyzer analyzer, @Nonnull FulltextAnalysisSide side) {
		return compile(analyzer.text(), analyzer.splitting(), analyzer.words(), side, analyzer.context());
	}

	/**
	 * Compiles query analysis of one shared term, retaining text and words steps around a keyword tokenizer.
	 * Resource preparation still uses the language tokenizer to check that synonym entries are single words.
	 *
	 * @param analyzer the resolved analyzer of the group
	 * @param stopWords index stop-word sets whose preparations are replaced by the preceding query operations
	 * @return the query word chain owned by the caller
	 */
	@Nonnull
	public Analyzer compileWordChain(
		@Nonnull ResolvedFulltextAnalyzer analyzer, @Nonnull List<StopWordPreparation> stopWords
	) {
		return compile(analyzer.text(), analyzer.splitting(), analyzer.words(), FulltextAnalysisSide.QUERY,
			analyzer.context(), true, stopWords);
	}

	/**
	 * Compiles query analysis of one term the text-aware splitting produced: the words steps around a keyword
	 * tokenizer, without the text steps the term has already been through. Running them again would change the term
	 * a second time unless every text step happened to be idempotent. Stop-word preparations stay those of
	 * {@link #compileWordChain}, which prepare the original surface.
	 *
	 * @param analyzer the resolved analyzer of the group
	 * @return the query word chain owned by the caller
	 */
	@Nonnull
	public Analyzer compileTextSplitWordChain(@Nonnull ResolvedFulltextAnalyzer analyzer) {
		return compile(List.of(), analyzer.splitting(), analyzer.words(), FulltextAnalysisSide.QUERY,
			analyzer.context(), true, null);
	}

	/**
	 * Compiles the unchanged full index chain with a passive token capture immediately before its first stemming
	 * operation. The stemming operation's shared normalization hook prepares only the captured lookup characters;
	 * original terms and all posting attributes remain untouched. Without stemming, a tail marker reuses the final
	 * term. Query-only operations never derive keys. Downstream words operations must retain custom token attributes
	 * through capture/restore for a complete lane; absent or cleared capture degrades to an incomplete lane.
	 *
	 * @param analyzer the resolved field profile
	 * @return the capture-equipped index chain, owned and closed by the registry
	 */
	@Nonnull
	public Analyzer compileIndexSurfaceChain(@Nonnull ResolvedFulltextAnalyzer analyzer) {
		return compile(analyzer.text(), analyzer.splitting(), analyzer.words(), FulltextAnalysisSide.INDEX,
			analyzer.context(), false, null, false, true);
	}

	/**
	 * Compiles surface normalization through the query words phase up to its stemmer, retaining any normalization
	 * the stemmer itself performs. For operations running on both sides, this normalization must equal index-side
	 * pre-stem normalization. This query chain never derives persisted lane keys.
	 *
	 * @param analyzer the resolved field profile
	 * @return the cached surface chain, owned and closed by the registry
	 */
	@Nonnull
	public Analyzer compileSurfaceChain(@Nonnull ResolvedFulltextAnalyzer analyzer) {
		return compile(analyzer.text(), analyzer.splitting(), analyzer.words(), FulltextAnalysisSide.QUERY,
			analyzer.context(), true, null, true, false);
	}

	/**
	 * Selects resolved text steps that act on query input, preserving their declared content and order.
	 *
	 * @param analyzer the definition resolved for this locale
	 * @return immutable query-side text steps, without index-only steps such as html-strip
	 */
	@Nonnull
	List<AnalysisStep> queryText(@Nonnull ResolvedFulltextAnalyzer analyzer) {
		final List<AnalysisStep> selected = new ArrayList<>(analyzer.text().size());
		for (final AnalysisStep step : analyzer.text()) {
			if (resolve(step, FulltextTextAnalysisStepFactory.class, "a text step")
				.getSides().contains(FulltextAnalysisSide.QUERY)) {
				selected.add(step);
			}
		}
		return List.copyOf(selected);
	}

	/**
	 * Compiles the locale's splitting preceded by the supplied query-side text phase, without words steps.
	 *
	 * @param analyzer a resolved analyzer carrying the locale's splitting and the chosen text phase
	 * @return the splitting chain owned by the caller
	 */
	@Nonnull
	public Analyzer compileSplittingChain(@Nonnull ResolvedFulltextAnalyzer analyzer) {
		return compile(analyzer.text(), analyzer.splitting(), List.of(), FulltextAnalysisSide.QUERY,
			analyzer.context());
	}

	/**
	 * Compiles the index chain and collects the prepared words of built-in stop steps for folded query recognition.
	 *
	 * @param analyzer the resolved analyzer
	 * @param stopWords receives per-step folded index words, initially paired with their index preparation
	 * @return the index chain owned by the caller
	 */
	@Nonnull
	Analyzer compile(@Nonnull ResolvedFulltextAnalyzer analyzer, @Nonnull List<StopWordPreparation> stopWords) {
		return compile(analyzer.text(), analyzer.splitting(), analyzer.words(), FulltextAnalysisSide.INDEX,
			analyzer.context(), false, stopWords);
	}

	/**
	 * Computes persistent identities of the index chain and its words phase. Only steps acting on the index side
	 * contribute - the tokenizer and every further splitting step always do - so a step or a parameter acting on the
	 * query side only leaves both values unchanged; the words identity excludes text and splitting. Each stream starts
	 * with a fixed format marker, and every field is tagged and length-prefixed or count-prefixed before XXH3 hashing.
	 *
	 * Parameters contribute in sorted name order, restricted to the factory's index-side declarations. Resource
	 * parameters contribute raw content and kind, never resource names: word counts and words in list order, or
	 * synonym group counts followed by each group's word count and words in order. Preceding steps already encode
	 * preparation, and preserving resource order protects providers that read it in order. Delegated behaviour
	 * contributes its names and versions recursively; a delegate cycle is a provider bug.
	 *
	 * Neither the analyzer's name nor the locale contributes. The locale is the lookup key: a field's stored
	 * fingerprint is always compared with its analyzer's fingerprint in the same locale. What each step derives from
	 * that locale is the same on both sides of the comparison; stemming delegates identify the selected stemmer.
	 *
	 * @param analyzer the definition resolved for the locale of the field
	 * @return the index fingerprint and the words-only identity
	 * @throws EvitaInvalidUsageException when a step is unknown, has invalid parameters, belongs to another phase,
	 *                                    splitting has no tokenizer, or a required resource is missing or of another
	 *                                    kind than its parameter declares
	 * @throws GenericEvitaInternalError when a provider has cyclic delegates or an unsupported parameter value
	 */
	@Nonnull
	public FulltextAnalyzerFingerprint fingerprint(@Nonnull ResolvedFulltextAnalyzer analyzer) {
		final FulltextFingerprintEncoder index = new FulltextFingerprintEncoder("evitaDB-fulltext-fingerprint-1");
		final FulltextFingerprintEncoder words = new FulltextFingerprintEncoder("evitaDB-fulltext-word-phase-1");
		final FulltextAnalysisContext context = analyzer.context();
		final List<AnalysisStep> splitting = analyzer.splitting();
		fingerprintPhase("text", analyzer.text(), FulltextTextAnalysisStepFactory.class, "a text step", context, index);
		if (splitting.isEmpty()) {
			throw new EvitaInvalidUsageException("Word splitting must start with a tokenizer.");
		}
		index.count("splitting", splitting.size());
		final AnalysisStep tokenizer = splitting.get(0);
		index.step(tokenizer, resolve(tokenizer, FulltextTokenizerAnalysisStepFactory.class, "the tokenizer"), context);
		for (final AnalysisStep step : splitting.subList(1, splitting.size())) {
			index.step(step, resolve(step, FulltextSplittingAnalysisStepFactory.class, "a further splitting step"),
				context);
		}
		fingerprintPhase("words", analyzer.words(), FulltextWordsAnalysisStepFactory.class, "a words step",
			context, index, words);
		return new FulltextAnalyzerFingerprint(index.hash(), words.hash());
	}

	/**
	 * Resolves a text or words phase and encodes exactly the steps whose factory's sides contain the index side. Every
	 * step is resolved and checked, selected or not; the selection is written alike to each given stream, which is how
	 * the words phase reaches both the index and the words-phase identity.
	 *
	 * @param tag the stable phase identifier, written as the count field of each stream
	 * @param steps the steps in execution order
	 * @param expected the factory type belonging to the phase
	 * @param role the phase's name for validation errors
	 * @param context the locale and resources used by the steps
	 * @param encoders the canonical streams receiving the same selected steps
	 * @param <T> the factory type checked by the same resolver as compilation
	 */
	private <T extends FulltextAnalysisStepFactory> void fingerprintPhase(
		@Nonnull String tag,
		@Nonnull List<AnalysisStep> steps,
		@Nonnull Class<T> expected,
		@Nonnull String role,
		@Nonnull FulltextAnalysisContext context,
		@Nonnull FulltextFingerprintEncoder... encoders
	) {
		final List<AnalysisStep> selectedSteps = new ArrayList<>(steps.size());
		final List<T> selectedFactories = new ArrayList<>(steps.size());
		for (final AnalysisStep step : steps) {
			final T factory = resolve(step, expected, role);
			final boolean selected = switch (factory) {
				case FulltextTextAnalysisStepFactory text -> text.getSides().contains(FulltextAnalysisSide.INDEX);
				case FulltextWordsAnalysisStepFactory words -> words.getSides().contains(FulltextAnalysisSide.INDEX);
				default -> throw new GenericEvitaInternalError("Only text and words phases select an analysis side.");
			};
			if (selected) {
				selectedSteps.add(step);
				selectedFactories.add(factory);
			}
		}
		for (final FulltextFingerprintEncoder encoder : encoders) {
			encoder.count(tag, selectedSteps.size());
			for (int i = 0; i < selectedSteps.size(); i++) {
				encoder.step(selectedSteps.get(i), selectedFactories.get(i), context);
			}
		}
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
	 * @throws EvitaInvalidUsageException when a step is unknown, has invalid parameters, belongs to another phase,
	 *                                    refuses its place, or the splitting does not start with a tokenizer
	 */
	@Nonnull
	public Analyzer compile(
		@Nonnull List<AnalysisStep> text,
		@Nonnull List<AnalysisStep> splitting,
		@Nonnull List<AnalysisStep> words,
		@Nonnull FulltextAnalysisSide side,
		@Nonnull FulltextAnalysisContext context
	) {
		return compile(text, splitting, words, side, context, false, null);
	}

	/**
	 * Builds a full chain or a query word chain using the same step resolution and preparation.
	 *
	 * @param text the text steps
	 * @param splitting the locale's tokenizer and splitting steps
	 * @param words the words steps
	 * @param side the side being compiled
	 * @param context the locale and available resources
	 * @param keyword whether the emitted chain tokenizes each input as one term
	 * @param stopWords optional collector of index stop sets and their corresponding query preparations
	 * @return the compiled chain owned by the caller
	 */
	@Nonnull
	private Analyzer compile(
		@Nonnull List<AnalysisStep> text, @Nonnull List<AnalysisStep> splitting,
		@Nonnull List<AnalysisStep> words, @Nonnull FulltextAnalysisSide side,
		@Nonnull FulltextAnalysisContext context, boolean keyword, @Nullable List<StopWordPreparation> stopWords
	) {
		return compile(text, splitting, words, side, context, keyword, stopWords, false, false);
	}

	/**
	 * Builds a complete chain or the query surface prefix using the same resolved operations.
	 *
	 * @param text text operations
	 * @param splitting language splitting
	 * @param words words operations
	 * @param side compilation side
	 * @param context resources and locale
	 * @param keyword whether to use one input token
	 * @param stopWords optional stop-word preparation collector
	 * @param surfaceOnly whether to stop before query stemming
	 * @param captureIndex whether to carry the actual index pre-stem token through the full chain
	 * @return the owned compiled chain
	 */
	@Nonnull
	private Analyzer compile(
		@Nonnull List<AnalysisStep> text, @Nonnull List<AnalysisStep> splitting,
		@Nonnull List<AnalysisStep> words, @Nonnull FulltextAnalysisSide side,
		@Nonnull FulltextAnalysisContext context, boolean keyword,
		@Nullable List<StopWordPreparation> stopWords, boolean surfaceOnly, boolean captureIndex
	) {
		final List<FulltextTextAnalysisOperation> textOperations = new ArrayList<>(text.size());
		for (final AnalysisStep step : text) {
			final FulltextTextAnalysisStepFactory factory = resolve(
				step, FulltextTextAnalysisStepFactory.class, "a text step"
			);
			if (factory.getSides().contains(side)) {
				textOperations.add(factory.create(step.parameters(), stepContext(step, factory, context)));
			}
		}
		if (splitting.isEmpty()) {
			throw new EvitaInvalidUsageException("Word splitting must start with a tokenizer.");
		}
		final AnalysisStep tokenizerStep = splitting.get(0);
		final FulltextTokenizerAnalysisStepFactory tokenizerFactory = resolve(
			tokenizerStep, FulltextTokenizerAnalysisStepFactory.class, "the tokenizer"
		);
		final FulltextTokenizerAnalysisOperation tokenizer = tokenizerFactory.create(
			tokenizerStep.parameters(), stepContext(tokenizerStep, tokenizerFactory, context)
		);
		final List<FulltextSplittingAnalysisOperation> splittingOperations = new ArrayList<>(splitting.size() - 1);
		for (final AnalysisStep step : splitting.subList(1, splitting.size())) {
			final FulltextSplittingAnalysisStepFactory factory = resolve(
				step, FulltextSplittingAnalysisStepFactory.class, "a further splitting step"
			);
			splittingOperations.add(factory.create(step.parameters(), stepContext(step, factory, context)));
		}
		final List<FulltextWordsAnalysisOperation> wordOperations = new ArrayList<>(words.size());
		final List<PlacedStep> placedSteps = new ArrayList<>(words.size());
		int stopWordIndex = 0;
		int captureAt = -1;
		FulltextWordsAnalysisOperation captureNormalization = null;
		for (final AnalysisStep step : words) {
			final FulltextWordsAnalysisStepFactory factory = resolve(
				step, FulltextWordsAnalysisStepFactory.class, "a words step"
			);
			if (factory.getSides().contains(side)) {
				final FulltextWordPreparation preparation = new PrecedingStepsPreparation(
					new FulltextTextAnalysisOperation[0],
					wordOperations.toArray(FulltextWordsAnalysisOperation[]::new), tokenizer, side
				);
				final FulltextAnalysisContext preparedContext =
					stepContext(step, factory, context).withPreparation(preparation);
				final FulltextWordsAnalysisOperation operation = factory.create(step.parameters(), preparedContext);
				if (captureIndex && captureAt < 0 && operation.getTraits(side).contains(FulltextAnalysisTrait.STEMS)) {
					captureAt = wordOperations.size();
					captureNormalization = operation;
				}
				if (surfaceOnly && operation.getTraits(side).contains(FulltextAnalysisTrait.STEMS)) {
					wordOperations.add((stream, ignoredSide) -> operation.wrapQuerySurface(stream));
					break;
				}

				if (stopWords != null && factory instanceof StopWordsAnalysisStepFactory) {
					if (side == FulltextAnalysisSide.INDEX) {
						stopWords.add(new StopWordPreparation(foldStopWords(step, preparedContext), preparation));
					} else {
						// Built-in stop steps act on both sides; pair each with exactly its own index stop set.
						final StopWordPreparation indexed = stopWords.get(stopWordIndex);
						stopWords.set(stopWordIndex++, new StopWordPreparation(indexed.foldedWords(),
							new PrecedingStepsPreparation(textOperations.toArray(FulltextTextAnalysisOperation[]::new),
								wordOperations.toArray(FulltextWordsAnalysisOperation[]::new), tokenizer, side)));
					}
				}
				wordOperations.add(operation);
				placedSteps.add(new PlacedStep(step.name(), operation.getTraits(side)));
			}
		}
		if (!surfaceOnly) {
			verifyPlacements(wordOperations, List.copyOf(placedSteps), side);
		}
		return new CompiledChain(
			textOperations.toArray(FulltextTextAnalysisOperation[]::new),
			keyword ? KeywordTokenizer::new : tokenizer,
			keyword ? new FulltextSplittingAnalysisOperation[0] :
				splittingOperations.toArray(FulltextSplittingAnalysisOperation[]::new),
			wordOperations.toArray(FulltextWordsAnalysisOperation[]::new),
			side, captureIndex && captureAt < 0 ? wordOperations.size() : captureAt, captureNormalization
		);
	}

	/**
	 * Folds the words a stop step removes on the index side, as its preparation leaves them, so that a query word can
	 * be recognized as one of them by {@link CompiledFulltextAnalyzer#isFoldedStopWord(String)}.
	 *
	 * @param step            the stop step, naming its word resources
	 * @param preparedContext the step's context, preparing its resource words by the preceding index steps
	 * @return the prepared stop words, lower-cased and ASCII-folded
	 * @throws EvitaInvalidUsageException when the step does not name its word resources as a `String[]`
	 */
	@Nonnull
	private static Set<String> foldStopWords(
		@Nonnull AnalysisStep step, @Nonnull FulltextAnalysisContext preparedContext
	) {
		if (!(step.parameters().get(AnalysisSteps.WORDS) instanceof String[] resources)) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + step.name() + "` requires a String[] of word resource names.");
		}
		final Set<String> folded = CollectionUtils.createHashSet(64);
		for (final String name : resources) {
			for (final String word : preparedContext.getPreparedWords(name)) {
				folded.add(CompiledFulltextAnalyzer.foldStopWord(word));
			}
		}
		return folded;
	}

	/**
	 * Restricts a step to resources explicitly named by its own declared resource parameters, on either side.
	 *
	 * @param step the definition holding explicit parameter values
	 * @param factory the validated factory declaring resource kinds
	 * @param context the locale, available resources and word preparation
	 * @return the context exposing only resources this step may read
	 */
	@Nonnull
	private static FulltextAnalysisContext stepContext(
		@Nonnull AnalysisStep step, @Nonnull FulltextAnalysisStepFactory factory,
		@Nonnull FulltextAnalysisContext context
	) {
		final Set<String> names = CollectionUtils.createHashSet(factory.getParameters().size());
		final Map<String, Serializable> parameters = step.parameters();
		for (final AnalysisStepParameterDescriptor descriptor : factory.getParameters()) {
			if (descriptor.resourceKind() != null) {
				final Serializable value = parameters.get(descriptor.name());
				if (value instanceof String name) {
					names.add(name);
				} else if (value instanceof String[] resources) {
					for (final String name : resources) {
						names.add(name);
					}
				}
			}
		}
		return context.withResources(names);
	}

	/**
	 * Lets every words step of the side check its place among the others.
	 *
	 * @param operations  the operations of the words steps acting on the side, in order
	 * @param placedSteps the same steps with their traits on the side, in the same order - immutable, so that every
	 *                    placement shares it instead of copying it
	 * @param side        the side the chain is built for
	 * @throws EvitaInvalidUsageException when a step refuses its place
	 */
	private static void verifyPlacements(
		@Nonnull List<FulltextWordsAnalysisOperation> operations,
		@Nonnull List<PlacedStep> placedSteps,
		@Nonnull FulltextAnalysisSide side
	) {
		for (int i = 0; i < operations.size(); i++) {
			operations.get(i).verifyPlacement(new FulltextWordsPlacement(side, placedSteps, i));
		}
	}

	/**
	 * Resolves a step and checks that its factory belongs to the expected phase.
	 *
	 * @param step     the step
	 * @param expected the factory type of the expected phase
	 * @param role     the place the step was put in, as a user reads it in the error, such as `the tokenizer` or
	 *                 `a words step`
	 * @param <T>      the factory type of the expected phase
	 * @return the factory
	 * @throws EvitaInvalidUsageException when the step is unknown, has invalid parameters or belongs elsewhere
	 */
	@Nonnull
	private <T extends FulltextAnalysisStepFactory> T resolve(
		@Nonnull AnalysisStep step,
		@Nonnull Class<T> expected,
		@Nonnull String role
	) {
		final FulltextAnalysisStepFactory factory = this.registry.resolveFulltext(step);
		if (!expected.isInstance(factory)) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + step.name() + "` is " + describe(factory) + ", it cannot serve as " + role + "."
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
	 * Prepares a word of a resource for a words step: normalizes it to NFC, as {@link FulltextAnalyzer} normalizes the
	 * text, and runs it as one token through the words steps before that step on the side. The text phase and the word
	 * splitting are left out for resources: the word is compared as it reaches the step, not with a text. The
	 * synonyms a preceding step adds (Lucene's `SYNONYM` token type) are left out too: they are other words, not forms
	 * of this one. So is a term a preceding step leaves empty. Whether the word reaches the step whole is the
	 * tokenizer's call alone, asked over the same NFC form.
	 *
	 * The same preparation serves a query surface compared with a stop step's words; for it, the caller also supplies
	 * the query text operations preceding the stop step.
	 */
	private static final class PrecedingStepsPreparation implements FulltextWordPreparation {
		/**
		 * Optional query text operations preparing a surface; resource words leave this empty.
		 */
		@Nonnull private final FulltextTextAnalysisOperation[] text;
		/**
		 * The operations of the preceding words steps on the side, in order.
		 */
		@Nonnull private final FulltextWordsAnalysisOperation[] operations;
		/**
		 * The tokenizer of the side, deciding which words reach the words phase whole.
		 */
		@Nonnull private final FulltextTokenizerAnalysisOperation tokenizer;
		/**
		 * The side the chain is built for.
		 */
		@Nonnull private final FulltextAnalysisSide side;

		/**
		 * Creates the preparation of the next words step.
		 *
		 * @param text       query text operations for a surface, empty when preparing resource words
		 * @param operations the operations of the preceding words steps on the side, in order
		 * @param tokenizer  the tokenizer of the side, deciding which words reach the words phase whole
		 * @param side       the side the chain is built for
		 */
		PrecedingStepsPreparation(
			@Nonnull FulltextTextAnalysisOperation[] text,
			@Nonnull FulltextWordsAnalysisOperation[] operations,
			@Nonnull FulltextTokenizerAnalysisOperation tokenizer,
			@Nonnull FulltextAnalysisSide side
		) {
			this.text = text;
			this.operations = operations;
			this.tokenizer = tokenizer;
			this.side = side;
		}

		@Override
		public boolean isSingleWord(@Nonnull String word) {
			try (final Tokenizer source = this.tokenizer.createTokenizer()) {
				source.setReader(new StringReader(Normalizer.normalize(word, Normalizer.Form.NFC)));
				source.reset();
				int tokens = 0;
				while (source.incrementToken()) {
					tokens++;
				}
				source.end();
				// the count, not the token: `'s` is one word to the standard tokenizer, which emits it as `s`
				return tokens == 1;
			} catch (IOException e) {
				throw new GenericEvitaInternalError(
					"Splitting the word `" + word + "` failed: " + e.getMessage(), "Splitting a word failed.", e
				);
			}
		}

		@Nonnull
		@Override
		public List<String> prepare(@Nonnull String word) {
			final String normalized = Normalizer.normalize(word, Normalizer.Form.NFC);
			if (this.text.length == 0 && this.operations.length == 0) {
				return List.of(normalized);
			}
			final Tokenizer source = new KeywordTokenizer();
			TokenStream chain = source;
			try {
				Reader reader = new StringReader(normalized);
				for (final FulltextTextAnalysisOperation operation : this.text) {
					reader = operation.wrap(reader, this.side);
				}
				source.setReader(reader);
				for (final FulltextWordsAnalysisOperation operation : this.operations) {
					chain = operation.wrap(chain, this.side);
				}
			} catch (RuntimeException e) {
				// the streams built so far are closed through the last of them, which closes its input
				IOUtils.closeWhileHandlingException(chain);
				throw e;
			}
			try (final TokenStream stream = chain) {
				final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
				final TypeAttribute type = stream.addAttribute(TypeAttribute.class);
				stream.reset();
				final List<String> prepared = new ArrayList<>(2);
				while (stream.incrementToken()) {
					// a synonym is another word, not a form of this one - a stop word does not stop its synonyms;
					// and a term a step emptied, such as `'s` after `english-possessive`, is no word at all
					if (term.length() > 0 && !SynonymGraphFilter.TYPE_SYNONYM.equals(type.type())) {
						prepared.add(term.toString());
					}
				}
				stream.end();
				return prepared;
			} catch (IOException e) {
				throw new GenericEvitaInternalError(
					"Preparing the word `" + word + "` failed: " + e.getMessage(), "Preparing a word failed.", e
				);
			}
		}
	}

	/**
	 * The compiled chain: the operations of the phases that act on its side, asked for new Lucene objects per thread.
	 * Unlike the built-in chains, it does not override `Analyzer#normalize`: a single term normalized through it comes
	 * back unchanged.
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
		/** Words-operation boundary at which to capture, or minus one for an ordinary chain. */
		private final int captureAt;
		/** Shared stemming normalization for captured lookup text, null for tail or ordinary chains. */
		@Nullable private final FulltextWordsAnalysisOperation captureNormalization;

		/**
		 * Creates the chain over checked operations.
		 *
		 * @param text      the operations of the text phase acting on `side`
		 * @param tokenizer the tokenizer
		 * @param splitting the further splitting steps
		 * @param words     the operations of the words phase acting on `side`
		 * @param side      the side the chain is built for
		 * @param captureAt capture boundary, or minus one to disable capture
		 * @param captureNormalization normalization embedded in the first stemming operation, or null
		 */
		CompiledChain(
			@Nonnull FulltextTextAnalysisOperation[] text,
			@Nonnull FulltextTokenizerAnalysisOperation tokenizer,
			@Nonnull FulltextSplittingAnalysisOperation[] splitting,
			@Nonnull FulltextWordsAnalysisOperation[] words,
			@Nonnull FulltextAnalysisSide side, int captureAt,
			@Nullable FulltextWordsAnalysisOperation captureNormalization
		) {
			this.captureAt = captureAt;
			this.captureNormalization = captureNormalization;
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
			for (int i = 0; i <= this.words.length; i++) {
				if (i == this.captureAt) {
					stream = new PreStemCaptureFilter(stream, this.captureNormalization);
				}
				if (i < this.words.length) {
					stream = this.words[i].wrap(stream, this.side);
				}
			}
			return new TokenStreamComponents(source, stream);
		}
	}

	/** Captures token identity without modifying terms, positions, offsets or token order. */
	private static final class PreStemCaptureFilter extends TokenFilter {
		/** Shared current term attribute. */
		@Nonnull private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
		/** Shared captured pre-stem term. */
		@Nonnull private final FulltextPreStemAttribute captured;
		/** Per-component normalization stream; null marks reuse of the final term. */
		@Nullable private final SurfaceNormalizer normalization;

		/**
		 * Installs the implementation explicitly so JPMS need not expose this internal attribute to Lucene reflection.
		 * @param input the unchanged index stream entering stemming
		 * @param operation stemming operation supplying shared lookup normalization, null for a tail capture
		 */
		private PreStemCaptureFilter(@Nonnull TokenStream input, @Nullable FulltextWordsAnalysisOperation operation) {
			super(input);
			addAttributeImpl(new FulltextPreStemAttributeImpl());
			this.captured = getAttribute(FulltextPreStemAttribute.class);
			this.normalization = operation == null ? null : new SurfaceNormalizer(operation);
		}

		@Override
		public boolean incrementToken() throws IOException {
			if (!this.input.incrementToken()) {
				return false;
			}
			if (this.normalization == null) {
				this.captured.useFinalTerm();
			} else {
				this.normalization.capture(this.term.buffer(), this.term.length(), this.captured);
			}
			return true;
		}

		@Override
		public void close() throws IOException {
			IOUtils.close(this.input, this.normalization);
		}
	}

	/** Runs the same embedded normalization as query preparation on an independent reusable token buffer. */
	private static final class SurfaceNormalizer implements Closeable {
		/** Single borrowed word input, independent of the posting stream's attributes. */
		@Nonnull private final SurfaceNormalizationInput input = new SurfaceNormalizationInput();
		/** Normalization hook, built once with the cached analyzer's thread-local components. */
		@Nonnull private final TokenStream output;
		/** Current normalized characters. */
		@Nonnull private final CharTermAttribute term;

		/**
		 * Creates only the normalization owned by this index-fingerprinted stemming operation.
		 * @param operation the first index stemming operation
		 */
		private SurfaceNormalizer(@Nonnull FulltextWordsAnalysisOperation operation) {
			this.output = operation.wrapQuerySurface(this.input);
			this.term = this.output.addAttribute(CharTermAttribute.class);
		}

		/**
		 * Captures one normalized lookup word. A hook dropping or expanding a word leaves capture unavailable,
		 * preserving postings and avoiding an invented association.
		 * @param source original pre-stem characters
		 * @param length valid source characters
		 * @param target reusable capture attribute
		 * @throws IOException when a normalization filter fails
		 */
		private void capture(@Nonnull char[] source, int length, @Nonnull FulltextPreStemAttribute target)
			throws IOException {
			if (this.output == this.input) {
				target.copyBuffer(source, 0, length);
				return;
			}
			this.output.reset();
			this.input.setToken(source, length);
			int count = 0;
			while (this.output.incrementToken()) {
				if (++count == 1) {
					target.copyBuffer(this.term.buffer(), 0, this.term.length());
				}
			}
			this.output.end();
			if (count != 1) {
				target.copyBuffer(source, 0, 0);
			}
		}

		@Override
		public void close() throws IOException {
			this.output.close();
		}
	}

	/** Feeds a single pre-stem word into the cached embedded normalization without strings or a tokenizer. */
	private static final class SurfaceNormalizationInput extends TokenStream {
		/** Mutable source characters reused for every capture. */
		@Nonnull private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
		/** Whether the current word has yet to be emitted. */
		private boolean pending;

		/** Creates an empty normalization input. */
		private SurfaceNormalizationInput() {
		}

		/**
		 * Prepares one word after the normalization stream has been reset.
		 * @param source pre-stem input characters
		 * @param length valid source characters
		 */
		private void setToken(@Nonnull char[] source, int length) {
			clearAttributes();
			this.term.copyBuffer(source, 0, length);
			this.pending = true;
		}

		@Override
		public boolean incrementToken() {
			if (!this.pending) {
				return false;
			}
			this.pending = false;
			return true;
		}
	}

}
