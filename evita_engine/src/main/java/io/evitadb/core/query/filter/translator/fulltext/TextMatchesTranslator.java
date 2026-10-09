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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
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
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.analysis.AnalyzedTermConsumer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
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
 * (field, term) with postings becomes one {@link ExpandedTerm} of the token. This slice expands exact terms and stem
 * variants only - prefix completion and typo tolerance come later and only add terms to a token.
 *
 * ## Which analyzer reads the text
 *
 * The analyzer must produce the terms the index was built with, or the two sides stop meeting. The schema's current
 * assignment normally says which analyzer that is, and its search-side half - the one that folds diacritics and emits
 * every stem a word could have had - is what lets a query typed without accents find the accented value. But an index
 * is built once and keeps the analyzer it was built with ({@link FulltextIndex#getAnalyzerName()}), while the
 * assignment may have moved on since; until #409 reindexes, the search-side half of the *new* assignment would
 * produce terms of a chain the index never saw. So the search analyzer is used only while the assignment's index
 * analyzer is still the index's own, and the index's own analyzer reads the text otherwise: recall drops (no variant
 * stems), but nothing wrong is ever found - degrade, never wrong.
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
		final Map<Scope, FulltextFieldKey[]> fieldsByScope = resolveFields(textMatches, entitySchema, scopes);
		if (filterByVisitor.isConstraintCheckOnly()) {
			// the constraint has been checked against the schema - a check reads no index holding data
			return EmptyFormula.INSTANCE;
		}

		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final FulltextAnalyzerRegistry analyzerRegistry = queryContext.getFulltextAnalyzerRegistry();
		final String entityType = entitySchema.getName();
		// the text is analyzed and its word count checked whether or not any scope keeps an index for the locale, so
		// that a query never fails or passes depending on the data
		final FulltextAnalyzer searchAnalyzer = analyzerRegistry.getSearchAnalyzer(entityType, locale);
		final QueryToken[] searchTokens = analyze(searchAnalyzer, textMatches.getText());

		final Map<Scope, Formula> formulasByScope = new EnumMap<>(Scope.class);
		for (Map.Entry<Scope, FulltextFieldKey[]> entry : fieldsByScope.entrySet()) {
			final Scope scope = entry.getKey();
			final GlobalEntityIndex globalIndex = queryContext
				.getGlobalEntityIndexIfExists(entityType, scope)
				.orElse(null);
			final FulltextIndex fulltextIndex = globalIndex == null ? null : globalIndex.getFulltextIndex(locale);
			if (fulltextIndex == null || searchTokens.length == 0) {
				formulasByScope.put(scope, EmptyFormula.INSTANCE);
			} else {
				final FulltextFieldKey[] fields = entry.getValue();
				formulasByScope.put(
					scope,
					queryContext.computeOnlyOnce(
						List.of((EntityIndex) globalIndex),
						textMatches,
						() -> createFormula(
							fulltextIndex, fields, analyzerRegistry,
							resolveTokens(
								analyzerRegistry, entityType, locale, fulltextIndex, textMatches.getText(), searchTokens
							)
						)
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
	 * attributes (global attributes included) and the attributes of its references. With `inField` it searches the
	 * named entity or global attributes only - duplicates are de-duplicated, and each must be searchable in every
	 * processed scope, as an attribute filter must be filterable in every one. Reference attributes are reachable only
	 * through the default in this slice; naming them needs its own specification constraint.
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
	private static Map<Scope, FulltextFieldKey[]> resolveFields(
		@Nonnull TextMatches textMatches,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Set<Scope> scopes
	) {
		final Map<Scope, FulltextFieldKey[]> result = new EnumMap<>(Scope.class);
		final InField[] inFields = textMatches.getInFields();
		if (inFields.length == 0) {
			for (Scope scope : scopes) {
				final FulltextFieldKey[] fields = collectSearchableFields(entitySchema, scope);
				Assert.isTrue(
					fields.length > 0,
					() -> new EvitaInvalidUsageException(
						"Constraint `textMatches` cannot be used on the entity collection `" + entitySchema.getName() +
							"` in scope `" + scope + "` - the collection declares no attribute searchable there. " +
							"Make at least one localized String attribute `searchable` in the schema!"
					)
				);
				result.put(scope, fields);
			}
		} else {
			final Set<String> names = CollectionUtils.createLinkedHashSet(inFields.length);
			for (InField inField : inFields) {
				names.add(inField.getFieldName());
			}
			final FulltextFieldKey[] fields = new FulltextFieldKey[names.size()];
			int index = 0;
			for (String name : names) {
				final AttributeSchemaContract attributeSchema = entitySchema
					.getAttribute(name)
					.orElseThrow(() -> new AttributeNotFoundException(name, entitySchema));
				Assert.isTrue(
					attributeSchema.isSearchableInAnyScope(),
					() -> new AttributeNotSearchableException(name, entitySchema)
				);
				for (Scope scope : scopes) {
					Assert.isTrue(
						attributeSchema.isSearchableInScope(scope),
						() -> new AttributeNotSearchableException(name, entitySchema, scope)
					);
				}
				fields[index++] = FulltextFieldKey.attribute(name);
			}
			for (Scope scope : scopes) {
				result.put(scope, fields);
			}
		}
		return result;
	}

	/**
	 * Collects every field of the collection that is searchable in the scope: its entity attributes, global ones
	 * included, and the attributes of each of its references.
	 *
	 * @param entitySchema schema of the queried collection
	 * @param scope        the scope
	 * @return the searchable fields, empty when there is none
	 */
	@Nonnull
	private static FulltextFieldKey[] collectSearchableFields(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Scope scope
	) {
		final List<FulltextFieldKey> fields = new ArrayList<>(8);
		for (AttributeSchemaContract attributeSchema : entitySchema.getAttributes().values()) {
			if (attributeSchema.isSearchableInScope(scope)) {
				fields.add(FulltextFieldKey.attribute(attributeSchema.getName()));
			}
		}
		for (ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
			for (AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
				if (attributeSchema.isSearchableInScope(scope)) {
					fields.add(
						FulltextFieldKey.referenceAttribute(referenceSchema.getName(), attributeSchema.getName())
					);
				}
			}
		}
		return fields.toArray(FulltextFieldKey[]::new);
	}

	/**
	 * Returns the tokens of the text as the analyzer the index was built with understands them - see the class
	 * documentation for why the search analyzer is used only while the assignment still names the index's analyzer.
	 *
	 * @param analyzerRegistry the catalog's analyzer registry
	 * @param entityType       the queried collection
	 * @param locale           the query locale
	 * @param fulltextIndex    the index the tokens are looked up in
	 * @param text             the text of the query
	 * @param searchTokens     the text as the search analyzer of the current assignment tokenized it
	 * @return the tokens in text order
	 */
	@Nonnull
	private static QueryToken[] resolveTokens(
		@Nonnull FulltextAnalyzerRegistry analyzerRegistry,
		@Nonnull String entityType,
		@Nonnull Locale locale,
		@Nonnull FulltextIndex fulltextIndex,
		@Nonnull String text,
		@Nonnull QueryToken[] searchTokens
	) {
		final String indexAnalyzerName = fulltextIndex.getAnalyzerName();
		if (analyzerRegistry.getIndexAnalyzer(entityType, locale).getAnalyzerName().equals(indexAnalyzerName)) {
			return searchTokens;
		}
		// the assignment moved on since the index was built and the index has not been rebuilt yet (#409) - the
		// search-side chain of the new assignment would produce terms the index never saw, so the index's own chain
		// reads the text: the variant stems are lost, but nothing wrong can match
		return analyze(analyzerRegistry.getIndexAnalyzerByName(indexAnalyzerName), text);
	}

	/**
	 * Analyzes the text into tokens: each term with a positive position increment starts a new token, and each term
	 * at the same position (`positionIncrement == 0`) joins the token before it as an alternative.
	 *
	 * @param analyzer the analyzer
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
	 * Builds the formula of one scope: every term of every token is looked up in every searched field the index knows,
	 * and each (field, term) with postings becomes one {@link ExpandedTerm}.
	 *
	 * ## Stop words typed without diacritics
	 *
	 * A token none of whose terms the index holds in any field is **dropped** when its surface form, lower-cased and
	 * folded to ASCII, is a folded stop word of the analyzer the index was built with
	 * ({@link FulltextAnalyzerRegistry#isFoldedStopWord}). The stop filters of the Czech, Polish and Romanian index
	 * chains (and German's) run on the accented text and know accented forms only, so the index drops `není`, while
	 * the query word `neni` passes the search chain unrecognized and - under the strict match - would empty the
	 * result. The word's absence says nothing about the indexed texts, so the token is not asked for.
	 *
	 * The test is on absence from the **whole** index first, not merely from the searched fields, because folding
	 * collides with real words: `být` is a stop word, `byt` ("apartment") is not. A word the index holds anywhere is a
	 * real word of the corpus and stays a token - inside `inField` too, where it then matches nothing in the named
	 * fields, which is what the user asked. Any other token without expansions still empties the result.
	 *
	 * Dropping happens before the tokens reach the formula, so the scorer counts matched tokens over the kept ones
	 * only and the ranking is the one the text without the stop word would get.
	 *
	 * @param fulltextIndex    the fulltext index of the scope and locale
	 * @param fields           the searched fields
	 * @param analyzerRegistry the catalog's analyzer registry, which knows the stop words of the index's analyzer
	 * @param tokens           the tokens of the text, analyzed for this index
	 * @return the formula; a token whose terms occur in no searched field makes it match nothing, and so does a text
	 * that produced no token at all or whose every token was a dropped stop word
	 */
	@Nonnull
	private static Formula createFormula(
		@Nonnull FulltextIndex fulltextIndex,
		@Nonnull FulltextFieldKey[] fields,
		@Nonnull FulltextAnalyzerRegistry analyzerRegistry,
		@Nonnull QueryToken[] tokens
	) {
		if (tokens.length == 0) {
			return EmptyFormula.INSTANCE;
		}
		// a field the index never saw, or retired, resolves to no id and contributes nothing
		final int[] fieldIds = new int[fields.length];
		int fieldCount = 0;
		for (FulltextFieldKey field : fields) {
			final int fieldId = fulltextIndex.getFieldId(field);
			if (fieldId != FulltextIndex.UNKNOWN_FIELD_ID) {
				fieldIds[fieldCount++] = fieldId;
			}
		}
		final List<ExpandedTerm[]> expandedTokens = new ArrayList<>(tokens.length);
		final List<ExpandedTerm> expansions = new ArrayList<>(fieldCount * 2);
		for (QueryToken token : tokens) {
			expansions.clear();
			for (int f = 0; f < fieldCount; f++) {
				for (String term : token.terms()) {
					final ExpandedTerm expandedTerm = lookUpTerm(fulltextIndex, fieldIds[f], term);
					if (expandedTerm != null) {
						expansions.add(expandedTerm);
					}
				}
			}
			if (
				expansions.isEmpty() &&
					analyzerRegistry.isFoldedStopWord(fulltextIndex.getAnalyzerName(), token.surfaceForm()) &&
					!isKnownToIndex(fulltextIndex, token)
			) {
				// a stop word typed without diacritics - the index dropped it, see the method documentation
				continue;
			}
			expandedTokens.add(expansions.toArray(ExpandedTerm[]::new));
		}
		return expandedTokens.isEmpty() ?
			EmptyFormula.INSTANCE : new FulltextFormula(expandedTokens.toArray(ExpandedTerm[][]::new));
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
			for (String term : token.terms()) {
				if (lookUpTerm(fulltextIndex, fieldId, term) != null) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Looks up one exact term of one field, reading the postings and impacts from the leaf the dictionary cursor stands
	 * on - one descent and no copy, where {@link FulltextIndex#getPostings} and {@link FulltextIndex#getImpacts} would
	 * take two descents and copy the impacts. The walk starts at the term itself, the smallest key carrying it as a
	 * prefix, so the first key visited is the term when the field holds it.
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
