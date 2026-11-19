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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.core.query.filter.translator.fulltext.TextMatchesTranslator;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.FulltextFieldDefinition;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Bounded sample checks of the analyzers a schema change touched, run before the schema is published. They prove,
 * on the same compiled index and query chains that indexing and `textMatches` use, what an analyzer has to guarantee
 * for a query to find what was indexed. Diagnostics name the rules by these numbers:
 *
 * 1. **self-match** - a sample finds itself as typed and lower-cased, and a built-in language sample also without
 *    its diacritics;
 * 2. **stop words** - an entry of a stop-word resource leaves no index term;
 * 3. **protected words** - an entry of a stemmer's protected-words resource has the index terms of the same chain in
 *    which that stemmer leaves the word unstemmed; the stemmer's own normalization still applies;
 * 4. **synonyms** - every member of a synonym group finds every other member.
 *
 * Only touched analyzer/locale pairs are checked, in the locales of the searchable fields using the analyzer; there
 * the built-in samples and rules 2 to 4 apply as well. An unused custom analyzer checks only its own samples, in
 * root. Warm-up DDL and final validation share the session's baseline and reuse the last verdict when the inputs
 * match. Load and WAL replay retain their existing checks. Catalog-owned compiled chains stay in the catalog cache;
 * the rule 3 comparison chain is owned and closed here.
 */
public final class FulltextAnalyzerBehaviourCheck {
	/** Only the first 500 stop entries per analyzer/language are checked; later entries remain admissible. */
	private static final int MAX_STOP_WORDS = 500;
	/** Only the first 500 entries of stemming steps' protected lists per analyzer/language are checked. */
	private static final int MAX_PROTECTED_WORDS = 500;
	/** Only the first 100 synonym groups per analyzer/language are checked; later groups remain admissible. */
	private static final int MAX_SYNONYM_GROUPS = 100;
	/** Only the first eight members of a group are compared, giving at most 56 distinct ordered pairs. */
	private static final int MAX_SYNONYM_MEMBERS = 8;
	/** Every analyzer/language is evaluated, but only the first ten failures are retained in the refusal. */
	private static final int MAX_FAILURES = 10;
	/** A diagnostic quotes at most this many characters of the offending sample. */
	private static final int MAX_DIAGNOSTIC_SAMPLE_LENGTH = 80;
	/** A diagnostic lists at most this many distinct terms per side. */
	private static final int MAX_DIAGNOSTIC_TERMS = 10;
	/** Query forms in rule 1 order, shared by the sample verdict and its diagnostic. */
	private static final List<String> SAMPLE_FORMS = List.of("as typed", "lower-cased", "without diacritics");

	/** Prevents instantiation of this schema validation utility. */
	private FulltextAnalyzerBehaviourCheck() {
	}

	/**
	 * Evaluates touched analyzer/locale pairs, reusing the session's verdict when the complete input is unchanged.
	 * @param steps installed providers describing resource dependencies and compiling rule 3 comparison chains
	 * @param registry catalog cache owning compiled chains
	 * @param session the session's starting declarations, field usage and previous verdict
	 * @param catalog catalog whose current schema compilation has already been validated
	 * @throws InvalidSchemaMutationException when any checked pair fails
	 */
	public static void validate(
		@Nonnull AnalysisStepRegistry steps, @Nonnull FulltextAnalyzerRegistry registry,
		@Nonnull SessionChecks session, @Nonnull CatalogContract catalog
	) {
		final CatalogSchemaContract schema = catalog.getSchema();
		final Set<CheckInput> inputs = collectInputs(steps, session, catalog, schema);
		final Verdict previous = session.verdict;
		final String refusal;
		if (previous != null && previous.inputs().equals(inputs)) {
			refusal = previous.refusal();
		} else {
			final List<String> failures = new ArrayList<>(MAX_FAILURES);
			for (final CheckInput input : inputs) {
				final ResolvedFulltextAnalyzer resolved = input.resolved();
				final CompiledFulltextAnalyzer compiled =
					registry.getCompiledAnalyzer(schema, resolved.analyzer(), resolved.context().locale());
				final String failure = check(steps, input, compiled);
				if (failure != null && failures.size() < MAX_FAILURES) {
					failures.add(failure);
				}
			}
			refusal = failures.isEmpty() ? null : String.join("\n", failures);
			session.verdict = new Verdict(inputs, refusal);
		}
		if (refusal != null) {
			throw new InvalidSchemaMutationException(refusal);
		}
	}

	/**
	 * Freezes the input of each check, independently of unrelated schema versions and collection changes.
	 * @param steps installed providers identifying resource dependencies
	 * @param session starting declarations and field assignments
	 * @param catalog catalog providing stable collection identities
	 * @param schema current declarations and fields
	 * @return touched checks in evaluation order
	 */
	@Nonnull
	private static Set<CheckInput> collectInputs(
		@Nonnull AnalysisStepRegistry steps, @Nonnull SessionChecks session, @Nonnull CatalogContract catalog,
		@Nonnull CatalogSchemaContract schema
	) {
		final Map<String, Set<Locale>> fields = CollectionUtils.createHashMap(8);
		final Map<String, Set<Locale>> attached = CollectionUtils.createHashMap(8);
		visitSearchableFields(catalog, schema, (key, field) -> {
			fields.computeIfAbsent(field.analyzer(), ignored -> CollectionUtils.createLinkedHashSet(4))
				.addAll(field.locales());
			if (!Objects.equals(session.startFieldAssignments.get(key), field.analyzer())) {
				attached.computeIfAbsent(field.analyzer(), ignored -> CollectionUtils.createLinkedHashSet(4))
					.addAll(field.locales());
			}
		});
		final Set<String> analyzers = CollectionUtils.createLinkedHashSet(schema.getFulltextAnalyzers().size() + 2);
		analyzers.addAll(BuiltInFulltextAnalyzers.getAnalyzers().keySet());
		analyzers.addAll(schema.getFulltextAnalyzers().keySet());
		final Set<CheckInput> inputs = CollectionUtils.createLinkedHashSet(analyzers.size());
		for (final String name : analyzers) {
			final boolean builtIn = BuiltInFulltextAnalyzers.getAnalyzers().containsKey(name);
			final List<String> authorSamples = samples(schema, name);
			final Set<Locale> locales = fields.getOrDefault(name, Set.of());
			final boolean used = !locales.isEmpty();
			if (!used && (builtIn || authorSamples.isEmpty())) {
				continue;
			}
			for (final Locale locale : used ? locales : Set.of(Locale.ROOT)) {
				final ResolvedFulltextAnalyzer resolved = resolveUsedResources(steps, schema, name, locale);
				if (touched(steps, session, schema, resolved, builtIn) ||
					!builtIn && attached.getOrDefault(name, Set.of()).contains(locale)) {
					inputs.add(new CheckInput(resolved, authorSamples, used));
				}
			}
		}
		return inputs;
	}

	/**
	 * Compares the analyzer's relevant declarations and effective chains against the session start.
	 * Field attachment is tested separately, using field identity independently of allowed locales.
	 * @param steps installed providers identifying resource dependencies
	 * @param session starting declarations
	 * @param schema current declarations
	 * @param current resolved chain with only the resources it reads
	 * @param builtIn whether the analyzer's template is fixed
	 * @return true when the pair needs a behavior verdict
	 */
	private static boolean touched(
		@Nonnull AnalysisStepRegistry steps, @Nonnull SessionChecks session, @Nonnull CatalogSchemaContract schema,
		@Nonnull ResolvedFulltextAnalyzer current, boolean builtIn
	) {
		final String name = current.analyzer();
		final Locale locale = current.context().locale();
		final CatalogSchemaContract start = session.startSchema;
		if (!builtIn && (changedDeclaration(schema, start, name, locale.getLanguage()) ||
			!Objects.equals(schema.getFulltextLanguages().get(locale.getLanguage()),
				start.getFulltextLanguages().get(locale.getLanguage())))) {
			return true;
		}
		return !current.equals(resolveUsedResources(steps, start, name, locale)) ||
			!builtIn && !ResolvedFulltextAnalyzer.hasSamePhaseDeclarations(schema, start, name, locale);
	}

	/**
	 * Detects changes to a custom declaration's samples, base analyzer or applicable language override.
	 * Resolved chains are compared separately, including inherited phase contents and named resources.
	 * @param schema current declaration source
	 * @param start declarations at session entry
	 * @param analyzer custom analyzer being compared
	 * @param language language whose override is in scope, empty for root
	 * @return whether the declaration is new or changed for this language
	 */
	private static boolean changedDeclaration(
		@Nonnull CatalogSchemaContract schema, @Nonnull CatalogSchemaContract start, @Nonnull String analyzer,
		@Nonnull String language
	) {
		final FulltextAnalyzerSchemaContract current = ResolvedFulltextAnalyzer.find(schema, analyzer, null);
		final FulltextAnalyzerSchemaContract original = start.getFulltextAnalyzer(analyzer).orElse(null);
		return original == null || !original.getSamples().equals(current.getSamples()) ||
			!original.getBasedOn().equals(current.getBasedOn()) ||
			!Objects.equals(original.getLanguages().get(language), current.getLanguages().get(language));
	}

	/**
	 * Resolves a chain and retains only resources referenced by a provider's declared parameter on either side.
	 * @param steps installed analysis providers
	 * @param schema schema providing analyzer, splitting and resource declarations
	 * @param name analyzer to resolve
	 * @param locale locale the field holds
	 * @return the effective phases and exact resource content that can affect the check
	 */
	@Nonnull
	private static ResolvedFulltextAnalyzer resolveUsedResources(
		@Nonnull AnalysisStepRegistry steps, @Nonnull CatalogSchemaContract schema,
		@Nonnull String name, @Nonnull Locale locale
	) {
		final ResolvedFulltextAnalyzer resolved = ResolvedFulltextAnalyzer.resolve(schema, name, locale);
		final Set<String> resourceNames = CollectionUtils.createHashSet(4);
		for (final AnalysisStep step : allSteps(resolved).toList()) {
			for (final AnalysisStepParameterDescriptor parameter : steps.resolveFulltext(step).getParameters()) {
				if (parameter.resourceKind() != null) {
					resourceNames.addAll(references(step, parameter.name()));
				}
			}
		}
		return new ResolvedFulltextAnalyzer(name, resolved.text(), resolved.splitting(), resolved.words(),
			resolved.context().withResources(resourceNames));
	}

	/**
	 * Visits searchable field assignments and their current locales. Imported globals and effective reference
	 * attributes use the same schema accessors as fulltext query planning.
	 * Every field is used in the locales of its collection: schema validation, which runs before this check,
	 * accepts only localized searchable fields.
	 * @param catalog catalog providing collection primary keys across renames
	 * @param schema catalog whose entity schemas declare fulltext places
	 * @param consumer receiver of field identities and analyzer assignments
	 */
	private static void visitSearchableFields(
		@Nonnull CatalogContract catalog, @Nonnull CatalogSchemaContract schema,
		@Nonnull BiConsumer<FieldAddress, FieldUse> consumer
	) {
		for (final EntitySchemaContract entity : schema.getEntitySchemas()) {
			final EntityCollectionContract collection = catalog.getCollectionForEntity(entity.getName()).orElse(null);
			if (collection == null) {
				continue;
			}
			final int collectionId = collection.getEntityTypePrimaryKey();
			final Set<Locale> locales = entity.getLocales();
			for (final AttributeSchemaContract field : entity.getAttributes().values()) {
				if (field.isSearchableInAnyScope()) {
					consumer.accept(new FieldAddress(collectionId, FulltextFieldKey.attribute(field.getName())),
						new FieldUse(field.getAnalyzer(), locales));
				}
			}
			for (final AssociatedDataSchemaContract field : entity.getAssociatedData().values()) {
				if (field.isSearchableInAnyScope()) {
					consumer.accept(new FieldAddress(collectionId, FulltextFieldKey.associatedData(field.getName())),
						new FieldUse(field.getAnalyzer(), locales));
				}
			}
			for (final ReferenceSchemaContract reference : entity.getReferences().values()) {
				for (final AttributeSchemaContract field :
					FulltextFieldDefinition.availableReferenceAttributes(reference).values()) {
					if (field.isSearchableInAnyScope()) {
						consumer.accept(
							new FieldAddress(collectionId,
								FulltextFieldKey.referenceAttribute(reference.getName(), field.getName())),
							new FieldUse(field.getAnalyzer(), locales)
						);
					}
				}
			}
		}
	}

	/**
	 * Identifies a searchable field even when its collection is renamed.
	 * @param collectionId primary key of the owning collection
	 * @param key field kind and its name within the collection
	 */
	private record FieldAddress(int collectionId, @Nonnull FulltextFieldKey key) {}

	/**
	 * Captures a field's analyzer and the locales it can use at validation time.
	 * @param analyzer field's analyzer name
	 * @param locales locales of the owning collection, in which the localized field holds its values
	 */
	private record FieldUse(@Nonnull String analyzer, @Nonnull Set<Locale> locales) {}

	/**
	 * Starting snapshot and last behavior verdict owned by a schema-authoring session.
	 * The sealed catalog schema's entity accessor follows live changes in warm-up, so analyzer assignments
	 * are copied by field identity at session entry. Locales are read only at validation time. The session's thread
	 * and the engine mutation executor both use it; the verdict is a cache whose check-then-set is not atomic, so
	 * two racing validations can at worst both analyze.
	 */
	@ThreadSafe
	public static final class SessionChecks {
		/** Original immutable analyzer, splitting and resource declarations. */
		@Nonnull private final CatalogSchemaContract startSchema;
		/** Field identities and their custom analyzer assignments at session entry. */
		@Nonnull private final Map<FieldAddress, String> startFieldAssignments;
		/** Last set of inputs and its accepted or refused verdict, shared by the probe and final validation. */
		@Nullable private volatile Verdict verdict;
		/** A failed schema batch may have retained field changes despite restoring the catalog schema version. */
		private volatile boolean failedWarmUpSchemaBatch;

		/**
		 * Captures the analysis baseline while the session's starting schema is installed.
		 * @param catalog catalog providing stable field ownership
		 * @param startSchema schema at the start of the session or at entry to direct engine go-live
		 */
		public SessionChecks(@Nonnull CatalogContract catalog, @Nonnull CatalogSchemaContract startSchema) {
			this.startSchema = startSchema;
			if (startSchema.getFulltextAnalyzers().isEmpty()) {
				this.startFieldAssignments = Map.of();
			} else {
				final Map<FieldAddress, String> assignments = CollectionUtils.createHashMap(8);
				visitSearchableFields(catalog, startSchema, (address, field) -> {
					if (startSchema.getFulltextAnalyzers().containsKey(field.analyzer())) {
						assignments.put(address, field.analyzer());
					}
				});
				this.startFieldAssignments = Map.copyOf(assignments);
			}
		}

		/** Records a failed warm-up batch that reached mutation application. */
		public void markFailedWarmUpSchemaBatch() {
			this.failedWarmUpSchemaBatch = true;
		}

		/**
		 * Indicates whether final validation must account for any field edits retained by a failed schema batch.
		 * @return true when a warm-up schema batch failed after mutation application began
		 */
		public boolean hasFailedWarmUpSchemaBatch() {
			return this.failedWarmUpSchemaBatch;
		}
	}

	/**
	 * Exactly the inputs analyzed for one touched pair. The locale is part of the resolved context.
	 * @param resolved analysis phases and used resource content
	 * @param samples the declaration's own samples in order
	 * @param fieldUsed whether built-in samples and resource rules apply
	 */
	private record CheckInput(
		@Nonnull ResolvedFulltextAnalyzer resolved, @Nonnull List<String> samples, boolean fieldUsed
	) {}

	/**
	 * Last behavior result for a session, reusable across DDL that leaves the checks unchanged.
	 * @param inputs the exact inputs the verdict was computed from, compared by equality to decide reuse
	 * @param refusal joined diagnostics, or null for an accepted result
	 */
	private record Verdict(@Nonnull Set<CheckInput> inputs, @Nullable String refusal) {}

	/**
	 * Returns declaration-local samples, deliberately excluding inherited samples.
	 * @param schema schema containing the analyzer
	 * @param name analyzer name
	 * @return the declaration's samples, or the built-in declaration's empty samples
	 */
	@Nonnull
	private static List<String> samples(@Nonnull CatalogSchemaContract schema, @Nonnull String name) {
		return ResolvedFulltextAnalyzer.find(schema, name, null).getSamples();
	}

	/**
	 * Enumerates all resolved phases, including resources read before the words phase.
	 * @param resolved analyzer resolved for one language
	 * @return steps in phase order
	 */
	@Nonnull
	private static Stream<AnalysisStep> allSteps(@Nonnull ResolvedFulltextAnalyzer resolved) {
		return Stream.of(resolved.text(), resolved.splitting(), resolved.words()).flatMap(List::stream);
	}

	/**
	 * Reads an explicitly supplied resource parameter using the compiler's supported scalar/array forms.
	 * @param step step naming resources
	 * @param parameter descriptor's resource parameter name
	 * @return resource names in declaration order, empty when the parameter is omitted
	 */
	@Nonnull
	private static List<String> references(@Nonnull AnalysisStep step, @Nonnull String parameter) {
		final Serializable value = step.parameters().get(parameter);
		if (value instanceof String name) {
			return List.of(name);
		}
		if (value instanceof String[] names) {
			return Arrays.asList(names);
		}
		return List.of();
	}

	/**
	 * Evaluates the applicable rules in order, retaining the first failure for this pair.
	 * @param steps providers compiling the unstemmed comparison of protected words
	 * @param input analyzer to check, its samples and whether it has a searchable field in this locale
	 * @param compiled cached production index and query chains
	 * @return the first refusal, or null on success
	 */
	@Nullable
	private static String check(
		@Nonnull AnalysisStepRegistry steps, @Nonnull CheckInput input, @Nonnull CompiledFulltextAnalyzer compiled
	) {
		final ResolvedFulltextAnalyzer resolved = input.resolved();
		for (final String sample : input.samples()) {
			final String failure = checkSample(resolved, compiled, sample, false);
			if (failure != null) {
				return failure;
			}
		}
		if (!input.fieldUsed()) {
			return null;
		}
		for (final String sample : BuiltInFulltextSamples.forLocale(resolved.context().locale())) {
			final String failure = checkSample(resolved, compiled, sample, true);
			if (failure != null) {
				return failure;
			}
		}
		final String stopFailure = checkStopWords(resolved, compiled);
		if (stopFailure != null) {
			return stopFailure;
		}
		final String protectedFailure = checkProtected(steps, resolved, compiled);
		if (protectedFailure != null) {
			return protectedFailure;
		}
		return checkSynonyms(resolved, compiled);
	}

	/**
	 * Requires the first stop entries of the analyzer's stop steps to leave no index term.
	 * @param resolved resolved definition naming the stop resources
	 * @param compiled cached index and query chains
	 * @return the first stop-word failure, or null
	 */
	@Nullable
	private static String checkStopWords(
		@Nonnull ResolvedFulltextAnalyzer resolved, @Nonnull CompiledFulltextAnalyzer compiled
	) {
		int stops = 0;
		for (final AnalysisStep step : resolved.words()) {
			if (!AnalysisSteps.STOP_WORDS.equals(step.name())) {
				continue;
			}
			for (final String name : references(step, AnalysisSteps.WORDS)) {
				final WordList words = (WordList) resolved.context().getResource(name);
				for (final String word : words.words()) {
					if (stops++ >= MAX_STOP_WORDS) {
						break;
					}
					final Set<String> index = indexTerms(compiled.indexSurface(), word);
					if (!index.isEmpty()) {
						return failure(resolved, "2 stop words", word, index,
							TextMatchesTranslator.analyzeAlternatives(compiled, word, index));
					}
				}
			}
		}
		return null;
	}

	/**
	 * Requires every ordered pair among the first members of the first synonym groups to find each other.
	 * @param resolved resolved definition naming the synonym resources
	 * @param compiled cached index and query chains
	 * @return the first synonym failure, or null
	 */
	@Nullable
	private static String checkSynonyms(
		@Nonnull ResolvedFulltextAnalyzer resolved, @Nonnull CompiledFulltextAnalyzer compiled
	) {
		int groups = 0;
		for (final AnalysisStep step : resolved.words()) {
			if (!AnalysisSteps.SYNONYMS.equals(step.name())) {
				continue;
			}
			for (final String name : references(step, AnalysisSteps.SYNONYM_LISTS)) {
				final SynonymList synonyms = (SynonymList) resolved.context().getResource(name);
				for (final SynonymGroup group : synonyms.groups()) {
					if (groups++ >= MAX_SYNONYM_GROUPS) {
						break;
					}
					final List<String> members = group.words().subList(0,
						Math.min(group.words().size(), MAX_SYNONYM_MEMBERS));
					for (int a = 0; a < members.size(); a++) {
						for (int b = 0; b < members.size(); b++) {
							if (a == b) {
								continue;
							}
							final Set<String> index = indexTerms(compiled.indexSurface(), members.get(b));
							final String[][][] query = TextMatchesTranslator.analyzeAlternatives(
								compiled, members.get(a), index);
							if (searchMatch(query, index) != QueryMatch.MATCH) {
								return failure(resolved, "4 synonyms", members.get(a) + " -> " + members.get(b),
									index, query);
							}
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Checks typed and lower-cased text for every sample that yields postings. The diacritic check applies only
	 * to built-in language samples, which describe that language's normalization. Author samples must have
	 * index terms, a surviving search word in every form, and fit the scorer's pre-filter word limit.
	 * @param resolved analyzer and language for diagnostics
	 * @param compiled actual index and search chains
	 * @param sample raw sample text
	 * @param builtIn whether the word is from the language sample list
	 * @return rule 1 refusal, or null if the sample passes or a built-in word yields no index or search terms
	 */
	@Nullable
	private static String checkSample(
		@Nonnull ResolvedFulltextAnalyzer resolved, @Nonnull CompiledFulltextAnalyzer compiled,
		@Nonnull String sample, boolean builtIn
	) {
		final Set<String> index = indexTerms(compiled.indexSurface(), sample);
		if (index.isEmpty() && builtIn) {
			return null;
		}
		final String lower = sample.toLowerCase(Locale.ROOT);
		final String[] forms = builtIn ?
			new String[] { sample, lower, Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "") } :
			new String[] { sample, lower };
		for (int i = 0; i < forms.length; i++) {
			if (i > 0 && forms[i].equals(forms[i - 1])) {
				continue;
			}
			final String[][][] query = TextMatchesTranslator.analyzeAlternatives(compiled, forms[i], index);
			final String form = SAMPLE_FORMS.get(i);
			if (!builtIn && query.length > FulltextPhaseOneScorer.MAX_QUERY_TOKENS) {
				return failure(resolved, "1 self-match (" + form + " has " + query.length +
					" words, textMatches accepts at most " + FulltextPhaseOneScorer.MAX_QUERY_TOKENS + " words)",
					sample, index, query);
			}
			if (index.isEmpty()) {
				return failure(resolved, "1 self-match (empty index terms)", sample, index, query);
			}
			final QueryMatch match = searchMatch(query, index);
			if (match == QueryMatch.EMPTY) {
				return builtIn ? null :
					failure(resolved, "1 self-match (" + form + ", empty search alternatives)", sample, index, query);
			}
			if (match == QueryMatch.MISS) {
				return failure(resolved, "1 self-match (" + form + ")", sample, index, query);
			}
		}
		return null;
	}

	/**
	 * Compares protected words with the same index chain in which the stemming step naming the protected resource
	 * leaves every word unstemmed, while the normalization that stemmer embeds still applies. The comparison chain is
	 * compiled once per naming step from the steps the production chain already compiled, closed even on failure, and
	 * never enters the catalog cache. Built-in stemmers declare the protected-word contract; an external resource
	 * parameter alone does not establish that its provider stems, so no protected-word semantics are inferred for an
	 * arbitrary provider.
	 * @param steps installed step registry compiling the comparison chain
	 * @param resolved resolved definition
	 * @param compiled cached index and query chains
	 * @return the first protected-word failure, or null
	 */
	@Nullable
	private static String checkProtected(
		@Nonnull AnalysisStepRegistry steps, @Nonnull ResolvedFulltextAnalyzer resolved,
		@Nonnull CompiledFulltextAnalyzer compiled
	) {
		int checked = 0;
		for (int i = 0; i < resolved.words().size() && checked < MAX_PROTECTED_WORDS; i++) {
			final AnalysisStep step = resolved.words().get(i);
			if (!(steps.resolveFulltext(step) instanceof AbstractStemmingAnalysisStepFactory)) {
				continue;
			}
			final List<String> resources = references(step, AnalysisSteps.PROTECTED_WORDS);
			if (resources.isEmpty()) {
				continue;
			}
			try (final FulltextAnalyzer comparison = new FulltextAnalyzer(resolved.analyzer(),
				new FulltextChainCompiler(steps).compileUnstemmedIndexChain(resolved, i))) {
				for (final String name : resources) {
					final FulltextResourceContent content = resolved.context().getResource(name);
					if (!(content instanceof WordList words)) {
						continue;
					}
					for (final String word : words.words()) {
						if (checked++ >= MAX_PROTECTED_WORDS) {
							break;
						}
						final Set<String> index = indexTerms(compiled.indexSurface(), word);
						final Set<String> expected = indexTerms(comparison, word);
						if (!index.equals(expected)) {
							return failure(resolved, "3 protected words (without stemmer: " +
								expected.stream().limit(MAX_DIAGNOSTIC_TERMS).toList() + ")", word, index,
								TextMatchesTranslator.analyzeAlternatives(compiled, word, index));
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Drives the same full index chain and callback as field indexing, retaining only distinct nonempty terms.
	 * @param analyzer full compiled index chain
	 * @param text field value element
	 * @return distinct terms which would enter postings
	 */
	@Nonnull
	private static Set<String> indexTerms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		final Set<String> terms = CollectionUtils.createLinkedHashSet(8);
		analyzer.analyzeIndexed(text, (term, preStem, surface, start, end, increment) -> {
			if (!term.isEmpty()) {
				terms.add(term);
			}
		});
		return terms;
	}

	/**
	 * Applies the planner's all-words rule and requires at least one surviving word, as a closed query does.
	 * @param query alternatives grouped by word and part; an empty word is skipped
	 * @param index terms of the candidate document
	 * @return whether search was empty, missed a mandatory word, or matched all surviving words
	 */
	@Nonnull
	private static QueryMatch searchMatch(@Nonnull String[][][] query, @Nonnull Set<String> index) {
		boolean anySurvives = false;
		for (final String[][] word : query) {
			boolean survives = false;
			boolean found = false;
			for (final String[] part : word) {
				for (final String alternative : part) {
					survives = true;
					found |= index.contains(alternative);
				}
			}
			if (survives && !found) {
				return QueryMatch.MISS;
			}
			anySurvives |= survives;
		}
		return anySurvives ? QueryMatch.MATCH : QueryMatch.EMPTY;
	}

	/** Outcomes of comparing the planner's surviving search words to the indexed sample. */
	private enum QueryMatch {
		/** The search chains dropped every word. */
		EMPTY,
		/** A surviving mandatory word had no exact index term. */
		MISS,
		/** At least one word survived and every survivor found an exact term. */
		MATCH
	}

	/**
	 * Formats one bounded diagnostic while retaining actual terms from both production sides.
	 * @param resolved analyzer and language
	 * @param rule violated rule number and name
	 * @param sample offending text or ordered synonym pair
	 * @param index actual index terms
	 * @param query actual search alternatives
	 * @return one message with at most 80 sample characters and ten distinct terms per side
	 */
	@Nonnull
	private static String failure(
		@Nonnull ResolvedFulltextAnalyzer resolved, @Nonnull String rule, @Nonnull String sample,
		@Nonnull Set<String> index, @Nonnull String[][][] query
	) {
		final List<String> alternatives = Arrays.stream(query).flatMap(Arrays::stream).flatMap(Arrays::stream)
			.distinct().limit(MAX_DIAGNOSTIC_TERMS).toList();
		final Locale locale = resolved.context().locale();
		final String language = locale.equals(Locale.ROOT) ? "root (all-languages definition)" : locale.toLanguageTag();
		return "Fulltext analyzer `" + resolved.analyzer() + "`, language `" + language + "`, rule " + rule +
			", sample `" + sample.substring(0, Math.min(MAX_DIAGNOSTIC_SAMPLE_LENGTH, sample.length())) +
			"`: index terms " + index.stream().limit(MAX_DIAGNOSTIC_TERMS).toList() + "; search alternatives " +
			alternatives + ".";
	}
}
