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

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.filter.InField;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.AttributeNotSearchableException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.fulltext.FulltextFieldDefinition;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.analysis.AnalyzedTermConsumer;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Translates {@link TextMatches} into a {@link FulltextFormula}: the entities whose searchable fields contain every
 * word of the text, ranked on request by the `relevance()` sorter.
 *
 * ## From text to formula
 *
 * The text is analyzed into **tokens** - the analyzer emits a term per word, and the extra terms it emits at the same
 * position (`positionIncrement == 0`, the variant stems of the search-side chain) belong to the word before them. Each
 * token then looks its terms up in every searched field of the {@link FulltextIndex} of the query's locale; every
 * (field, term) with postings becomes one {@link ExpandedTerm} of the token. Only exact terms and stem variants are
 * expanded - prefix completion and typo tolerance would only add terms to a token.
 *
 * ## Which analyzer reads the text
 *
 * The locale splits NFC text once, before any group analyzes words. When all processed groups have equal resolved
 * query text steps, those steps precede the shared split. Otherwise splitting uses the raw text: a boundary a group's
 * text step would remove remains a boundary for that group, which can cost recall. Fields sharing compiled analyzer
 * content form one group. Each group applies its words phase around a keyword tokenizer to each shared term,
 * preserving alignment - after the raw split preceded by its own query text phase, after the text-aware split
 * without it, so that no text step runs twice on one term. A field whose recorded index fingerprint
 * or pivot differs from its current schema contributes nothing until a write starts a fresh generation (#409).
 *
 * ## Where the fulltext lives
 *
 * The fulltext index lives in the {@link GlobalEntityIndex} of each scope only, never in a reduced index, so the
 * formula is computed once per scope and memoised on the root planning context
 * ({@link QueryPlanningContext#computeOnlyOnce}), keyed by the global index and the constraint - two different texts or
 * field sets never share an entry. A reduced target index gets the scope's global formula AND-ed with its own primary
 * keys - the "Shape P" the substring accelerator established (see
 * `AbstractAttributeStringSearchTranslator#resolveFromIndex`). Several scopes yield one formula each, so a LIVE +
 * ARCHIVED query never pairs one scope's answer with the other's index. No prefetch alternative is offered: the
 * formula always computes from the index.
 *
 * ## What fails at planning time
 *
 * The engine neither filters nor sorts over data it keeps no index for, so a query that cannot be answered from a
 * fulltext index fails rather than returning an empty result: no locale, no searchable field in some processed scope,
 * an `inField` naming an unknown field or one not searchable in a processed scope, `textMatches` anywhere under
 * `userFilter` (the query model rejects only a direct child), inside a reference-scoped container, or with more words
 * than the scorer can count. Every check runs before any index is consulted, so the query never fails or passes
 * depending on the data. A searchable field the index retired or never saw simply contributes nothing.
 *
 * A text whose analysis yields no token at all - stop words or punctuation only - matches nothing: the strict match
 * set of zero tokens is empty by the same rule that makes a token without terms match nothing, and an empty search box
 * is already handled by the factory, which drops the constraint. A stop word typed without diacritics, which the
 * search chain does not recognize but the index dropped, is not asked for at all - see `createFormula`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TextMatchesTranslator implements FilteringConstraintTranslator<TextMatches>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull TextMatches textMatches, @Nonnull FilterByVisitor filterByVisitor) {
		Assert.isTrue(
			filterByVisitor.isOutsideUserFilter(),
			() -> new EvitaInvalidUsageException(
				"Constraint `textMatches` is forbidden in `userFilter` at any depth - a fulltext search belongs to " +
					"the mandatory part of the filter, so that facets and histograms describe the search result!"
			)
		);
		Assert.isTrue(
			filterByVisitor.isEntityTypeKnown(),
			() -> new EvitaInvalidUsageException(
				"Constraint `textMatches` requires the queried entity collection to be specified!"
			)
		);
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema();
		Assert.isTrue(
			referenceSchema == null,
			() -> new EvitaInvalidUsageException(
				"Constraint `textMatches` cannot be used inside a container scoped to the reference `" +
					referenceSchema.getName() + "` - it searches the fields of the entity itself, and the searchable " +
					"attributes of its references are searched by a top-level `textMatches` without `inField`!"
			)
		);
		final Locale locale = filterByVisitor.getLocale();
		Assert.isTrue(
			locale != null,
			() -> new EvitaInvalidUsageException(
				"Constraint `textMatches` requires the query locale - add `entityLocaleEquals` to the filter, " +
					"the fulltext index is kept per locale!"
			)
		);

		final EntitySchemaContract entitySchema = processingScope.getEntitySchemaOrThrowException();
		final Set<Scope> scopes = processingScope.getScopes();
		final Map<Scope, FulltextFieldDefinition[]> fieldsByScope = resolveFields(textMatches, entitySchema, scopes);
		// Writes validate locales, so an undeclared locale cannot own a fulltext index. Keep field errors above.
		if (!entitySchema.getLocales().contains(locale)) {
			return EmptyFormula.INSTANCE;
		}

		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final FulltextAnalyzerRegistry analyzerRegistry = queryContext.getFulltextAnalyzerRegistry();
		final String entityType = entitySchema.getName();
		final CatalogSchemaContract catalogSchema = queryContext.getCatalogSchema();
		// All field definitions compile before any index containing data is read, including the check-only path.
		final Set<CompiledFulltextAnalyzer> analyzers = CollectionUtils.createLinkedHashSet(4);
		final Map<String, CompiledFulltextAnalyzer> resolved = CollectionUtils.createHashMap(4);
		final Map<Scope, Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>>> fieldsByAnalyzer =
			new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, FulltextFieldDefinition[]> entry : fieldsByScope.entrySet()) {
			final Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> grouped =
				CollectionUtils.createLinkedHashMap(4);
			for (final FulltextFieldDefinition field : entry.getValue()) {
				// Keep the resolved handle across analysis and grouping: eviction may change a later lookup's identity.
				final CompiledFulltextAnalyzer analyzer = resolved.computeIfAbsent(field.analyzer(),
					name -> analyzerRegistry.getCompiledAnalyzer(catalogSchema, name, locale));
				analyzers.add(analyzer);
				grouped.computeIfAbsent(analyzer, ignored -> new ArrayList<>(4)).add(field);
			}
			fieldsByAnalyzer.put(entry.getKey(), grouped);
		}
		final CompiledFulltextAnalyzer first = analyzers.iterator().next();
		boolean commonText = true;
		for (final CompiledFulltextAnalyzer analyzer : analyzers) {
			if (!first.queryText().equals(analyzer.queryText())) {
				commonText = false;
				break;
			}
		}
		final QueryToken[] searchTokens = analyze(commonText ? first.textSplitting() : first.splitting(),
			textMatches.getText());
		final Map<CompiledFulltextAnalyzer, String[][]> analyzed = CollectionUtils.createHashMap(analyzers.size());
		for (final CompiledFulltextAnalyzer analyzer : analyzers) {
			analyzed.put(analyzer, analyzeGroups(analyzer, searchTokens, commonText));
		}
		final Map<Scope, List<AnalyzedFieldGroup>> groupsByScope = new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>>> entry :
			fieldsByAnalyzer.entrySet()) {
			final List<AnalyzedFieldGroup> groups = new ArrayList<>(entry.getValue().size());
			for (final Map.Entry<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> group :
				entry.getValue().entrySet()) {
				groups.add(new AnalyzedFieldGroup(group.getKey(),
					group.getValue().toArray(FulltextFieldDefinition[]::new), analyzed.get(group.getKey())));
			}
			groupsByScope.put(entry.getKey(), groups);
		}
		if (filterByVisitor.isConstraintCheckOnly()) {
			// the constraint has been checked against the schema - a check reads no index holding data
			return EmptyFormula.INSTANCE;
		}

		final Map<Scope, Formula> formulasByScope = new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, FulltextFieldDefinition[]> entry : fieldsByScope.entrySet()) {
			final Scope scope = entry.getKey();
			final GlobalEntityIndex globalIndex = queryContext
				.getGlobalEntityIndexIfExists(entityType, scope)
				.orElse(null);
			final FulltextIndex fulltextIndex = globalIndex == null ? null : globalIndex.getFulltextIndex(locale);
			if (fulltextIndex == null || searchTokens.length == 0) {
				formulasByScope.put(scope, EmptyFormula.INSTANCE);
			} else {
				formulasByScope.put(
					scope,
					queryContext.computeOnlyOnce(
						List.of((EntityIndex) globalIndex), textMatches,
						() -> createFormula(fulltextIndex, groupsByScope.get(scope), searchTokens)
					)
				);
			}
		}

		return filterByVisitor.applyOnIndexes(
			entityIndex -> resolveFromIndex(entityIndex, formulasByScope)
		);
	}

	/**
	 * Resolves the fields the constraint searches in every processed scope, and verifies they may be searched there.
	 *
	 * Without `inField` the constraint searches every searchable field of the collection in the scope: its entity
	 * attributes (global attributes included), the attributes of its references and its associated data. With
	 * `inField` it searches the named entity or global attributes only - duplicates are de-duplicated, and each must be
	 * searchable in every processed scope, as an attribute filter must be filterable in every one. Reference attributes
	 * and associated data are reachable only through the default; naming them needs a specification constraint of
	 * their own.
	 *
	 * @param textMatches  the constraint
	 * @param entitySchema schema of the queried collection
	 * @param scopes       the processed scopes
	 * @return the searched fields per scope, never empty
	 * @throws AttributeNotFoundException when an `inField` names an attribute the collection does not declare
	 * @throws AttributeNotSearchableException when an `inField` names an attribute that is not searchable at all, or
	 *                                        not in some processed scope
	 * @throws EvitaInvalidUsageException     when a processed scope has no searchable field at all
	 */
	@Nonnull
	private static Map<Scope, FulltextFieldDefinition[]> resolveFields(
		@Nonnull TextMatches textMatches,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Set<Scope> scopes
	) {
		final Map<Scope, FulltextFieldDefinition[]> result = new EnumMap<>(Scope.class);
		final InField[] inFields = textMatches.getInFields();
		if (inFields.length == 0) {
			for (final Scope scope : scopes) {
				final FulltextFieldDefinition[] fields = collectSearchableFields(entitySchema, scope);
				Assert.isTrue(
					fields.length > 0,
					() -> new EvitaInvalidUsageException(
						"Constraint `textMatches` cannot be used on the entity collection `" + entitySchema.getName() +
							"` in scope `" + scope + "` - the collection declares no attribute or associated data " +
							"searchable there. Make at least one localized String attribute or associated data " +
							"`searchable` in the schema!"
					)
				);
				result.put(scope, fields);
			}
		} else {
			final Set<String> names = CollectionUtils.createLinkedHashSet(inFields.length);
			for (final InField inField : inFields) {
				names.add(inField.getFieldName());
			}
			final FulltextFieldDefinition[] fields = new FulltextFieldDefinition[names.size()];
			int index = 0;
			for (final String name : names) {
				final AttributeSchemaContract attributeSchema = entitySchema
					.getAttribute(name)
					.orElseThrow(() -> new AttributeNotFoundException(name, entitySchema));
				Assert.isTrue(
					attributeSchema.isSearchableInAnyScope(),
					() -> new AttributeNotSearchableException(name, entitySchema)
				);
				for (final Scope scope : scopes) {
					Assert.isTrue(
						attributeSchema.isSearchableInScope(scope),
						() -> new AttributeNotSearchableException(name, entitySchema, scope)
					);
				}
				fields[index++] = FulltextFieldDefinition.resolve(entitySchema, FulltextFieldKey.attribute(name));
			}
			for (final Scope scope : scopes) {
				result.put(scope, fields);
			}
		}
		return result;
	}

	/**
	 * Collects every field of the collection that is searchable in the scope: its entity attributes, global ones
	 * included, the attributes of each of its references, and its associated data.
	 *
	 * @param entitySchema schema of the queried collection
	 * @param scope        the scope
	 * @return the searchable fields, empty when there is none
	 */
	@Nonnull
	private static FulltextFieldDefinition[] collectSearchableFields(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Scope scope
	) {
		final List<FulltextFieldDefinition> fields = new ArrayList<>(8);
		for (final AttributeSchemaContract attributeSchema : entitySchema.getAttributes().values()) {
			if (attributeSchema.isSearchableInScope(scope)) {
				fields.add(FulltextFieldDefinition.resolve(entitySchema,
					FulltextFieldKey.attribute(attributeSchema.getName())));
			}
		}
		for (final ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
			for (final AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
				if (attributeSchema.isSearchableInScope(scope)) {
					fields.add(
						FulltextFieldDefinition.resolve(entitySchema,
							FulltextFieldKey.referenceAttribute(referenceSchema.getName(), attributeSchema.getName()))
					);
				}
			}
		}
		for (final AssociatedDataSchemaContract associatedDataSchema : entitySchema.getAssociatedData().values()) {
			if (associatedDataSchema.isSearchableInScope(scope)) {
				fields.add(FulltextFieldDefinition.resolve(entitySchema,
					FulltextFieldKey.associatedData(associatedDataSchema.getName())));
			}
		}
		return fields.toArray(FulltextFieldDefinition[]::new);
	}

	/**
	 * One analyzer's searched fields and alternatives aligned with the shared query words.
	 *
	 * @param analyzer the shared compiled content identity
	 * @param fields the fields searched in this scope
	 * @param alternatives each word's analyzed alternatives, empty when this group drops the word
	 */
	private record AnalyzedFieldGroup(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull FulltextFieldDefinition[] fields,
		@Nonnull String[][] alternatives
	) { }

	/**
	 * Runs a group's word chain over every term of each shared word. Position increments emitted inside the word
	 * chain never create a new query word; every nonempty emitted term is an alternative of that shared input word.
	 * After the raw split the chain applies the group's query text phase to each word first; after the text-aware
	 * split the words have been through that phase already, and the chain without it keeps a text step that is not
	 * idempotent from changing them a second time.
	 *
	 * @param analyzer the group's compiled chains
	 * @param words the locale's shared words
	 * @param textSplit whether the words come from the text-aware split, which applied the common query text phase
	 * @return alternatives aligned with the shared words
	 */
	@Nonnull
	private static String[][] analyzeGroups(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull QueryToken[] words, boolean textSplit
	) {
		final FulltextAnalyzer wordChain = textSplit ? analyzer.textSplitWord() : analyzer.word();
		final String[][] alternatives = new String[words.length][];
		for (int i = 0; i < words.length; i++) {
			final Set<String> terms = CollectionUtils.createLinkedHashSet(4);
			for (final String term : words[i].terms()) {
				wordChain.analyze(term, (value, surface, start, end, increment) -> {
					if (!value.isEmpty()) {
						terms.add(value);
					}
				});
			}
			alternatives[i] = terms.toArray(String[]::new);
		}
		return alternatives;
	}

	/**
	 * Analyzes the text into tokens: each term with a positive position increment starts a new token, and each term
	 * at the same position (`positionIncrement == 0`) joins the token before it as an alternative. A word-number split
	 * therefore yields separate words: a typed `UHD7800` needs `uhd7800` or `uhd`, and `7800`, which over an index
	 * split the same way finds what Lucene's `uhd7800 OR (uhd AND 7800)` finds. Regrouping the parts with their
	 * original by offset containment - the original or any part, ranked by how much of the group matched
	 * (`p5-word-number-split-comparison.md` §6) - waits for a ranking lane that puts a whole-word match above a part
	 * match: without it, the matches of a bare part would come with nothing to rank them down.
	 *
	 * The caller supplies text-aware splitting only when every group's resolved query text phase is equal.
	 * With heterogeneous phases it supplies raw splitting: a word boundary a group's text step removes is then
	 * not removed for that group. Either choice is independent of group iteration order. Char filters correct
	 * offsets to the original NFC input, so the collected surface still addresses what the user typed.
	 *
	 * @param analyzer the shared splitting chain, optionally preceded by the common query text phase
	 * @param text     the text of the query
	 * @return the tokens in text order; empty when the analysis produced no term
	 * @throws EvitaInvalidUsageException when the text has more tokens than the scorer can count
	 */
	@Nonnull
	static QueryToken[] analyze(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		final TokenCollector collector = new TokenCollector();
		analyzer.analyze(text, collector);
		final QueryToken[] tokens = collector.getTokens();
		Assert.isTrue(
			tokens.length <= FulltextPhaseOneScorer.MAX_QUERY_TOKENS,
			() -> new EvitaInvalidUsageException(
				"The text of `textMatches` consists of " + tokens.length + " words, at most " +
					FulltextPhaseOneScorer.MAX_QUERY_TOKENS + " are supported!"
			)
		);
		return tokens;
	}

	/**
	 * Looks up each group's alternatives only in its current field generations. A shared word leaves the query only
	 * when every group with a current field drops it. Groups without current fields do not participate.
	 * Folded stop recognition is per group and is suppressed by any corpus collision,
	 * including postings of retired fields. A kept word with no expansion remains a word that matches nothing.
	 *
	 * @param fulltextIndex the locale index of this scope
	 * @param groups the analyzed groups and their schema field identities
	 * @param tokens the shared words with their original surface forms
	 * @return the fulltext formula, or empty when all words were dropped
	 */
	@Nonnull
	private static Formula createFormula(
		@Nonnull FulltextIndex fulltextIndex, @Nonnull List<AnalyzedFieldGroup> groups,
		@Nonnull QueryToken[] tokens
	) {
		final List<int[]> fieldIds = new ArrayList<>(groups.size());
		for (final AnalyzedFieldGroup group : groups) {
			final int[] ids = new int[group.fields().length];
			int count = 0;
			for (final FulltextFieldDefinition field : group.fields()) {
				final int id = fulltextIndex.getFieldId(field.key());
				if (id != FulltextIndex.UNKNOWN_FIELD_ID && fulltextIndex.isFieldCurrent(
					id, group.analyzer().fingerprint().index(), field.lengthPivot())) {
					ids[count++] = id;
				}
			}
			fieldIds.add(Arrays.copyOf(ids, count));
		}
		final List<ExpandedTerm[]> expandedTokens = new ArrayList<>(tokens.length);
		for (int i = 0; i < tokens.length; i++) {
			final List<ExpandedTerm> expansions = new ArrayList<>(8);
			boolean kept = false;
			for (int g = 0; g < groups.size(); g++) {
				if (fieldIds.get(g).length == 0) {
					continue;
				}
				final AnalyzedFieldGroup group = groups.get(g);
				final String[] alternatives = group.alternatives()[i];
				if (alternatives.length == 0) {
					continue;
				}
				final int before = expansions.size();
				for (final int fieldId : fieldIds.get(g)) {
					for (final String term : alternatives) {
						final ExpandedTerm expanded = lookUpTerm(fulltextIndex, fieldId, term);
						if (expanded != null) {
							expansions.add(expanded);
						}
					}
				}
				if (expansions.size() == before && group.analyzer().isFoldedStopWord(tokens[i].surfaceForm()) &&
					!isKnownToIndex(fulltextIndex, new QueryToken(tokens[i].surfaceForm(), alternatives))) {
					continue;
				}
				kept = true;
			}
			if (kept) {
				expandedTokens.add(expansions.toArray(ExpandedTerm[]::new));
			}
		}
		return expandedTokens.isEmpty() ? EmptyFormula.INSTANCE :
			new FulltextFormula(expandedTokens.toArray(ExpandedTerm[][]::new));
	}

	/**
	 * Returns whether the index holds any term of the token in any of its fields, searched or not, retired or not.
	 *
	 * @param fulltextIndex the fulltext index
	 * @param token         the query token
	 * @return true when some field holds some term of the token
	 */
	private static boolean isKnownToIndex(@Nonnull FulltextIndex fulltextIndex, @Nonnull QueryToken token) {
		final int fieldCount = fulltextIndex.getFieldCount();
		for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
			for (final String term : token.terms()) {
				if (lookUpTerm(fulltextIndex, fieldId, term) != null) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Looks up one exact term of one field, reading the postings and impacts from the leaf the dictionary cursor stands
	 * on - one descent, and no copy of the impacts. The walk starts at the term itself, the smallest key carrying it as
	 * a prefix, so the first key visited is the term when the field holds it.
	 *
	 * @param fulltextIndex the fulltext index
	 * @param fieldId       id of the field
	 * @param term          the analyzed term
	 * @return the expanded term, or null when the field does not hold the term
	 */
	@Nullable
	private static ExpandedTerm lookUpTerm(@Nonnull FulltextIndex fulltextIndex, int fieldId, @Nonnull String term) {
		final ExpandedTerm[] found = new ExpandedTerm[1];
		fulltextIndex.forEachTerm(
			fieldId, term,
			(visitedTerm, postings, impacts) -> {
				if (visitedTerm.equals(term) && !postings.isEmpty()) {
					found[0] = new ExpandedTerm(fieldId, visitedTerm, postings, impacts, 0);
				}
				return false;
			}
		);
		return found[0];
	}

	/**
	 * Returns the formula of one target index: the scope's global formula for the global index, and the global formula
	 * AND-ed with the index's own primary keys for a reduced one. Both speak owner entity primary keys, so the
	 * intersection is exactly the part of the global answer that index holds.
	 *
	 * @param entityIndex     the target index
	 * @param formulasByScope the global formula of every processed scope
	 * @return the formula of the index
	 */
	@Nonnull
	private static Formula resolveFromIndex(
		@Nonnull EntityIndex entityIndex,
		@Nonnull Map<Scope, Formula> formulasByScope
	) {
		final Formula globalFormula = formulasByScope.get(entityIndex.getIndexKey().scope());
		if (globalFormula == null || globalFormula instanceof EmptyFormula) {
			return EmptyFormula.INSTANCE;
		}
		return entityIndex instanceof GlobalEntityIndex ?
			globalFormula :
			FormulaFactory.and(entityIndex.getAllPrimaryKeysFormula(), globalFormula);
	}

	/**
	 * One word of the query text as the analyzer produced it.
	 *
	 * @param surfaceForm the word as written, a slice of the NFC-normalized text - the form a stop-word test reads
	 * @param terms       the distinct terms the analyzer emitted at the word's position, its variant stems included
	 */
	record QueryToken(@Nonnull String surfaceForm, @Nonnull String[] terms) {
	}

	/**
	 * Groups the terms an analyzer emits into tokens - see {@link #analyze(FulltextAnalyzer, String)}.
	 */
	private static final class TokenCollector implements AnalyzedTermConsumer {
		/**
		 * The finished tokens.
		 */
		private final List<QueryToken> tokens = new ArrayList<>(8);
		/**
		 * The distinct terms of the token being collected.
		 */
		private final List<String> currentToken = new ArrayList<>(4);
		/**
		 * The surface form of the token being collected, null before its first term.
		 */
		@Nullable private String currentSurfaceForm;

		@Override
		public void accept(
			@Nonnull String term,
			@Nonnull Supplier<String> surfaceForm,
			int startOffset,
			int endOffset,
			int positionIncrement
		) {
			if (positionIncrement > 0) {
				flush();
			}
			if (this.currentSurfaceForm == null) {
				this.currentSurfaceForm = surfaceForm.get();
			}
			// a variant stem may repeat the term before it - one lookup per distinct term is enough
			if (!this.currentToken.contains(term)) {
				this.currentToken.add(term);
			}
		}

		/**
		 * Returns the collected tokens, finishing the one being collected.
		 *
		 * @return the tokens in text order
		 */
		@Nonnull
		QueryToken[] getTokens() {
			flush();
			return this.tokens.toArray(QueryToken[]::new);
		}

		/**
		 * Finishes the token being collected, if any.
		 */
		private void flush() {
			if (!this.currentToken.isEmpty()) {
				this.tokens.add(
					new QueryToken(
						Objects.requireNonNull(this.currentSurfaceForm), this.currentToken.toArray(String[]::new)
					)
				);
				this.currentToken.clear();
			}
			this.currentSurfaceForm = null;
		}

	}

}
