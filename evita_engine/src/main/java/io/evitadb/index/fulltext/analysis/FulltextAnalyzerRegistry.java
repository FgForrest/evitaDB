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
import io.evitadb.utils.Assert;
import org.apache.lucene.analysis.Analyzer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The single point through which the engine reaches a full-text analyzer. One instance per catalog.
 *
 * Resolution for a (collection, locale) pair runs in two steps that are worth keeping apart. First the
 * {@link AnalyzerAssignmentResolver} is asked which analyzer **names** the schema prescribes for the pair, and
 * when it prescribes none, the built-in default for the locale's **language** is used
 * (see {@link BuiltInAnalyzers}); the requested {@link AnalyzerSlot} then picks one name out of that
 * {@link AnalyzerAssignment}. Second, the name is translated into an instance. Instances are therefore keyed by
 * **name alone** — the collection and the locale decide *which* analyzer is used, never *which copy of it*, so
 * ten collections referring to `czech` share one chain instead of building ten identical ones. Laziness is not
 * micro-optimization: building a chain loads its data (Czech 172 stop words from a jar resource, Polish a 2.1 MB
 * stemmer table), while sharing one is free because a Lucene `Analyzer` is thread-safe.
 *
 * **The registry is open at runtime.** {@link #register(String, Supplier)} adds an analyzer under a new name,
 * which is what keeps an unusual language or an unusual chain from being a change inside the engine. The name is
 * what a schema refers to, so names must be unique — shadowing an existing one, built-in or registered, is
 * rejected rather than silently overriding an analyzer some collection already relies on.
 *
 * **The mode is declared at registration, never at the point of use.** {@link #register(String, Supplier,
 * AnalysisMode)} is where somebody who knows what the chain contains says which side of the pipeline it belongs
 * to. Letting a schema declare the mode instead would validate an author's assumption against the same author's
 * assumption — see {@link AnalysisMode}.
 *
 * That declaration is checked in two places, on purpose. {@link #validateAssignment(AnalyzerAssignment)} is the
 * front door: a schema mutation runs it and an operator gets a rejected mutation, at the moment they can still
 * fix the assignment. The check inside every lookup is the backstop: reaching it means an assignment got in
 * without passing the front door, so it fails as an internal error rather than an operator one. Both are needed
 * — validating only at mutation time would trust every path that ever writes an assignment, and validating only
 * at lookup time would surface a schema typo as a failed write of an unrelated entity.
 *
 * **{@link #close()} is mandatory when the catalog goes away.** A Lucene `Analyzer` retains its per-thread stream
 * components in a `CloseableThreadLocal` whose `hardRefs` map holds them for the lifetime of the process
 * otherwise. After closing, every accessor of this registry fails rather than handing out an analyzer whose
 * components were already released.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextAnalyzerRegistry implements Closeable {

	/**
	 * Seam through which a schema overrides the built-in language table.
	 */
	@Nonnull private final AnalyzerAssignmentResolver assignmentResolver;
	/**
	 * Analyzers registered at runtime, keyed by their name. Built-in analyzers are NOT copied in here — they are
	 * resolved through {@link BuiltInAnalyzers} — so this map holds exactly what somebody added.
	 */
	@Nonnull private final Map<String, RegisteredAnalyzer> registeredAnalyzers = new ConcurrentHashMap<>(8);
	/**
	 * Lazily created analyzer instances, keyed by analyzer name.
	 */
	@Nonnull private final Map<String, FulltextAnalyzer> instances = new ConcurrentHashMap<>(16);
	/**
	 * Set by {@link #close()}; every accessor checks it so that a released analyzer can never be handed out.
	 */
	private volatile boolean closed;

	/**
	 * Creates a registry that leaves every combination on its language default.
	 */
	public FulltextAnalyzerRegistry() {
		this(AnalyzerAssignmentResolver.DEFAULT);
	}

	/**
	 * Creates a registry consulting `assignmentResolver` before falling back to the language defaults.
	 *
	 * @param assignmentResolver seam through which a schema prescribes analyzers
	 */
	public FulltextAnalyzerRegistry(@Nonnull AnalyzerAssignmentResolver assignmentResolver) {
		this.assignmentResolver = assignmentResolver;
	}

	/**
	 * Returns the analyzer to be used while indexing values of `entityType` written in `locale`.
	 *
	 * @param entityType entity collection the analysed value belongs to
	 * @param locale     locale of the analysed value
	 * @return shared analyzer instance for the indexing slot
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the resolved analyzer declares itself
	 *                                                       search-time only
	 */
	@Nonnull
	public FulltextAnalyzer getIndexAnalyzer(@Nonnull String entityType, @Nonnull Locale locale) {
		return getAnalyzer(entityType, locale, AnalyzerSlot.INDEX);
	}

	/**
	 * Returns the analyzer to be used for the text of a query against `entityType` in `locale`.
	 *
	 * In this prototype it resolves to the same analyzer as the indexing one, which is the point: query and
	 * value normalization have to be one implementation, or the two sides stop meeting. The slots are separate
	 * so that query-only components (synonyms, entity recognition) can be assigned later without the difference
	 * growing up beside the registry as an exception.
	 *
	 * @param entityType entity collection being queried
	 * @param locale     locale of the query text
	 * @return shared analyzer instance for the query slot
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the resolved analyzer declares itself
	 *                                                       index-time only
	 */
	@Nonnull
	public FulltextAnalyzer getSearchAnalyzer(@Nonnull String entityType, @Nonnull Locale locale) {
		return getAnalyzer(entityType, locale, AnalyzerSlot.SEARCH);
	}

	/**
	 * Returns the analyzer to be used for the text of a **phrase** query against `entityType` in `locale`.
	 *
	 * Separate from {@link #getSearchAnalyzer} because a chain that drops stop words destroys a phrase — see
	 * {@link AnalyzerAssignment} on why the slot is split now rather than when it is first needed.
	 *
	 * @param entityType entity collection being queried
	 * @param locale     locale of the query text
	 * @return shared analyzer instance for the phrase slot
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the resolved analyzer declares itself
	 *                                                       index-time only
	 */
	@Nonnull
	public FulltextAnalyzer getPhraseAnalyzer(@Nonnull String entityType, @Nonnull Locale locale) {
		return getAnalyzer(entityType, locale, AnalyzerSlot.PHRASE);
	}

	/**
	 * Registers an analyzer usable on both sides of the pipeline under `name`, making it referable from a schema
	 * through {@link AnalyzerAssignmentResolver}.
	 *
	 * {@link AnalysisMode#ALL} is the right default rather than a silent assumption: there is nothing to derive
	 * a mode from here, the declaration is its only source, and a chain built from stateless components — which
	 * is what an analyzer is unless it reloads data behind the index's back — genuinely constrains nothing.
	 *
	 * @param name    name the analyzer becomes reachable under
	 * @param factory builds the Lucene chain; called at most once, when the name is first used
	 * @throws EvitaInvalidUsageException when the name is blank or already taken by a built-in or a previously
	 *                                    registered analyzer
	 */
	public void register(@Nonnull String name, @Nonnull Supplier<Analyzer> factory) {
		register(name, factory, AnalysisMode.ALL);
	}

	/**
	 * Registers an analyzer under `name`, declaring which side of the pipeline it may be used on.
	 *
	 * The declaration is made here, by whoever knows what the chain contains, and enforced at every later
	 * lookup — that asymmetry is the whole protection: an analyzer reloading its synonyms at runtime, declared
	 * {@link AnalysisMode#SEARCH_TIME}, can never be baked into an index by a schema that assigns it to the
	 * indexing slot.
	 *
	 * @param name    name the analyzer becomes reachable under
	 * @param factory builds the Lucene chain; called at most once, when the name is first used
	 * @param mode    side(s) of the pipeline the chain may be used on
	 * @throws EvitaInvalidUsageException when the name is blank or already taken by a built-in or a previously
	 *                                    registered analyzer
	 */
	public void register(
		@Nonnull String name,
		@Nonnull Supplier<Analyzer> factory,
		@Nonnull AnalysisMode mode
	) {
		assertNotClosed();
		Assert.isTrue(
			!name.isBlank(),
			() -> new EvitaInvalidUsageException("Analyzer name must not be empty.")
		);
		Assert.isTrue(
			BuiltInAnalyzers.supplierFor(name) == null,
			() -> new EvitaInvalidUsageException(
				"Analyzer `" + name + "` cannot be registered - the name is taken by a built-in analyzer."
			)
		);
		final RegisteredAnalyzer alreadyRegistered = this.registeredAnalyzers.putIfAbsent(
			name, new RegisteredAnalyzer(mode, factory)
		);
		Assert.isTrue(
			alreadyRegistered == null,
			() -> new EvitaInvalidUsageException(
				"Analyzer `" + name + "` is already registered in this catalog."
			)
		);
	}

	/**
	 * Verifies that `assignment` can actually be served — every name it uses resolves to a built-in or a
	 * registered analyzer, and every resolved analyzer is allowed in the slot it is assigned to. Slots the
	 * assignment leaves unset are checked as well, under the name they inherit.
	 *
	 * Meant to be called while a schema mutation is being applied, so that the operator who wrote the
	 * assignment is the one who learns it is wrong. The same rules are enforced again at every lookup, but by
	 * then the mutation has been accepted and the failure lands on whoever writes or queries an entity next —
	 * see the class javadoc on why both checks exist.
	 *
	 * @param assignment analyzer names a schema wants to prescribe for one (collection, locale) combination
	 * @throws EvitaInvalidUsageException when a name resolves to nothing, or when the analyzer it resolves to
	 *                                    may not be used in the slot it was assigned to
	 */
	public void validateAssignment(@Nonnull AnalyzerAssignment assignment) {
		assertNotClosed();
		for (final AnalyzerSlot slot : AnalyzerSlot.values()) {
			final String name = assignment.analyzerName(slot);
			final RegisteredAnalyzer definition = this.resolveDefinition(name);
			Assert.notNull(
				definition,
				() -> new EvitaInvalidUsageException(
					"Analyzer `" + name + "` assigned to the " + slot + " slot is neither a built-in nor a " +
						"registered analyzer."
				)
			);
			Assert.isTrue(
				definition.mode().isAllowedInMode(slot.getRequiredMode()),
				() -> new EvitaInvalidUsageException(
					"Analyzer `" + name + "` is declared as " + definition.mode() + " and cannot be assigned " +
						"to the " + slot + " slot, which requires " + slot.getRequiredMode() + "."
				)
			);
		}
	}

	/**
	 * Releases every analyzer instance this registry created. Mandatory when the catalog is closed — see the
	 * class javadoc.
	 */
	@Override
	public void close() {
		this.closed = true;
		final Collection<FulltextAnalyzer> createdInstances = this.instances.values();
		for (final FulltextAnalyzer analyzer : createdInstances) {
			analyzer.close();
		}
		this.instances.clear();
	}

	/**
	 * Resolves the analyzer for the given combination and slot, and lazily creates its instance.
	 *
	 * The mode is validated on every lookup rather than only when the instance is built — the same analyzer is
	 * shared by every slot referring to it, so a search-time only chain that a query slot already instantiated
	 * must still be refused when an indexing slot asks for it.
	 *
	 * @param entityType entity collection the value / query text belongs to
	 * @param locale     locale of the text
	 * @param slot       slot the analyzer is needed for
	 * @return shared analyzer instance
	 * @throws EvitaInvalidUsageException                    when the assignment names an analyzer that is
	 *                                                       neither built in nor registered
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the resolved analyzer may not be used in the
	 *                                                       requested slot, i.e. when an assignment that never
	 *                                                       passed {@link #validateAssignment} got in
	 */
	@Nonnull
	private FulltextAnalyzer getAnalyzer(
		@Nonnull String entityType,
		@Nonnull Locale locale,
		@Nonnull AnalyzerSlot slot
	) {
		assertNotClosed();
		final AnalyzerAssignment assignment = resolveAssignment(entityType, locale);
		final String name = assignment.analyzerName(slot);
		final RegisteredAnalyzer definition = resolveDefinition(name);
		Assert.notNull(
			definition,
			() -> new EvitaInvalidUsageException(
				"Analyzer `" + name + "` requested for entity type `" + entityType + "` and locale `" + locale +
					"` is neither a built-in nor a registered analyzer."
			)
		);
		// the slot refuses an analyzer that declares the opposite side of the pipeline - this is what makes a
		// runtime-swappable component impossible to bake into an index. Getting here means the assignment never
		// passed validateAssignment, hence an internal error rather than a usage one
		definition.mode().checkAllowedInMode(slot.getRequiredMode());
		return this.instances.computeIfAbsent(
			name,
			analyzerName -> {
				assertNotClosed();
				return new FulltextAnalyzer(analyzerName, definition.mode(), definition.factory().get());
			}
		);
	}

	/**
	 * Resolves the analyzer names for the given combination: the schema's choice when there is one, the built-in
	 * language default otherwise.
	 *
	 * @param entityType entity collection the value / query text belongs to
	 * @param locale     locale of the text
	 * @return names of the analyzers to use for the three slots
	 */
	@Nonnull
	private AnalyzerAssignment resolveAssignment(@Nonnull String entityType, @Nonnull Locale locale) {
		final Optional<AnalyzerAssignment> configured = this.assignmentResolver.resolveAnalyzers(entityType, locale);
		return configured.orElseGet(() -> AnalyzerAssignment.uniform(BuiltInAnalyzers.nameForLocale(locale)));
	}

	/**
	 * Translates an analyzer name into what is needed to build it — the registered declaration when there is
	 * one, the built-in factory (always {@link AnalysisMode#ALL}) otherwise. Returns null rather than throwing,
	 * because an unknown name means different things to its two callers: a rejected schema mutation to
	 * {@link #validateAssignment(AnalyzerAssignment)}, a failed lookup to
	 * {@link #getAnalyzer(String, Locale, AnalyzerSlot)}.
	 *
	 * @param name name of the analyzer to resolve
	 * @return declaration to build the analyzer from, or null when the name is neither built in nor registered
	 */
	@Nullable
	private RegisteredAnalyzer resolveDefinition(@Nonnull String name) {
		final RegisteredAnalyzer registered = this.registeredAnalyzers.get(name);
		if (registered != null) {
			return registered;
		}
		final Supplier<Analyzer> builtIn = BuiltInAnalyzers.supplierFor(name);
		return builtIn == null ? null : new RegisteredAnalyzer(AnalysisMode.ALL, builtIn);
	}

	/**
	 * Fails when this registry has already been closed; handing out an analyzer whose stream components were
	 * released would otherwise produce results that look merely empty.
	 */
	private void assertNotClosed() {
		Assert.isTrue(
			!this.closed,
			() -> new EvitaInvalidUsageException("Full-text analyzer registry has already been closed.")
		);
	}

	/**
	 * What a name resolves to before an instance exists: the declared mode and the factory able to build the
	 * chain. Private on purpose — the name is the key it is stored under, and nothing outside the registry has a
	 * reason to hold the triple.
	 *
	 * @param mode    side(s) of the pipeline chains built by `factory` may be used on
	 * @param factory builds the Lucene chain
	 */
	private record RegisteredAnalyzer(
		@Nonnull AnalysisMode mode,
		@Nonnull Supplier<Analyzer> factory
	) {
	}

}
