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
import io.evitadb.index.fulltext.analysis.BuiltInAnalyzers.BuiltInAnalyzer;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single point through which the engine reaches a full-text analyzer. One instance per catalog.
 *
 * Resolution for a (collection, locale) pair runs in two steps that are worth keeping apart. First the built-in
 * language table ({@link BuiltInAnalyzers}) names the analyzer of the locale's **language** for the side asked for
 * - the index chain for stored values, the `*-search` chain for query text. Second, the name is translated into an
 * instance. Instances are therefore keyed by **name alone** — the locale decides *which* analyzer is used, never
 * *which copy of it*, so ten collections indexing Czech share one chain instead of building ten identical ones.
 * Laziness is not micro-optimization: building a chain loads its data (Czech and Polish each read a stop-word list
 * from a jar resource, and every Snowball chain constructs its stemmer's ending tables), while sharing one is free
 * because a Lucene `Analyzer` is thread-safe.
 *
 * **{@link #close()} is mandatory when the catalog goes away.** A Lucene `Analyzer` retains its per-thread stream
 * components in a `CloseableThreadLocal` whose `hardRefs` map holds them for the lifetime of the process
 * otherwise. After closing, every accessor of this registry fails rather than handing out an analyzer whose
 * components were already released.
 *
 * Closing races with a lookup that is already in flight, so {@link #getAnalyzerInstance(String, Supplier)} checks
 * the flag **twice**: once before building a chain, and once after the instance has been published into
 * {@link #instances}. Only the second check is load-bearing — an entry published after `close()` has walked
 * past its key would otherwise be owned by nobody and closed by no one. **A successful removal from
 * {@link #instances} is what confers ownership**, in both directions: `close()` closes only what it managed to
 * remove, and a late lookup closes only what it managed to remove afterwards, so every instance is released
 * exactly once no matter which of the two wins.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextAnalyzerRegistry implements Closeable {

	/**
	 * Translates an analyzer name into the declaration its chain is built from, null for an unknown name.
	 */
	@Nonnull private final Function<String, BuiltInAnalyzer> definitions;
	/**
	 * Lazily created analyzer instances, keyed by analyzer name.
	 */
	@Nonnull private final Map<String, FulltextAnalyzer> instances = new ConcurrentHashMap<>(16);
	/**
	 * Set by {@link #close()}; every accessor checks it so that a released analyzer can never be handed out.
	 */
	private volatile boolean closed;

	/**
	 * Creates a registry over the built-in analyzers.
	 */
	public FulltextAnalyzerRegistry() {
		this(BuiltInAnalyzers::definitionFor);
	}

	/**
	 * Creates a registry translating names through `definitions` - the built-in table in production, a table holding
	 * a chain whose construction a test controls in the tests of the closing race.
	 *
	 * @param definitions translates an analyzer name into its declaration, null for an unknown name
	 */
	FulltextAnalyzerRegistry(@Nonnull Function<String, BuiltInAnalyzer> definitions) {
		this.definitions = definitions;
	}

	/**
	 * Returns the analyzer to be used while indexing values of `entityType` written in `locale`.
	 *
	 * @param entityType entity collection the analysed value belongs to; the built-in choice depends on the locale
	 *                   alone, the collection only names the request in an error
	 * @param locale     locale of the analysed value
	 * @return shared analyzer instance for the index side
	 * @throws EvitaInvalidUsageException when the registry is closed
	 */
	@Nonnull
	public FulltextAnalyzer getIndexAnalyzer(@Nonnull String entityType, @Nonnull Locale locale) {
		assertNotClosed();
		final String name = BuiltInAnalyzers.assignmentForLocale(locale).indexAnalyzer();
		return getAnalyzerInstance(name, () -> unknownForRequest(name, entityType, locale));
	}

	/**
	 * Returns the analyzer to be used for the text of a query against `entityType` in `locale`.
	 *
	 * The query chain differs from the indexing one by the markup stripping for every language — a query text is
	 * what a user typed. Czech, Slovak, Polish and Romanian differ further: their query chain folds diacritics away
	 * and then emits every stem the word could have had, precisely so that the two sides *do* meet for a query typed
	 * without accents. See {@link BuiltInAnalyzers}.
	 *
	 * @param entityType entity collection being queried; the built-in choice depends on the locale alone, the
	 *                   collection only names the request in an error
	 * @param locale     locale of the query text
	 * @return shared analyzer instance for the query side
	 * @throws EvitaInvalidUsageException when the registry is closed
	 */
	@Nonnull
	public FulltextAnalyzer getSearchAnalyzer(@Nonnull String entityType, @Nonnull Locale locale) {
		assertNotClosed();
		final String name = BuiltInAnalyzers.assignmentForLocale(locale).searchAnalyzer();
		return getAnalyzerInstance(name, () -> unknownForRequest(name, entityType, locale));
	}

	/**
	 * Returns the analyzer of the passed name - the lookup that reads a persisted index back with the analyzer its
	 * terms were produced by, whatever the language table says now.
	 *
	 * @param analyzerName name of a built-in analyzer
	 * @return shared analyzer instance
	 * @throws EvitaInvalidUsageException when the name is not a built-in analyzer, or the registry is closed
	 */
	@Nonnull
	public FulltextAnalyzer getIndexAnalyzerByName(@Nonnull String analyzerName) {
		assertNotClosed();
		return getAnalyzerInstance(
			analyzerName,
			() -> "Analyzer `" + analyzerName + "` a persisted fulltext index was built with is not a built-in " +
				"analyzer."
		);
	}

	/**
	 * Returns the stop words the analyzer of the passed name drops, lower-cased and folded to ASCII - the very set its
	 * stop filter reads, or an empty set for a chain without a stop filter.
	 *
	 * The query side needs this because the stop filters of several built-in index chains run on the accented text
	 * and hold accented forms only: the index drops `není`, while the query word `neni` passes the search chain
	 * untouched. Folding both sides recognizes the word as one the index dropped - see
	 * {@link #isFoldedStopWord(String, String)}.
	 *
	 * @param analyzerName name of the analyzer, typically the one a fulltext index was built with
	 * @return the folded stop words, unmodifiable, never null
	 * @throws EvitaInvalidUsageException when the registry is closed
	 */
	@Nonnull
	public Set<String> getFoldedStopWords(@Nonnull String analyzerName) {
		assertNotClosed();
		return BuiltInAnalyzers.foldedStopWordsFor(analyzerName);
	}

	/**
	 * Returns whether a word, lower-cased and folded to ASCII, is a stop word of the analyzer of the passed name -
	 * a word that analyzer drops in some spelling, so that its absence from an index the analyzer built says
	 * nothing about the indexed texts.
	 *
	 * Folding loses distinctions, and some are real words: `být` is a Czech stop word while `byt` ("apartment") is
	 * not. Callers must therefore ask only about a word the index does not know at all - a word with postings is a
	 * real word whatever its folded form collides with.
	 *
	 * @param analyzerName name of the analyzer
	 * @param word         the word as written, e.g. a query word's surface form
	 * @return true when its folded form is one of the analyzer's folded stop words
	 * @throws EvitaInvalidUsageException when the registry is closed
	 */
	public boolean isFoldedStopWord(@Nonnull String analyzerName, @Nonnull String word) {
		final Set<String> stopWords = getFoldedStopWords(analyzerName);
		return !stopWords.isEmpty() && stopWords.contains(BuiltInAnalyzers.foldStopWord(word));
	}

	/**
	 * Releases every analyzer instance this registry created. Mandatory when the catalog is closed — see the
	 * class javadoc.
	 *
	 * The flag is raised before the drain, which is the half of the handshake this method owns: a lookup that
	 * publishes its instance after the drain has passed observes the raised flag and releases that instance
	 * itself. See {@link #getAnalyzerInstance(String, Supplier)}.
	 *
	 * The map is **drained entry by entry rather than iterated and then cleared**, because `clear()` would
	 * discard a concurrently published entry without closing it — and would do so invisibly, since the late
	 * lookup's own removal then finds nothing and concludes somebody else took ownership. Removing each
	 * instance before closing it keeps that conclusion true.
	 */
	@Override
	public void close() {
		this.closed = true;
		for (final String name : this.instances.keySet()) {
			final FulltextAnalyzer analyzer = this.instances.remove(name);
			if (analyzer != null) {
				analyzer.close();
			}
		}
	}

	/**
	 * Translates an analyzer name into its shared instance, creating it lazily, and runs the second closed-flag check
	 * — the caller has run the first.
	 *
	 * The closed flag is checked twice, before and after the instance is published — see the class javadoc. A
	 * lookup that loses the race releases the instance it just created, so that {@link #close()} never leaves a
	 * chain behind whose stream components nothing will free.
	 *
	 * @param name           name of the analyzer
	 * @param unknownMessage message of the error an unknown name produces
	 * @return shared analyzer instance
	 * @throws EvitaInvalidUsageException when the name is unknown, or the registry was closed meanwhile
	 */
	@Nonnull
	private FulltextAnalyzer getAnalyzerInstance(@Nonnull String name, @Nonnull Supplier<String> unknownMessage) {
		final BuiltInAnalyzer definition = this.definitions.apply(name);
		Assert.notNull(definition, () -> new EvitaInvalidUsageException(unknownMessage.get()));
		final FulltextAnalyzer analyzer = this.instances.computeIfAbsent(
			name,
			analyzerName -> {
				assertNotClosed();
				return new FulltextAnalyzer(analyzerName, definition.factory().get());
			}
		);
		if (this.closed) {
			// close() ran while the factory was still building the chain and may have walked past this key
			// before the entry existed - take the instance back out and release it here, because nothing else
			// ever will. The removal is also what prevents a double close: close() likewise closes only what
			// it removed, so a false return here means close() already owns and released this instance
			if (this.instances.remove(name, analyzer)) {
				analyzer.close();
			}
			throw registryClosedException();
		}
		return analyzer;
	}

	/**
	 * Composes the error of a language table entry naming an analyzer the table of chains does not hold.
	 *
	 * @param name       the analyzer name the language table gave
	 * @param entityType entity collection the request was for
	 * @param locale     locale the request was for
	 * @return the message
	 */
	@Nonnull
	private static String unknownForRequest(@Nonnull String name, @Nonnull String entityType, @Nonnull Locale locale) {
		return "Analyzer `" + name + "` requested for entity type `" + entityType + "` and locale `" + locale +
			"` is not a built-in analyzer.";
	}

	/**
	 * Fails when this registry has already been closed; handing out an analyzer whose stream components were
	 * released would otherwise produce results that look merely empty.
	 */
	private void assertNotClosed() {
		Assert.isTrue(!this.closed, () -> registryClosedException());
	}

	/**
	 * The error every attempt to use a closed registry reports. Factored out so that the message and the
	 * exception type are defined once, and so that a caller which has *already* established that the registry
	 * is closed can throw it outright instead of going through {@link #assertNotClosed()} - a guard call in a
	 * branch where the condition is known reads as a no-op, which it is not.
	 *
	 * @return the exception to throw, never null
	 */
	@Nonnull
	private static EvitaInvalidUsageException registryClosedException() {
		return new EvitaInvalidUsageException("Full-text analyzer registry has already been closed.");
	}

}
