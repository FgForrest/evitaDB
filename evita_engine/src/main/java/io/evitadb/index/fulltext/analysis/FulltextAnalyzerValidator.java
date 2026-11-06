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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks that every fulltext analyzer of a catalog compiles, on both sides, in every language it can meet: the
 * check the catalog schema cannot make without a server, because it needs the installed steps. A step that is not
 * installed, parameters a step refuses, a step in the wrong phase, a step that refuses its place in the words phase,
 * and a resource a step of another provider names but the catalog does not declare are all refused here.
 *
 * The languages of a declared analyzer are those it - or an analyzer it is based on - overrides, those the catalog
 * declares word splitting for, those of the entities that have a searchable field using the analyzer, and the
 * all-languages definition ({@link Locale#ROOT}), so that an analyzer no field uses yet is checked too. A built-in
 * analyzer is checked only in the languages the catalog declares word splitting for: everything else in its chains
 * is fixed and compiles in every language, which the golden tests of the built-in analyzers pin, so a catalog that
 * declares no fulltext configuration costs nothing here.
 *
 * The check belongs where the whole schema is final - at the end of a session, before a catalog goes live, before a
 * mid-session flush, and on load - never at a single schema mutation: a schema built in several steps may name a
 * resource or an analyzer a later step declares.
 */
public final class FulltextAnalyzerValidator {

	/**
	 * Prevents instantiation of this utility class.
	 */
	private FulltextAnalyzerValidator() {
	}

	/**
	 * Compiles both sides of every built-in and declared analyzer of the catalog in each of its languages, and
	 * discards the chains. Returns at once when the catalog declares neither word splitting nor an analyzer.
	 *
	 * @param registry      the catalog's analysis step registry, which knows the installed steps
	 * @param catalogSchema the catalog schema declaring the analyzers, the languages, the resources and the entities
	 * @throws InvalidSchemaMutationException when an analyzer does not compile, naming the analyzer, the language, the
	 *                                        side and the reason
	 */
	public static void validate(@Nonnull AnalysisStepRegistry registry, @Nonnull CatalogSchemaContract catalogSchema) {
		final Set<Locale> declaredLanguages = CollectionUtils.createLinkedHashSet(
			catalogSchema.getFulltextLanguages().size()
		);
		for (final String language : catalogSchema.getFulltextLanguages().keySet()) {
			declaredLanguages.add(Locale.forLanguageTag(language));
		}
		final Map<String, FulltextAnalyzerSchemaContract> declaredAnalyzers = catalogSchema.getFulltextAnalyzers();
		if (declaredLanguages.isEmpty() && declaredAnalyzers.isEmpty()) {
			return;
		}
		final FulltextChainCompiler compiler = new FulltextChainCompiler(registry);
		for (final String analyzer : BuiltInFulltextAnalyzers.getAnalyzers().keySet()) {
			validate(compiler, catalogSchema, analyzer, declaredLanguages);
		}
		if (declaredAnalyzers.isEmpty()) {
			return;
		}
		final Map<String, Set<Locale>> fieldLocales = collectFieldLocales(catalogSchema);
		for (final String analyzer : declaredAnalyzers.keySet()) {
			validate(
				compiler, catalogSchema, analyzer,
				collectLocales(catalogSchema, analyzer, declaredLanguages, fieldLocales)
			);
		}
	}

	/**
	 * Compiles both sides of one analyzer in each of the given locales.
	 *
	 * @param compiler      the compiler resolving the steps through the catalog's registry
	 * @param catalogSchema the catalog schema declaring the analyzers
	 * @param analyzer      the name of the analyzer
	 * @param locales       the locales to compile it in
	 * @throws InvalidSchemaMutationException when the analyzer does not compile in one of the locales
	 */
	private static void validate(
		@Nonnull FulltextChainCompiler compiler,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String analyzer,
		@Nonnull Set<Locale> locales
	) {
		for (final Locale locale : locales) {
			final ResolvedFulltextAnalyzer resolved;
			try {
				resolved = ResolvedFulltextAnalyzer.resolve(catalogSchema, analyzer, locale);
			} catch (EvitaInvalidUsageException ex) {
				throw new InvalidSchemaMutationException(
					"Fulltext analyzer `" + analyzer + "` cannot be resolved for " + describe(locale) + ": " +
						ex.getMessage()
				);
			}
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try {
					// compiled only to find out whether it compiles
					compiler.compile(resolved, side).close();
				} catch (EvitaInvalidUsageException ex) {
					throw new InvalidSchemaMutationException(
						"Fulltext analyzer `" + resolved.analyzer() + "` cannot analyze " + describe(locale) +
							" on the " + side.name().toLowerCase(Locale.ROOT) + " side: " + ex.getMessage()
					);
				}
			}
			try {
				compiler.fingerprint(resolved);
			} catch (EvitaInvalidUsageException ex) {
				throw new InvalidSchemaMutationException(
					"Fulltext analyzer `" + analyzer + "` cannot fingerprint " + describe(locale) + ": " +
						ex.getMessage()
				);
			}
		}
	}

	/**
	 * Collects the locales a declared analyzer is checked in: the all-languages definition, the languages it and the
	 * analyzers it is based on override, the languages the catalog declares word splitting for, and the locales of
	 * the entities with a searchable field using it.
	 *
	 * @param catalogSchema     the catalog schema declaring the analyzers
	 * @param analyzer          the name of the analyzer
	 * @param declaredLanguages the languages the catalog declares word splitting for
	 * @param fieldLocales      the locales of the entities with a searchable field using it, keyed by analyzer name
	 * @return the locales, the root locale first
	 * @throws InvalidSchemaMutationException when an analyzer on the way along `basedOn` does not exist, or `basedOn`
	 *                                        leads back to an analyzer already on the way
	 */
	@Nonnull
	private static Set<Locale> collectLocales(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String analyzer,
		@Nonnull Set<Locale> declaredLanguages,
		@Nonnull Map<String, Set<Locale>> fieldLocales
	) {
		final Set<Locale> locales = CollectionUtils.createLinkedHashSet(16);
		locales.add(Locale.ROOT);
		final Set<String> visited = CollectionUtils.createHashSet(4);
		String current = analyzer;
		String basedOnBy = null;
		while (current != null) {
			// the walk goes the whole way, unlike the resolution, which stops at the first definition of a phase and
			// so does not meet a cycle beyond it
			if (!visited.add(current)) {
				throw new InvalidSchemaMutationException(
					"Fulltext analyzer `" + analyzer + "` is based on itself through a cycle of `basedOn`."
				);
			}
			final FulltextAnalyzerSchemaContract definition;
			try {
				definition = ResolvedFulltextAnalyzer.find(catalogSchema, current, basedOnBy);
			} catch (EvitaInvalidUsageException ex) {
				throw new InvalidSchemaMutationException(ex.getMessage());
			}
			for (final String language : definition.getLanguages().keySet()) {
				locales.add(Locale.forLanguageTag(language));
			}
			basedOnBy = current;
			current = definition.getBasedOn().orElse(null);
		}
		locales.addAll(declaredLanguages);
		locales.addAll(fieldLocales.getOrDefault(analyzer, Set.of()));
		return locales;
	}

	/**
	 * Collects, for each analyzer a searchable field names, the locales of the entities having such a field - the
	 * locales its values are analyzed in. Attributes, associated data and attributes of references count.
	 *
	 * @param catalogSchema the catalog schema whose entities are walked
	 * @return the locales keyed by analyzer name
	 */
	@Nonnull
	private static Map<String, Set<Locale>> collectFieldLocales(@Nonnull CatalogSchemaContract catalogSchema) {
		final Map<String, Set<Locale>> fieldLocales = CollectionUtils.createHashMap(8);
		for (final EntitySchemaContract entitySchema : catalogSchema.getEntitySchemas()) {
			final Set<Locale> locales = entitySchema.getLocales();
			if (locales.isEmpty()) {
				// only localized values are analyzed, and an entity without locales has none
				continue;
			}
			for (final AttributeSchemaContract attribute : entitySchema.getAttributes().values()) {
				addField(fieldLocales, attribute.isSearchableInAnyScope(), attribute.getAnalyzer(), locales);
			}
			for (final AssociatedDataSchemaContract associatedData : entitySchema.getAssociatedData().values()) {
				addField(
					fieldLocales, associatedData.isSearchableInAnyScope(), associatedData.getAnalyzer(), locales
				);
			}
			for (final ReferenceSchemaContract reference : entitySchema.getReferences().values()) {
				for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
					addField(fieldLocales, attribute.isSearchableInAnyScope(), attribute.getAnalyzer(), locales);
				}
			}
		}
		return fieldLocales;
	}

	/**
	 * Records the locales of one field's entity under the field's analyzer, when the field is searchable.
	 *
	 * @param fieldLocales the locales collected so far, keyed by analyzer name
	 * @param searchable   whether the field is searchable in any scope
	 * @param analyzer     the name of the analyzer the field uses
	 * @param locales      the locales of the field's entity
	 */
	private static void addField(
		@Nonnull Map<String, Set<Locale>> fieldLocales,
		boolean searchable,
		@Nonnull String analyzer,
		@Nonnull Set<Locale> locales
	) {
		if (searchable) {
			fieldLocales.computeIfAbsent(analyzer, name -> CollectionUtils.createHashSet(locales.size()))
				.addAll(locales);
		}
	}

	/**
	 * Names a locale the way the refusals read it.
	 *
	 * @param locale the locale an analyzer is checked in
	 * @return `the all-languages definition` for the root locale, the locale's language tag otherwise
	 */
	@Nonnull
	private static String describe(@Nonnull Locale locale) {
		return Locale.ROOT.equals(locale) ?
			"the all-languages definition" : "language `" + locale.toLanguageTag() + "`";
	}
}
