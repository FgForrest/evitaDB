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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextLanguageSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer.StopWordPreparation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single point through which the engine reaches a full-text analyzer. One instance per catalog.
 *
 * Immutable schema memos are safely published through a volatile list. Hits take no lock and never reorder the
 * memos. For a new catalog schema instance, a memo compares the analyzer definitions along `basedOn` (including
 * built-ins), the language definition or its absence, and every catalog resource by reference. Catalog copies retain
 * these objects, so ordinary commits neither resolve analysis nor hash resource contents.
 *
 * A true miss resolves content and shares chains through resolved-content equality, including query-only resources.
 * Compilation runs outside the publication lock; a concurrent loser closes its own unpublished chains. Publication
 * retains eight schema instances in insertion order and drops content entries no retained memo uses. Splitting is
 * shared separately; an entry with query text steps also owns a text-aware splitter. Only publication, eviction
 * and close take the lock, so compiling a miss cannot block hits.
 *
 * Eviction drops references without closing chains held by concurrent work. Lucene's `CloseableThreadLocal` and its
 * per-thread components can be collected once their analyzer becomes unreachable. Catalog close releases retained
 * chains only. Lookups check the closed flag before lookup and at publication; close drains entries under the
 * publication lock, while a late compiler closes its unpublished result. Successful removal owns closing a published
 * entry, preventing double close.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextAnalyzerRegistry implements Closeable {

	/** The maximum number of catalog schema identities retained by the fast memo. */
	private static final int SCHEMA_MEMO_LIMIT = 8;
	/** The compiler over the catalog's installed analysis steps. */
	@Nonnull private final FulltextChainCompiler compiler;
	/** Guards publication, eviction and closing; resolution and compilation never hold it. */
	@Nonnull private final ReentrantLock cacheLock = new ReentrantLock();
	/** Immutable schema window in insertion order, read without locking. */
	@Nonnull private volatile List<SchemaMemo> schemaMemos = List.of();
	/** Compiled content referenced by the retained memos; only publication and closing mutate it. */
	@Nonnull private final Map<ResolvedFulltextAnalyzer, CompiledFulltextAnalyzer> compiled =
		CollectionUtils.createConcurrentHashMap(16);
	/** Shared splitting chains, published and drained under the publication lock. */
	@Nonnull private final Map<ResolvedFulltextAnalyzer, FulltextAnalyzer> splittingChains = new HashMap<>();

	/**
	 * Set by {@link #close()}; every accessor checks it so that a released analyzer can never be handed out.
	 */
	private volatile boolean closed;

	/**
	 * Immutable lookups published for one schema instance.
	 *
	 * @param schema the schema instance, compared by identity
	 * @param lookups compiled entries keyed by analyzer name and locale
	 */
	private record SchemaMemo(
		@Nonnull CatalogSchemaContract schema, @Nonnull Map<AnalyzerLocale, DefinitionReferences> lookups
	) { }

	/**
	 * Definition identities used to validate a compiled lookup without resolving or hashing resource content.
	 *
	 * @param analyzers definitions along the complete inheritance path, including built-ins
	 * @param language the locale's language definition, or null when the default splitting applies
	 * @param resources all catalog resources exposed by the resolution context
	 * @param compiled the shared compiled result
	 */
	private record DefinitionReferences(
		@Nonnull List<FulltextAnalyzerSchemaContract> analyzers,
		@Nullable FulltextLanguageSchemaContract language,
		@Nonnull Map<String, FulltextResourceSchemaContract> resources,
		@Nonnull CompiledFulltextAnalyzer compiled
	) {
		/**
		 * Checks only references; an absent language and additions/removals of resources are dependencies too.
		 *
		 * @param schema the schema to validate against
		 * @param locale the lookup locale
		 * @return true when every definition used by resolution is still the same object
		 */
		private boolean matches(@Nonnull CatalogSchemaContract schema, @Nonnull Locale locale) {
			for (final FulltextAnalyzerSchemaContract analyzer : this.analyzers) {
				final FulltextAnalyzerSchemaContract builtIn = BuiltInFulltextAnalyzers.getAnalyzers()
					.get(analyzer.getName());
				if ((builtIn == null ? schema.getFulltextAnalyzers().get(analyzer.getName()) : builtIn) != analyzer) {
					return false;
				}
			}
			if (schema.getFulltextLanguages().get(locale.getLanguage()) != this.language ||
				schema.getFulltextResources().size() != this.resources.size()) {
				return false;
			}
			for (final Map.Entry<String, FulltextResourceSchemaContract> resource : this.resources.entrySet()) {
				if (schema.getFulltextResources().get(resource.getKey()) != resource.getValue()) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * One analyzer lookup within a fixed schema.
	 *
	 * @param analyzer the schema analyzer name
	 * @param locale the complete locale
	 */
	private record AnalyzerLocale(@Nonnull String analyzer, @Nonnull Locale locale) { }

	/**
	 * Creates the catalog's compiled analyzer cache over its installed steps.
	 *
	 * @param registry the catalog's analysis step registry
	 */
	public FulltextAnalyzerRegistry(@Nonnull AnalysisStepRegistry registry) {
		this.compiler = new FulltextChainCompiler(registry);
	}

	/**
	 * Resolves a field's current analyzer, sharing identical definitions across schema versions and collections.
	 *
	 * @param schema the current catalog schema instance
	 * @param analyzer the field's schema analyzer name
	 * @param locale the locale of its postings
	 * @return the shared compiled chains and index identities
	 * @throws EvitaInvalidUsageException when the registry is closed, analyzer resolution fails, or an analysis
	 *                                    definition, parameter, resource or step placement is invalid
	 * @throws GenericEvitaInternalError when fingerprinting detects cyclic delegates or unsupported parameter values,
	 *                                  or resource word splitting or preparation fails with an I/O error
	 * @throws RuntimeException when an analysis provider, compilation or chain cleanup throws another unchecked
	 *                          exception, propagated without wrapping
	 */
	@Nonnull
	public CompiledFulltextAnalyzer getCompiledAnalyzer(
		@Nonnull CatalogSchemaContract schema, @Nonnull String analyzer, @Nonnull Locale locale
	) {
		assertNotClosed();
		final AnalyzerLocale key = new AnalyzerLocale(analyzer, locale);
		for (final SchemaMemo memo : this.schemaMemos) {
			final DefinitionReferences entry = memo.lookups().get(key);
			if (entry != null && (memo.schema() == schema || entry.matches(schema, locale))) {
				assertNotClosed();
				return entry.compiled();
			}
		}
		final ResolvedFulltextAnalyzer resolved = ResolvedFulltextAnalyzer.resolve(schema, analyzer, locale);
		final List<FulltextAnalyzerSchemaContract> definitions = new ArrayList<>(4);
		final Set<String> visited = CollectionUtils.createHashSet(4);
		String current = analyzer;
		while (current != null && visited.add(current)) {
			final FulltextAnalyzerSchemaContract definition = ResolvedFulltextAnalyzer.find(schema, current, null);
			definitions.add(definition);
			current = definition.getBasedOn().orElse(null);
		}
		final CompiledFulltextAnalyzer existing = this.compiled.get(resolved);
		// A speculative compiler owns every chain until publication, including its private splitters.
		final CompiledFulltextAnalyzer candidate = existing == null ? compile(resolved) : null;
		this.cacheLock.lock();
		try {
			if (this.closed) {
				if (candidate != null) {
					releaseUnpublished(candidate);
				}
				throw registryClosedException();
			}
			CompiledFulltextAnalyzer result = this.compiled.get(resolved);
			if (result == null) {
				// An entry read before locking may have been evicted. Its handle remains usable by policy.
				result = candidate == null ? existing : candidate;
				final FulltextAnalyzer shared =
					this.splittingChains.putIfAbsent(splittingOf(resolved), result.splitting());
				if (shared != null && shared != result.splitting()) {
					if (candidate != null) {
						candidate.splitting().close();
					}
					result = new CompiledFulltextAnalyzer(result.word(), shared,
						result.queryText(),
						result.textSplitting() == result.splitting() ? shared : result.textSplitting(),
						result.textSplitWord(), result.indexSurface(), result.surface(), result.textSplitSurface(),
						result.fragment(), result.textSplitFragment(), result.fingerprint(),
						result.foldedStopWords(), result.stopWordPreparations());
				}
				this.compiled.put(resolved, result);
			} else if (candidate != null) {
				releaseUnpublished(candidate);
			}
			final DefinitionReferences references = new DefinitionReferences(List.copyOf(definitions),
				schema.getFulltextLanguages().get(locale.getLanguage()), Map.copyOf(schema.getFulltextResources()), result);
			final List<SchemaMemo> memos = new ArrayList<>(this.schemaMemos);
			final Map<AnalyzerLocale, DefinitionReferences> lookups = CollectionUtils.createHashMap(8);
			int memoIndex = -1;
			for (int i = 0; i < memos.size(); i++) {
				if (memos.get(i).schema() == schema) {
					lookups.putAll(memos.get(i).lookups());
					memoIndex = i;
					break;
				}
			}
			lookups.put(key, references);
			final SchemaMemo updatedMemo = new SchemaMemo(schema, Map.copyOf(lookups));
			if (memoIndex < 0) {
				memos.addFirst(updatedMemo);
			} else {
				memos.set(memoIndex, updatedMemo);
			}
			if (memos.size() > SCHEMA_MEMO_LIMIT) {
				memos.removeLast();
			}
			this.schemaMemos = List.copyOf(memos);
			final Set<CompiledFulltextAnalyzer> retained = CollectionUtils.createHashSet(this.compiled.size());
			for (final SchemaMemo memo : memos) {
				for (final DefinitionReferences entry : memo.lookups().values()) {
					retained.add(entry.compiled());
				}
			}
			this.compiled.values().removeIf(value -> !retained.contains(value));
			final Set<FulltextAnalyzer> retainedSplitting = CollectionUtils.createHashSet(retained.size());
			for (final CompiledFulltextAnalyzer value : retained) {
				retainedSplitting.add(value.splitting());
			}
			this.splittingChains.values().removeIf(value -> !retainedSplitting.contains(value));
			if (this.closed) {
				if (this.compiled.remove(resolved, result)) {
					releaseCompiledChains(result);
				}
				this.schemaMemos = List.of();
				throw registryClosedException();
			}
			return result;
		} finally {
			this.cacheLock.unlock();
		}
	}

	/**
	 * Compiles all chains of a content cache miss, releasing partially built chains on failure.
	 *
	 * @param resolved the complete content and locale of the definition
	 * @return unpublished chains owned by the caller until publication
	 */
	@Nonnull
	private CompiledFulltextAnalyzer compile(@Nonnull ResolvedFulltextAnalyzer resolved) {
		final List<StopWordPreparation> stopWords = new ArrayList<>(2);
		final FulltextAnalyzerFingerprint fingerprint = this.compiler.fingerprint(resolved);
		final List<FulltextAnalyzer> owned = new ArrayList<>(9);
		try {
			final FulltextAnalyzer indexSurface = new FulltextAnalyzer(resolved.analyzer(),
				this.compiler.compileIndexSurfaceChain(resolved, stopWords));
			owned.add(indexSurface);
			final FulltextAnalyzer surface = new FulltextAnalyzer(resolved.analyzer(),
				this.compiler.compileSurfaceChain(resolved, false));
			owned.add(surface);
			final FulltextAnalyzer fragment = new FulltextAnalyzer(resolved.analyzer(),
				this.compiler.compileFragmentChain(resolved, false));
			owned.add(fragment);
			final FulltextAnalyzer word = new FulltextAnalyzer(resolved.analyzer(),
				this.compiler.compileWordChain(resolved, stopWords));
			owned.add(word);
			final FulltextAnalyzer privateSplitting = new FulltextAnalyzer("splitting",
				this.compiler.compileSplittingChain(splittingOf(resolved)));
			owned.add(privateSplitting);
			final List<AnalysisStep> queryText = this.compiler.queryText(resolved);
			final FulltextAnalyzer textSplitting;
			final FulltextAnalyzer textSplitWord;
			final FulltextAnalyzer textSplitFragment;
			final FulltextAnalyzer textSplitSurface;
			if (queryText.isEmpty()) {
				textSplitting = privateSplitting;
				textSplitWord = word;
				textSplitFragment = fragment;
				textSplitSurface = surface;
			} else {
				textSplitting = new FulltextAnalyzer("query-splitting",
					this.compiler.compileSplittingChain(new ResolvedFulltextAnalyzer(
						"query-splitting", queryText, resolved.splitting(), List.of(), resolved.context())));
				owned.add(textSplitting);
				textSplitWord = new FulltextAnalyzer(resolved.analyzer(),
					this.compiler.compileTextSplitWordChain(resolved));
				owned.add(textSplitWord);
				textSplitFragment = new FulltextAnalyzer(resolved.analyzer(),
					this.compiler.compileFragmentChain(resolved, true));
				owned.add(textSplitFragment);
				textSplitSurface = new FulltextAnalyzer(resolved.analyzer(),
					this.compiler.compileSurfaceChain(resolved, true));
				owned.add(textSplitSurface);
			}
			final Set<String> foldedStopWords = CollectionUtils.createHashSet(64);
			for (final StopWordPreparation stop : stopWords) {
				foldedStopWords.addAll(stop.foldedWords());
			}
			return new CompiledFulltextAnalyzer(word, privateSplitting, queryText, textSplitting, textSplitWord,
				indexSurface, surface, textSplitSurface, fragment, textSplitFragment, fingerprint,
				foldedStopWords, stopWords);
		} catch (RuntimeException ex) {
			for (int i = owned.size() - 1; i >= 0; i--) {
				owned.get(i).close();
			}
			throw ex;
		}
	}

	/**
	 * Releases retained compiled chains when the catalog closes. The closed flag fences speculative compilation;
	 * publication and draining share the cache lock, and the caller removing an entry owns its close.
	 */
	@Override
	public void close() {
		this.closed = true;
		this.cacheLock.lock();
		try {
			for (final ResolvedFulltextAnalyzer key : List.copyOf(this.compiled.keySet())) {
				final CompiledFulltextAnalyzer analyzer = this.compiled.remove(key);
				if (analyzer != null) {
					releaseCompiledChains(analyzer);
				}
			}
			for (final ResolvedFulltextAnalyzer key : List.copyOf(this.splittingChains.keySet())) {
				final FulltextAnalyzer analyzer = this.splittingChains.remove(key);
				if (analyzer != null) {
					analyzer.close();
				}
			}
			this.schemaMemos = List.of();
		} finally {
			this.cacheLock.unlock();
		}
	}

	/**
	 * Releases the posting, word, fragment and surface preparation chains and optional text-aware chains of a removed
	 * compiled entry. Raw splitting has its own cache entry and is drained once separately, even when many analyzer
	 * groups share it.
	 *
	 * @param analyzer the removed entry whose chains, except shared raw splitting, this caller owns
	 */
	private static void releaseCompiledChains(@Nonnull CompiledFulltextAnalyzer analyzer) {
		analyzer.fragment().close();
		if (analyzer.textSplitFragment() != analyzer.fragment()) {
			analyzer.textSplitFragment().close();
		}
		analyzer.surface().close();
		if (analyzer.textSplitSurface() != analyzer.surface()) {
			analyzer.textSplitSurface().close();
		}
		analyzer.indexSurface().close();
		analyzer.word().close();
		if (analyzer.textSplitWord() != analyzer.word()) {
			analyzer.textSplitWord().close();
		}
		if (analyzer.textSplitting() != analyzer.splitting()) {
			analyzer.textSplitting().close();
		}
	}

	/**
	 * Releases every chain of a compiled entry that was never published, its private raw splitting included - the
	 * caller built them all and nobody else holds them.
	 *
	 * @param candidate the unpublished entry whose chains this caller owns
	 */
	private static void releaseUnpublished(@Nonnull CompiledFulltextAnalyzer candidate) {
		releaseCompiledChains(candidate);
		candidate.splitting().close();
	}

	/**
	 * Returns the definition of the locale's raw word splitting alone - the key the shared splitting chains are
	 * cached under, and what a private splitting chain is compiled from.
	 *
	 * @param resolved the resolved analyzer whose locale splitting is taken
	 * @return the splitting definition without text and words steps
	 */
	@Nonnull
	private static ResolvedFulltextAnalyzer splittingOf(@Nonnull ResolvedFulltextAnalyzer resolved) {
		return new ResolvedFulltextAnalyzer(
			"splitting", List.of(), resolved.splitting(), List.of(), resolved.context()
		);
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
