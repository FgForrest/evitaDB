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
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link FulltextAnalyzerRegistry} — how a (collection, locale) pair resolves to analyzer names, that
 * instances are shared per name, that the three pipeline slots enforce the {@link AnalysisMode} declared at
 * registration, that the registry can be extended at runtime, and that a closed registry stops handing analyzers
 * out. The inheritance rule of {@link AnalyzerAssignment} is covered here too, because it is what turns one
 * assignment into the three names the slots ask for, and so is
 * {@link FulltextAnalyzerRegistry#validateAssignment(AnalyzerAssignment)}, the schema-side front door in front
 * of the lookup-time backstop.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Full-text analyzer registry")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextAnalyzerRegistryTest {

	private static final String ENTITY_TYPE = "PRODUCT";
	private static final String OTHER_ENTITY_TYPE = "CATEGORY";
	private static final Locale CZECH_CZ = new Locale("cs", "CZ");
	private static final Locale CZECH = new Locale("cs");

	@Nullable private FulltextAnalyzerRegistry registry;

	@AfterEach
	void tearDown() {
		if (this.registry != null) {
			this.registry.close();
			this.registry = null;
		}
	}

	/**
	 * Creates the registry under test and remembers it so that it gets closed after the test.
	 *
	 * @param resolver seam through which a schema prescribes analyzers
	 * @return the registry under test
	 */
	@Nonnull
	private FulltextAnalyzerRegistry createRegistry(@Nonnull AnalyzerAssignmentResolver resolver) {
		this.registry = new FulltextAnalyzerRegistry(resolver);
		return this.registry;
	}

	/**
	 * Creates the registry under test with no schema overrides.
	 *
	 * @return the registry under test
	 */
	@Nonnull
	private FulltextAnalyzerRegistry createRegistry() {
		return createRegistry(AnalyzerAssignmentResolver.DEFAULT);
	}

	/**
	 * Returns the terms of `text` as produced by `analyzer`, for comparing what two slots emit.
	 *
	 * @param analyzer analyzer to run
	 * @param text     text to analyse
	 * @return terms produced by the analyzer
	 */
	@Nonnull
	private static List<String> terms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::term).toList();
	}

	@Nested
	@DisplayName("Instance resolution")
	class InstanceResolution {

		@Test
		@DisplayName("Locales of the same language share one instance")
		void shouldShareOneInstancePerLanguage() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			// analysis is a property of a language, so the country part must not fragment the cache - nor pay
			// for a second chain, which for Polish means a second 2.1 MB stemmer table
			assertSame(
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ),
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)
			);
		}

		@Test
		@DisplayName("Collections resolving to the same name share one instance")
		void shouldShareOneInstanceAcrossCollections() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			// the collection decides WHICH analyzer is used, never which copy of it - ten collections on the
			// Czech default must not build ten identical chains
			assertSame(
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ),
				registry.getIndexAnalyzer(OTHER_ENTITY_TYPE, CZECH_CZ)
			);
		}

		@Test
		@DisplayName("Unknown language falls back to the generic analyzer instead of failing")
		void shouldFallBackToGenericAnalyzerForUnknownLanguage() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			final FulltextAnalyzer analyzer = registry.getIndexAnalyzer(ENTITY_TYPE, new Locale("fi"));
			// neither an exception nor a silent skip - an explicitly named fallback analyzer that a schema can
			// refer to and that documentation can describe
			assertEquals(BuiltInAnalyzers.GENERIC_ANALYZER_NAME, analyzer.getAnalyzerName());
			assertIterableEquals(List.of("kirjat"), terms(analyzer, "Kirjat"));
		}

		@Test
		@DisplayName("All three slots yield working analyzers")
		void shouldServeAllThreeSlots() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			final FulltextAnalyzer index = registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ);
			final FulltextAnalyzer search = registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ);
			final FulltextAnalyzer phrase = registry.getPhraseAnalyzer(ENTITY_TYPE, CZECH_CZ);

			// the language default puts one name into all three slots, so all three slots share one chain - and
			// therefore produce identical terms, which is the property that actually matters: the query side has
			// to meet what the index side wrote
			assertSame(index, search);
			assertSame(index, phrase);
			final List<String> expected = List.of("cesk", "republik");
			assertIterableEquals(expected, terms(index, "Česká Republika"));
			assertIterableEquals(expected, terms(search, "Česká Republika"));
			assertIterableEquals(expected, terms(phrase, "Česká Republika"));
		}

		@Test
		@DisplayName("Each slot follows the name its assignment gives it")
		void shouldFollowThePerSlotNamesOfTheAssignment() {
			// index and query side deliberately disagree here - nonsense as a configuration, but it proves the
			// slot is what picks a name out of the assignment
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> Optional.of(
					new AnalyzerAssignment(
						BuiltInAnalyzers.GENERIC_ANALYZER_NAME,
						BuiltInAnalyzers.ENGLISH_ANALYZER_NAME,
						null
					)
				)
			);
			assertEquals(
				BuiltInAnalyzers.GENERIC_ANALYZER_NAME,
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ).getAnalyzerName()
			);
			assertEquals(
				BuiltInAnalyzers.ENGLISH_ANALYZER_NAME,
				registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ).getAnalyzerName()
			);
			// the phrase slot was left unset, so it inherits the query one rather than the index one
			assertSame(
				registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ),
				registry.getPhraseAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
		}

	}

	@Nested
	@DisplayName("Analyzer assignment")
	class Assignments {

		@Test
		@DisplayName("An unset slot inherits along index -> search -> phrase")
		void shouldInheritUnsetSlots() {
			final AnalyzerAssignment indexOnly = new AnalyzerAssignment("a", null, null);
			assertEquals("a", indexOnly.analyzerName(AnalyzerSlot.INDEX));
			assertEquals("a", indexOnly.analyzerName(AnalyzerSlot.SEARCH));
			assertEquals("a", indexOnly.analyzerName(AnalyzerSlot.PHRASE));

			// the phrase slot inherits from the QUERY side, not from the index one - a query-side chain the
			// schema went out of its way to name must not be silently bypassed by phrase queries
			final AnalyzerAssignment withSearch = new AnalyzerAssignment("a", "b", null);
			assertEquals("a", withSearch.analyzerName(AnalyzerSlot.INDEX));
			assertEquals("b", withSearch.analyzerName(AnalyzerSlot.SEARCH));
			assertEquals("b", withSearch.analyzerName(AnalyzerSlot.PHRASE));

			final AnalyzerAssignment withPhraseOnly = new AnalyzerAssignment("a", null, "c");
			assertEquals("a", withPhraseOnly.analyzerName(AnalyzerSlot.INDEX));
			assertEquals("a", withPhraseOnly.analyzerName(AnalyzerSlot.SEARCH));
			assertEquals("c", withPhraseOnly.analyzerName(AnalyzerSlot.PHRASE));
		}

		@Test
		@DisplayName("A fully specified assignment inherits nothing")
		void shouldUseExplicitNamesWhenAllSlotsAreSet() {
			final AnalyzerAssignment all = new AnalyzerAssignment("a", "b", "c");
			assertEquals("a", all.analyzerName(AnalyzerSlot.INDEX));
			assertEquals("b", all.analyzerName(AnalyzerSlot.SEARCH));
			assertEquals("c", all.analyzerName(AnalyzerSlot.PHRASE));
		}

		@Test
		@DisplayName("A uniform assignment puts one name into all three slots")
		void shouldPutOneNameIntoEverySlot() {
			final AnalyzerAssignment uniform = AnalyzerAssignment.uniform("a");
			assertEquals("a", uniform.analyzerName(AnalyzerSlot.INDEX));
			assertEquals("a", uniform.analyzerName(AnalyzerSlot.SEARCH));
			assertEquals("a", uniform.analyzerName(AnalyzerSlot.PHRASE));
			// only the index slot is set explicitly - "unset" has to stay distinguishable from "set to the same
			// value", because that is what a schema round-trip preserves
			assertNull(uniform.searchAnalyzer());
			assertNull(uniform.phraseAnalyzer());
		}

		@Test
		@DisplayName("An empty name is rejected in every slot")
		void shouldRejectBlankNames() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new AnalyzerAssignment(" ", null, null)
			);
			// null means "inherited" - a blank string means nothing at all, so it must not be accepted as a
			// synonym for it
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new AnalyzerAssignment("a", " ", null)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new AnalyzerAssignment("a", null, " ")
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AnalyzerAssignment.uniform("")
			);
		}

	}

	@Nested
	@DisplayName("Assignment validation")
	class AssignmentValidation {

		@Test
		@DisplayName("An assignment of known, compatible analyzers passes")
		void shouldAcceptValidAssignment() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.register("query-only", TokenizingAnalyzer::new, AnalysisMode.SEARCH_TIME);

			assertDoesNotThrow(
				() -> registry.validateAssignment(
					AnalyzerAssignment.uniform(BuiltInAnalyzers.CZECH_ANALYZER_NAME)
				)
			);
			// a search-time only analyzer is legal as long as it is assigned to the query side only
			assertDoesNotThrow(
				() -> registry.validateAssignment(
					new AnalyzerAssignment(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "query-only", null)
				)
			);
		}

		@Test
		@DisplayName("An unknown name is rejected as an operator error naming the slot")
		void shouldRejectUnknownNameInAssignment() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.validateAssignment(
					new AnalyzerAssignment(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "no-such-analyzer", null)
				)
			);
			// the message has to say which slot broke, because that is the only part of the assignment the
			// operator has to change
			assertTrue(exception.getMessage().contains("no-such-analyzer"));
			assertTrue(exception.getMessage().contains(AnalyzerSlot.SEARCH.name()));
		}

		@Test
		@DisplayName("An analyzer assigned to a slot it may not run in is rejected before the mutation lands")
		void shouldRejectModeViolationInAssignment() {
			final String analyzerName = "query-only";
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.register(analyzerName, TokenizingAnalyzer::new, AnalysisMode.SEARCH_TIME);

			// an operator error rather than an internal one - this is the whole reason the check exists twice:
			// here the person who wrote the assignment can still fix it
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.validateAssignment(AnalyzerAssignment.uniform(analyzerName))
			);
			assertTrue(exception.getMessage().contains(analyzerName));
			assertTrue(exception.getMessage().contains(AnalysisMode.SEARCH_TIME.name()));
			assertTrue(exception.getMessage().contains(AnalyzerSlot.INDEX.name()));
		}

		@Test
		@DisplayName("Inherited slots are validated under the name they inherit")
		void shouldValidateInheritedSlots() {
			final String analyzerName = "index-only";
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.register(analyzerName, TokenizingAnalyzer::new, AnalysisMode.INDEX_TIME);

			// nothing names the query side here - it inherits an index-only analyzer, and an unset slot must not
			// be a way past the check
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.validateAssignment(AnalyzerAssignment.uniform(analyzerName))
			);
			assertTrue(exception.getMessage().contains(AnalyzerSlot.SEARCH.name()));
		}

	}

	@Nested
	@DisplayName("Analysis mode enforcement")
	class AnalysisModeEnforcement {

		@Test
		@DisplayName("A slot accepts an unconstrained analyzer and rejects the opposite side")
		void shouldValidateAnalyzerAgainstSlot() {
			assertTrue(AnalysisMode.ALL.isAllowedInMode(AnalysisMode.INDEX_TIME));
			assertTrue(AnalysisMode.ALL.isAllowedInMode(AnalysisMode.SEARCH_TIME));
			assertTrue(AnalysisMode.INDEX_TIME.isAllowedInMode(AnalysisMode.INDEX_TIME));
			assertFalse(AnalysisMode.SEARCH_TIME.isAllowedInMode(AnalysisMode.INDEX_TIME));
			assertFalse(AnalysisMode.INDEX_TIME.isAllowedInMode(AnalysisMode.SEARCH_TIME));

			assertDoesNotThrow(() -> AnalysisMode.ALL.checkAllowedInMode(AnalysisMode.INDEX_TIME));
			assertDoesNotThrow(() -> AnalysisMode.ALL.checkAllowedInMode(AnalysisMode.SEARCH_TIME));
			assertDoesNotThrow(() -> AnalysisMode.INDEX_TIME.checkAllowedInMode(AnalysisMode.INDEX_TIME));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> AnalysisMode.SEARCH_TIME.checkAllowedInMode(AnalysisMode.INDEX_TIME)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> AnalysisMode.INDEX_TIME.checkAllowedInMode(AnalysisMode.SEARCH_TIME)
			);
		}

		@Test
		@DisplayName("A search-time analyzer cannot be used in the indexing slot")
		void shouldRefuseSearchTimeAnalyzerInIndexSlot() {
			final String analyzerName = "query-only";
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> Optional.of(AnalyzerAssignment.uniform(analyzerName))
			);
			registry.register(analyzerName, TokenizingAnalyzer::new, AnalysisMode.SEARCH_TIME);

			// this is the whole point of the mode flag: a runtime-swappable artifact declared search-time only
			// can never be baked into an index, because the indexing slot refuses to hand the chain out at all
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			// while the query side accepts it
			assertEquals(
				analyzerName,
				registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ).getAnalyzerName()
			);
			// and it keeps refusing the indexing slot after the instance exists - the mode is checked on every
			// lookup, not only while the chain is being built
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
		}

		@Test
		@DisplayName("An index-time analyzer cannot be used in the query or phrase slot")
		void shouldRefuseIndexTimeAnalyzerInSearchSlots() {
			final String analyzerName = "index-only";
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> Optional.of(AnalyzerAssignment.uniform(analyzerName))
			);
			registry.register(analyzerName, TokenizingAnalyzer::new, AnalysisMode.INDEX_TIME);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.getPhraseAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertEquals(
				analyzerName,
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ).getAnalyzerName()
			);
		}

		@Test
		@DisplayName("An analyzer registered without a mode is usable in every slot")
		void shouldTreatRegistrationWithoutModeAsUnconstrained() {
			final String analyzerName = "no-mode-declared";
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> Optional.of(AnalyzerAssignment.uniform(analyzerName))
			);
			// nothing is derived from the chain, so ALL is a real default rather than an assumption - the
			// registrant declares a restriction only when the chain actually carries one
			registry.register(analyzerName, TokenizingAnalyzer::new);

			assertEquals(AnalysisMode.ALL, registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ).getMode());
			assertDoesNotThrow(() -> registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ));
			assertDoesNotThrow(() -> registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ));
			assertDoesNotThrow(() -> registry.getPhraseAnalyzer(ENTITY_TYPE, CZECH_CZ));
		}

	}

	@Nested
	@DisplayName("Runtime registration")
	class RuntimeRegistration {

		@Test
		@DisplayName("An analyzer registered at runtime is selected by the resolver for its combination")
		void shouldUseRuntimeRegisteredAnalyzerSelectedByResolver() {
			final String analyzerName = "czech-no-folding";
			// the resolver overrides only PRODUCT/cs, everything else stays on its language default - this is
			// the granularity a schema needs
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> ENTITY_TYPE.equals(entityType) && "cs".equals(locale.getLanguage())
					? Optional.of(AnalyzerAssignment.uniform(analyzerName))
					: Optional.empty()
			);
			registry.register(analyzerName, TokenizingAnalyzer::new);

			final FulltextAnalyzer overridden = registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ);
			assertEquals(analyzerName, overridden.getAnalyzerName());
			// the custom analyzer neither stems nor folds, so the built-in Czech expectation must NOT hold
			assertIterableEquals(List.of("česká", "republika"), terms(overridden, "Česká Republika"));

			final FulltextAnalyzer notOverridden = registry.getIndexAnalyzer(OTHER_ENTITY_TYPE, CZECH_CZ);
			assertEquals(BuiltInAnalyzers.CZECH_ANALYZER_NAME, notOverridden.getAnalyzerName());
			assertIterableEquals(List.of("cesk", "republik"), terms(notOverridden, "Česká Republika"));
		}

		@Test
		@DisplayName("A built-in analyzer can be selected by name")
		void shouldAllowSelectingBuiltInAnalyzerByName() {
			// analysing Czech text with the English analyzer is nonsense, but it proves the name - not the
			// locale - is what picks the chain, which is what makes a schema override possible at all
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> Optional.of(
					AnalyzerAssignment.uniform(BuiltInAnalyzers.ENGLISH_ANALYZER_NAME)
				)
			);
			assertEquals(
				BuiltInAnalyzers.ENGLISH_ANALYZER_NAME,
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ).getAnalyzerName()
			);
			assertNotNull(BuiltInAnalyzers.supplierFor(BuiltInAnalyzers.GENERIC_ANALYZER_NAME));
		}

		@Test
		@DisplayName("Registering the same name twice is rejected")
		void shouldRejectDuplicateName() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.register("mine", TokenizingAnalyzer::new);
			// silently replacing an analyzer some collection already indexed with would desynchronize the index
			// from the data it describes
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.register("mine", TokenizingAnalyzer::new)
			);
		}

		@Test
		@DisplayName("Shadowing a built-in name is rejected")
		void shouldRejectShadowingBuiltInName() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.register(BuiltInAnalyzers.CZECH_ANALYZER_NAME, TokenizingAnalyzer::new)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.register(BuiltInAnalyzers.GENERIC_ANALYZER_NAME, TokenizingAnalyzer::new)
			);
		}

		@Test
		@DisplayName("An analyzer must be registered under a name")
		void shouldRejectBlankAnalyzerName() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.register(" ", TokenizingAnalyzer::new)
			);
		}

		@Test
		@DisplayName("An unknown analyzer name prescribed by the schema is reported, not substituted")
		void shouldRejectUnknownAnalyzerName() {
			final FulltextAnalyzerRegistry registry = createRegistry(
				(entityType, locale) -> Optional.of(AnalyzerAssignment.uniform("no-such-analyzer"))
			);
			// falling back to the language default here would hide a schema typo behind quietly different
			// search results
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
		}

	}

	@Nested
	@DisplayName("Lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("A closed registry refuses every further use")
		void shouldFailAfterClose() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ);
			registry.close();

			// handing out an analyzer whose stream components were already released would produce results that
			// look merely empty, so the failure has to be explicit
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getPhraseAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.register("mine", TokenizingAnalyzer::new)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.validateAssignment(
					AnalyzerAssignment.uniform(BuiltInAnalyzers.CZECH_ANALYZER_NAME)
				)
			);
		}

		@Test
		@DisplayName("Closing twice is harmless")
		void shouldTolerateRepeatedClose() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ);
			registry.close();
			assertDoesNotThrow(registry::close);
		}

	}

}
