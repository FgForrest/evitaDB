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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.InAttribute;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.AttributeNotSearchableException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.core.query.algebra.fulltext.ReferenceFulltextFormula;
import io.evitadb.core.query.algebra.fulltext.ReferenceFulltextScoreAccessor;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.VirtualPartitionRows;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.fulltext.FulltextFieldDefinition;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.index.fulltext.analysis.AnalyzedTermConsumer;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static io.evitadb.core.query.filter.translator.fulltext.FulltextTermExpansion.lookUpTerm;

/**
 * Prepares {@link TextMatches} once for union membership or reference-row dictionary membership, with the same
 * retained terms and ranking policy. The `relevanceText()` sorter reads the corresponding owner-space score source.
 *
 * ## From text to formula
 *
 * The text is analyzed into **words** grouped by corrected offset containment: the extra terms emitted at the same
 * span are alternatives of one word, and the spans contained in it are the parts of a split word - a typed code such
 * as `abc123` splits at its word-number boundary into `abc` and `123`. Each word looks its terms up in every searched
 * field of the {@link FulltextIndex} of the query's locale; every (field, term) with postings becomes one
 * {@link ExpandedTerm} of the word. Finished words add typo neighbours; the unclosed final word also adds prefix
 * completions and open-ended typo neighbours under the submitted policy. The typed word, normalized by the group's
 * query surface chain, is looked up the same ways in the index's surface-form lane, and each lane hit contributes
 * the stems indexed under that surface form. Exact terms are uncapped, while prefix completions and typo neighbours
 * are capped per word and group, stem and lane hits together - see `FulltextTermExpansion`.
 *
 * A split word stays one mandatory word, satisfied by its original or by any of its parts. Ranking counts the parts
 * a document covers, an original hit covering them all, in the matched-words lane - see {@link FulltextFormula}.
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
 * field sets never share an entry. Inside `referenceHaving` the key also names the reference, so a body's membership
 * formula never shares an entry with an equal top-level `textMatches` or with the body of another reference.
 * A reduced target index gets the scope's global formula AND-ed with its own primary
 * keys - the "Shape P" the substring accelerator established (see
 * `AbstractAttributeStringSearchTranslator#resolveFromIndex`). Several scopes yield one formula each, so a LIVE +
 * ARCHIVED query never pairs one scope's answer with the other's index. No prefetch alternative is offered: the
 * formula always computes from the index.
 *
 * A plain entity constraint also searches declared `COMPOSITION` references, one level deep. Each target place
 * is a separate owner place, weighted by its own weight times the reference weight. Referenced entities are items
 * of that place: their best impact per word is lifted to the owners before word intersection and place scoring.
 * Target analyzers join the shared split; targets in every processed scope map to owners in this formula's scope.
 * Explicit `inAttribute` selectors search own searchable attributes only. Composition writes no index and reads
 * current schemas.
 * Without an eligible composed place, preparation reuses the scope index and one shared own-field weight array;
 * source maps, place remapping and lifting are allocated only for composition.
 *
 * Inside `referenceHaving`, only the reference's searchable attributes participate. Every word must match the same
 * reference target and owner partition; different attributes or array elements of that row may supply the words.
 * Discovery returns matching partition keys, materialized leaves retain their producing-index tags, and virtual
 * leaves read installed row identities. Duplicate owner/target pairs share membership: words and sibling predicates
 * may combine across their rows, and negation can miss an owner. Top-level text keeps its pooled union semantics.
 * Both paths use this translator's shared preparation, including all expansion and posting-budget decisions.
 *
 * ## What fails at planning time
 *
 * The engine neither filters nor sorts over data it keeps no index for, so a query that cannot be answered from a
 * fulltext index fails rather than returning an empty result: no locale, neither an own searchable place nor a
 * composition declaration in a processed scope, an `inAttribute` naming an unknown searchable attribute or one not
 * searchable in a processed scope, `textMatches` anywhere under `userFilter` (the query model rejects only a direct
 * child), or with more words than the scorer can count. Every check runs before any index is consulted, so the query
 * never fails or passes depending on the data. A searchable field the index retired or never saw simply contributes
 * nothing.
 *
 * A text whose analysis yields no token at all - stop words or punctuation only - matches nothing: the strict match
 * set of zero tokens is empty by the same rule that makes a token without terms match nothing, and an empty search box
 * is already handled by the factory, which drops the constraint. A stop word typed without diacritics, which the
 * search chain does not recognize but the index dropped, is not asked for at all, unless it is the unfinished final
 * word and completes to a longer dictionary term - see `prepareUnionFormula`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TextMatchesTranslator implements FilteringConstraintTranslator<TextMatches>, SelfTraversingTranslator {

	/** Creates a stateless translator; expansion state belongs to each query. */
	public TextMatchesTranslator() { }

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
		final Map<Scope, FulltextFieldDefinition[]> fieldsByScope = referenceSchema == null ?
			resolveFields(textMatches, entitySchema, scopes) :
			resolveReferenceFields(textMatches, entitySchema, referenceSchema, scopes);
		final FulltextScoringPolicy scoringPolicy = filterByVisitor.getQueryContext()
			.getFulltextScoringPolicy(entitySchema);
		// Field errors still precede this schema-only shortcut; undeclared locales cannot own query results.
		if (!entitySchema.getLocales().contains(locale)) {
			return EmptyFormula.INSTANCE;
		}
		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final FulltextAnalyzerRegistry analyzerRegistry = queryContext.getFulltextAnalyzerRegistry();
		final String entityType = entitySchema.getName();
		final CatalogSchemaContract catalogSchema = queryContext.getCatalogSchema();
		final Map<Scope, List<SearchableSource>> sources = resolveSources(
			entitySchema, fieldsByScope, catalogSchema,
			referenceSchema == null && textMatches.getInAttributes().length == 0
		);
		// All own and target analyzers compile before any index containing data is read, also for check-only plans.
		final Set<CompiledFulltextAnalyzer> analyzers = CollectionUtils.createLinkedHashSet(4);
		final Map<String, CompiledFulltextAnalyzer> resolved = CollectionUtils.createHashMap(4);
		final Map<Scope, Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>>> fieldsByAnalyzer =
			new EnumMap<>(Scope.class);
		long processedScopes = 0L;
		for (final Map.Entry<Scope, FulltextFieldDefinition[]> entry : fieldsByScope.entrySet()) {
			processedScopes |= 1L << entry.getKey().ordinal();
			fieldsByAnalyzer.put(entry.getKey(), groupFields(
				entry.getValue(), analyzers, resolved, analyzerRegistry, catalogSchema, locale));
		}
		final Map<SearchableSource, Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>>> composedFields =
			sources == null ? null : CollectionUtils.createLinkedHashMap(sources.size());
		if (sources != null) {
			for (final List<SearchableSource> scopedSources : sources.values()) {
				for (final SearchableSource source : scopedSources) {
					// resolving the target's policy validates its weights now, before any data index is read
				queryContext.getFulltextScoringPolicy(source.schema());
					composedFields.put(source, groupFields(
						source.fields(), analyzers, resolved, analyzerRegistry, catalogSchema, locale));
				}
			}
		}
		if (analyzers.isEmpty()) {
			return EmptyFormula.INSTANCE;
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
		final boolean unfinished = searchTokens.length > 0 && isUnfinished(textMatches.getText()) &&
			searchTokens[searchTokens.length - 1].endOffset() ==
				Normalizer.normalize(textMatches.getText(), Normalizer.Form.NFC).length();
		final Map<CompiledFulltextAnalyzer, String[][]> fragments = CollectionUtils.createHashMap(analyzers.size());
		final Map<CompiledFulltextAnalyzer, String[][][]> surfaces = CollectionUtils.createHashMap(analyzers.size());
		final Map<CompiledFulltextAnalyzer, String[][][]> analyzed = CollectionUtils.createHashMap(analyzers.size());
		for (final Map.Entry<String, CompiledFulltextAnalyzer> entry : resolved.entrySet()) {
			final CompiledFulltextAnalyzer analyzer = entry.getValue();
			if (analyzed.containsKey(analyzer)) {
				continue;
			}
			analyzed.put(analyzer, analyzeGroups(analyzer, searchTokens, commonText));
			surfaces.put(analyzer, analyzeSurfaces(analyzer, searchTokens, commonText));
			final QueryToken last = searchTokens.length == 0 ? null : searchTokens[searchTokens.length - 1];
			final String[][] wordFragments = new String[last == null ? 1 : last.wordCount()][];
			for (int part = 0; part < wordFragments.length; part++) {
				// only the original and the last part take the unfinished-word policy, so only they need a fragment
				final boolean policyApplies = part == 0 || part == wordFragments.length - 1;
				final QueryToken word = last == null ? null : last.wordAt(part);
				wordFragments[part] = unfinished && policyApplies && word != null &&
					finalWordOffsetsMatch(analyzer, word, commonText)
					? FulltextQueryFragment.prepare(analyzer, word, commonText) : new String[0];
			}
			fragments.put(analyzer, wordFragments);
		}
		final Map<Scope, List<AnalyzedFieldGroup>> groupsByScope = new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>>> entry :
			fieldsByAnalyzer.entrySet()) {
			final List<AnalyzedFieldGroup> groups = new ArrayList<>(entry.getValue().size());
			for (final Map.Entry<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> group :
				entry.getValue().entrySet()) {
				groups.add(new AnalyzedFieldGroup(group.getKey(),
					group.getValue().toArray(FulltextFieldDefinition[]::new), analyzed.get(group.getKey()),
					surfaces.get(group.getKey()), fragments.get(group.getKey()), null));
			}
			if (sources != null) {
				for (final SearchableSource source : sources.get(entry.getKey())) {
					for (final Map.Entry<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> group :
						composedFields.get(source).entrySet()) {
						groups.add(new AnalyzedFieldGroup(group.getKey(),
							group.getValue().toArray(FulltextFieldDefinition[]::new), analyzed.get(group.getKey()),
							surfaces.get(group.getKey()), fragments.get(group.getKey()), source));
					}
				}
			}
			groupsByScope.put(entry.getKey(), groups);
		}
		if (filterByVisitor.isConstraintCheckOnly()) {
			return EmptyFormula.INSTANCE;
		}

		final FulltextTermExpansion expansion = new FulltextTermExpansion(queryContext.getFulltextTypoParameters());
		final Map<Scope, Formula> formulasByScope = new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, FulltextFieldDefinition[]> entry : fieldsByScope.entrySet()) {
			final Scope scope = entry.getKey();
			final GlobalEntityIndex globalIndex = queryContext
				.getGlobalEntityIndexIfExists(entityType, scope)
				.orElse(null);
			final FulltextIndex fulltextIndex = globalIndex == null ? null : globalIndex.getFulltextIndex(locale);
			if (globalIndex == null || (fulltextIndex == null && sources == null) || searchTokens.length == 0) {
				formulasByScope.put(scope, EmptyFormula.INSTANCE);
			} else {
				formulasByScope.put(
					scope,
					queryContext.computeOnlyOnce(
						List.of((EntityIndex) globalIndex), textMatches,
						() -> {
							final Formula prepared = prepareUnionFormula(
								queryContext, entitySchema, scope, locale, fulltextIndex, groupsByScope.get(scope),
								searchTokens, unfinished, expansion,
								globalIndex.getRecordsWithLanguageFormula(locale).getEstimatedCardinality(),
								scoringPolicy
							);
							return referenceSchema != null && prepared instanceof FulltextFormula union ?
								createReferenceFormula(fulltextIndex, union, queryContext, entitySchema,
									referenceSchema, scope) : prepared;
						}, scoringPolicy.computeHash(LongHashFunction.xx3()),
						sources == null ? fulltextIndex.getId() :
							sourceIdentity(groupsByScope.get(scope), queryContext, locale, fulltextIndex),
						// the reference and a kind marker keep a body's membership formula apart from the union
						// memoized for an equal top-level constraint, and from an equal body of another reference
						referenceSchema == null ? 0L : LongHashFunction.xx3().hashChars(referenceSchema.getName()),
						referenceSchema == null ? 0L : 1L,
						// analyzers of every processed scope decide the shared split, so an equal constraint planned
						// under `inScope` must not reuse the formula of a wider scope set, nor the other way round
						processedScopes
					)
				);
			}
		}

		if (referenceSchema != null) {
			return resolveReferenceText(filterByVisitor, textMatches, formulasByScope);
		}
		return filterByVisitor.applyOnIndexes(
			entityIndex -> resolveFromIndex(entityIndex, formulasByScope)
		);
	}

	/**
	 * Groups schema fields by their compiled analyzer before any data index is read.
	 * @param fields searchable fields in one source and scope
	 * @param analyzers distinct compiled analyzers participating in the shared split
	 * @param resolved query-local handles retained across grouping and analysis
	 * @param registry catalog analyzer registry
	 * @param catalog catalog schema declaring named analyzers
	 * @param locale query locale
	 * @return fields grouped by shared analyzer identity
	 */
	@Nonnull
	private static Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> groupFields(
		@Nonnull FulltextFieldDefinition[] fields, @Nonnull Set<CompiledFulltextAnalyzer> analyzers,
		@Nonnull Map<String, CompiledFulltextAnalyzer> resolved, @Nonnull FulltextAnalyzerRegistry registry,
		@Nonnull CatalogSchemaContract catalog, @Nonnull Locale locale
	) {
		final Map<CompiledFulltextAnalyzer, List<FulltextFieldDefinition>> grouped =
			CollectionUtils.createLinkedHashMap(4);
		for (final FulltextFieldDefinition field : fields) {
			final CompiledFulltextAnalyzer analyzer = resolved.computeIfAbsent(field.analyzer(),
				name -> registry.getCompiledAnalyzer(catalog, name, locale));
			analyzers.add(analyzer);
			grouped.computeIfAbsent(analyzer, ignored -> new ArrayList<>(4)).add(field);
		}
		return grouped;
	}

	/**
	 * Distinguishes the target inputs of composed places in the root context's shared memo: target indexes, target
	 * scopes, target scoring policies and the composing references with their weights. The processed scopes and the
	 * selectors are separate parts of the memo key.
	 * @param groups searched own and composed groups
	 * @param context snapshot index and policy resolver
	 * @param locale query locale
	 * @param ownIndex already resolved owner index, null when the owner has only composed places
	 * @return query-local source identity
	 */
	private static long sourceIdentity(
		@Nonnull List<AnalyzedFieldGroup> groups, @Nonnull QueryPlanningContext context, @Nonnull Locale locale,
		@Nullable FulltextIndex ownIndex
	) {
		long identity = ownIndex == null ? 0 : ownIndex.getId();
		for (final AnalyzedFieldGroup group : groups) {
			final SearchableSource source = group.source();
			if (source == null) {
				continue;
			}
			final GlobalEntityIndex global = context.getGlobalEntityIndexIfExists(source.schema().getName(),
				source.scope()).orElse(null);
			final FulltextIndex index = global == null ? null : global.getFulltextIndex(locale);
			identity = 31 * identity + (index == null ? 0 : index.getId());
			identity = 31 * identity + source.scope().ordinal();
			identity = 31 * identity + context.getFulltextScoringPolicy(source.schema())
				.computeHash(LongHashFunction.xx3());
			if (source.reference() != null) {
				identity = 31 * identity + LongHashFunction.xx3().hashChars(source.reference().getName());
				identity = 31 * identity + Float.floatToIntBits(source.reference().getSearchableThroughWeight());
			}
		}
		return identity;
	}

	/**
	 * Declared modes count as searchable capability even if the target collection or its places do not exist yet.
	 * @param schema owner schema
	 * @param scope owner scope
	 * @return whether any reference declares composition in the scope
	 */
	private static boolean declaresComposition(@Nonnull EntitySchemaContract schema, @Nonnull Scope scope) {
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			if (reference.getSearchableThrough(scope) == ReferenceSearchMode.COMPOSITION) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Resolves schema-only sources before opening data indexes. Target declarations are deliberately not followed.
	 * Synthetic negative ids identify (reference, target place) across all target scopes;
	 * own field ids are nonnegative.
	 * @param schema owner schema
	 * @param ownFields selected own places in every owner scope
	 * @param catalog catalog schema supplying managed target schemas
	 * @param compose whether this is a plain entity text constraint
	 * @return eligible composed sources per owner scope, or null when none has a searchable place
	 */
	@Nullable
	private static Map<Scope, List<SearchableSource>> resolveSources(
		@Nonnull EntitySchemaContract schema, @Nonnull Map<Scope, FulltextFieldDefinition[]> ownFields,
		@Nonnull CatalogSchemaContract catalog, boolean compose
	) {
		if (!compose) {
			return null;
		}
		Map<Scope, List<SearchableSource>> result = null;
		Map<String, Map<FulltextFieldKey, Integer>> places = null;
		int nextPlace = -1;
		for (final Map.Entry<Scope, FulltextFieldDefinition[]> own : ownFields.entrySet()) {
			for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
				if (reference.getSearchableThrough(own.getKey()) != ReferenceSearchMode.COMPOSITION ||
					!reference.isReferencedEntityTypeManaged() ||
					reference instanceof ReflectedReferenceSchemaContract reflected &&
						!reflected.isReflectedReferenceAvailable() ||
					!reference.isIndexedInScope(own.getKey()) ||
					!reference.getIndexedComponents(own.getKey())
						.contains(ReferenceIndexedComponents.REFERENCED_ENTITY)) {
					continue;
				}
				final EntitySchemaContract target = catalog.getEntitySchema(reference.getReferencedEntityType())
					.orElse(null);
				if (target == null) {
					continue;
				}
				for (final Scope targetScope : ownFields.keySet()) {
					final FulltextFieldDefinition[] fields = collectSearchableFields(target, targetScope);
					if (fields.length == 0) {
						continue;
					}
					if (result == null) {
						result = new EnumMap<>(Scope.class);
						places = CollectionUtils.createHashMap(4);
						for (final Scope ownerScope : ownFields.keySet()) {
							result.put(ownerScope, new ArrayList<>(4));
						}
					}
					final Map<FulltextFieldKey, Integer> referencePlaces =
						places.computeIfAbsent(reference.getName(), ignored -> CollectionUtils.createHashMap(8));
					for (final FulltextFieldDefinition field : fields) {
						if (!referencePlaces.containsKey(field.key())) {
							referencePlaces.put(field.key(), nextPlace--);
						}
					}
					result.get(own.getKey()).add(
						new SearchableSource(target, targetScope, reference, fields, referencePlaces));
				}
			}
		}
		return result;
	}

	/**
	 * Unions all materialized and virtual owner partitions of a target in one owner scope.
	 * @param context snapshot index resolver
	 * @param schema owner schema
	 * @param reference eligible reference, including a reflected reference's own declaration
	 * @param scope owner scope
	 * @param target target primary key, with no sign restriction
	 * @return owner primary keys, possibly empty
	 */
	@Nonnull
	private static Bitmap ownersOf(
		@Nonnull QueryPlanningContext context, @Nonnull EntitySchemaContract schema,
		@Nonnull ReferenceSchemaContract reference, @Nonnull Scope scope, int target
	) {
		final ReferencePartition[] partitions = context.resolvePartitionsOfTargetForRead(
			schema, reference, scope, target);
		if (partitions.length == 0) {
			return EmptyBitmap.INSTANCE;
		}
		if (partitions.length == 1) {
			return partitions[0].getAllPrimaryKeys();
		}
		final BaseBitmap owners = new BaseBitmap();
		for (final ReferencePartition partition : partitions) {
			owners.addAll(partition.getAllPrimaryKeys());
		}
		return owners;
	}

	/**
	 * Applies the ordinary budget when no projection exists, and bounded lifting otherwise.
	 * @param words unlifted expansion batches
	 * @param documentCount owner documents, including owners whose only text comes through a reference
	 * @param preparations per-group projection, null when every group searches own fields
	 * @param partCounts number of ranges in every word
	 * @return retained owner-space expansion batches
	 */
	@Nonnull
	private static ExpandedTerm[][][] retain(
		@Nonnull ExpandedTerm[][][] words, int documentCount,
		@Nullable FulltextPostingsBudget.TermPreparation[] preparations, @Nonnull int[] partCounts
	) {
		final long limit = FulltextPostingsBudget.limit(words.length, documentCount);
		if (preparations != null) {
			return FulltextPostingsBudget.retainLifted(words, limit, preparations, partCounts);
		}
		return FulltextPostingsBudget.retain(words, limit);
	}

	/**
	 * Schema-only origin of a searched group. No target schema traversal happens after this first level.
	 * @param schema collection whose own places are searched
	 * @param scope scope of its global fulltext index
	 * @param reference owner reference declaring composition
	 * @param fields searchable places in this target scope
	 * @param places synthetic ids shared across target scopes
	 */
	private record SearchableSource(
		@Nonnull EntitySchemaContract schema, @Nonnull Scope scope, @Nonnull ReferenceSchemaContract reference,
		@Nonnull FulltextFieldDefinition[] fields, @Nonnull Map<FulltextFieldKey, Integer> places
	) { }

	/**
	 * Builds the reference consumer of the union preparation using the collection's real partition split.
	 * @param index locale's current field generations
	 * @param union retained expansion and scoring preparation shared with top-level search
	 * @param context planning context of the owner collection
	 * @param entitySchema owner schema
	 * @param reference reference whose attributes were selected
	 * @param scope owner scope
	 * @return membership formula with symbolic per-word target maps
	 */
	@Nonnull
	private static ReferenceFulltextFormula createReferenceFormula(
		@Nonnull FulltextIndex index, @Nonnull FulltextFormula union, @Nonnull QueryPlanningContext context,
		@Nonnull EntitySchemaContract entitySchema, @Nonnull ReferenceSchemaContract reference, @Nonnull Scope scope
	) {
		final String entityType = entitySchema.getName();
		final IntFunction<ReferencePartition> partitionAccessor;
		if (entityType.equals(context.getEntityType())) {
			// the queried collection - its index snapshot
			partitionAccessor = context::resolvePartition;
		} else {
			// another collection planned with the queried one's context (a hierarchy parent filter, for example) - its
			// live indexes, because index primary keys are per collection and name another index in the queried one
			final EntityCollection collection = context.getEntityCollectionOrThrowException(
				entityType, "resolve reference text partitions");
			partitionAccessor = pk -> ReferencePartitions.requirePartition(
				collection.getIndexByPrimaryKeyIfExists(pk), entityType, pk);
		}
		return new ReferenceFulltextFormula(index, union, targets -> {
			final BaseBitmap candidates = new BaseBitmap();
			context.getEntityIndex(entityType,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, reference.getName()),
				ReferencedTypeEntityIndex.class).ifPresent(typeIndex -> {
					for (final int target : targets) {
						candidates.addAll(typeIndex.getAllReferenceIndexes(target));
					}
				});
			return context.splitPartitionsForRead(entityType, reference.getName(), candidates, scope);
		}, partitionAccessor);
	}

	/**
	 * Projects one memoized scope formula into tagged body owners or discovery partition keys.
	 * Exact virtual evaluation reads installed row identities and never the flattened reference accessor.
	 * @param visitor visitor that owns this plan's score registrations
	 * @param origin structural identity of the direct text place
	 * @param formulas membership formulas per owner scope
	 * @return tagged materialized leaves or partition-key leaves
	 */
	@Nonnull
	private static Formula resolveReferenceText(
		@Nonnull FilterByVisitor visitor, @Nonnull TextMatches origin, @Nonnull Map<Scope, Formula> formulas
	) {
		final Map<Scope, ReferenceFulltextScoreAccessor> scores = new EnumMap<>(Scope.class);
		final VirtualPartitionRows rows = visitor.getProcessingScope().getVirtualPartitionRows();
		final boolean positive = isUnderEvenNegation(visitor);
		final Formula result = visitor.applyOnIndexes(entityIndex -> {
			final Scope scope = entityIndex.getIndexKey().scope();
			if (!(formulas.get(scope) instanceof ReferenceFulltextFormula membership)) {
				return EmptyFormula.INSTANCE;
			}
			if (rows != null) {
				final ReferenceFulltextScoreAccessor accessor = scores.computeIfAbsent(scope,
					ignored -> new ReferenceFulltextScoreAccessor(membership));
				return rows.select(row -> {
					final int owner = rows.getOwnerPrimaryKey(row);
					final boolean matched = membership.matches(rows.getTargetPrimaryKey(row), owner);
					if (matched && positive) {
						accessor.include(owner);
					}
					return matched;
				});
			}
			if (entityIndex instanceof ReferencedTypeEntityIndex) {
				return constant(membership.getMatchingPartitions());
			}
			Assert.isPremiseValid(entityIndex instanceof ReducedEntityIndex,
				"Direct reference text requires a reference partition!");
			final ReducedEntityIndex partition = (ReducedEntityIndex) entityIndex;
			final Bitmap matched = membership.resolve(partition.getReferenceKey().primaryKey(),
				partition.getAllPrimaryKeys());
			final ReferenceFulltextScoreAccessor accessor = scores.computeIfAbsent(scope,
				ignored -> new ReferenceFulltextScoreAccessor(membership));
			if (positive) {
				accessor.include(matched);
			}
			return constant(matched);
		});
		for (final Map.Entry<Scope, ReferenceFulltextScoreAccessor> score : scores.entrySet()) {
			visitor.registerOwnerFulltextScores(origin, score.getKey(), score.getValue());
		}
		return result;
	}

	/**
	 * Tells whether the text counts as a positive occurrence, whose matches may score: an even number of `not`
	 * containers encloses it on the processing scope's constraint stack, which starts at the reference body.
	 * @param visitor visitor whose processing scope holds the enclosing constraints
	 * @return true under no or an even number of negations, false under an odd number
	 */
	private static boolean isUnderEvenNegation(@Nonnull FilterByVisitor visitor) {
		int negations = 0;
		for (final FilterConstraint parent : visitor.getProcessingScope().getProcessedConstraintParents()) {
			if (parent instanceof Not) {
				negations++;
			}
		}
		return negations % 2 == 0;
	}

	/**
	 * Wraps a materialized positive result, retaining the canonical empty leaf.
	 * @param bitmap owner or partition keys in the current processing space
	 * @return content-keyed constant or the empty formula
	 */
	@Nonnull
	private static Formula constant(@Nonnull Bitmap bitmap) {
		return bitmap.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(bitmap);
	}

	/**
	 * Unions the names of every attribute selector in the order supplied, so that a name repeated within or across
	 * selectors is searched and scored once.
	 * @param selectors the attribute selectors of one `textMatches`
	 * @return the distinct selected attribute names, empty without a selector
	 */
	@Nonnull
	private static Set<String> collectSelectedAttributeNames(@Nonnull InAttribute[] selectors) {
		final Set<String> names = CollectionUtils.createLinkedHashSet(selectors.length);
		for (final InAttribute selector : selectors) {
			Collections.addAll(names, selector.getAttributeNames());
		}
		return names;
	}

	/**
	 * Validates selectors against this reference, before inspecting any index data. The selectors name the union of
	 * their attributes, a repeated name counting once; without a selector, every reference attribute searchable in the
	 * scope is selected.
	 * @param textMatches direct body text constraint
	 * @param entitySchema owner schema used in diagnostics and field identities
	 * @param reference schema supplying searchable attributes
	 * @param scopes every requested owner scope
	 * @return selected reference fields in every processed scope
	 */
	@Nonnull
	private static Map<Scope, FulltextFieldDefinition[]> resolveReferenceFields(
		@Nonnull TextMatches textMatches, @Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract reference, @Nonnull Set<Scope> scopes
	) {
		final Set<String> names = collectSelectedAttributeNames(textMatches.getInAttributes());
		final Map<Scope, FulltextFieldDefinition[]> result = new EnumMap<>(Scope.class);
		for (final Scope scope : scopes) {
			final List<FulltextFieldDefinition> selected = new ArrayList<>(reference.getAttributes().size());
			if (names.isEmpty()) {
				for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
					if (attribute.isSearchableInScope(scope)) {
						selected.add(FulltextFieldDefinition.resolve(entitySchema,
							FulltextFieldKey.referenceAttribute(reference.getName(), attribute.getName())));
					}
				}
			} else {
				for (final String name : names) {
					final AttributeSchemaContract attribute = reference.getAttribute(name)
						.orElseThrow(() -> new AttributeNotFoundException(name, reference, entitySchema));
					Assert.isTrue(attribute.isSearchableInScope(scope),
						() -> new AttributeNotSearchableException(name, reference, entitySchema, scope));
					selected.add(FulltextFieldDefinition.resolve(entitySchema,
						FulltextFieldKey.referenceAttribute(reference.getName(), name)));
				}
			}
			Assert.isTrue(!selected.isEmpty(), () -> new EvitaInvalidUsageException(
				"Reference `" + reference.getName() + "` declares no searchable attribute in scope `" + scope + "`!"));
			result.put(scope, selected.toArray(FulltextFieldDefinition[]::new));
		}
		return result;
	}

	/**
	 * Checks the raw query's final code point for the separator that closes its last word.
	 * @param text raw query text before any profile's text filters
	 * @return true when the query ends in a letter, digit or combining mark
	 */
	private static boolean isUnfinished(@Nonnull String text) {
		if (text.isEmpty()) {
			return false;
		}
		final int last = text.codePointBefore(text.length());
		final int type = Character.getType(last);
		return Character.isLetterOrDigit(last) || type == Character.NON_SPACING_MARK ||
			type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
	}

	/**
	 * Checks a heterogeneous group's text phase before enabling offset-based final-word expansion.
	 * Common text splitting already passed the raw-end check. A raw split needs the same check per group,
	 * before word filters can erase a stop word. Non-correcting shortening readers therefore degrade equally.
	 * @param analyzer the group's compiled chains
	 * @param word final shared word before a heterogeneous text phase
	 * @param commonText whether shared splitting already applied the common text phase
	 * @return whether the final word's offsets still address the complete input word
	 */
	private static boolean finalWordOffsetsMatch(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull QueryToken word, boolean commonText
	) {
		if (commonText || analyzer.queryText().isEmpty()) {
			return true;
		}
		final int[] endOffset = new int[1];
		for (final String term : word.terms()) {
			endOffset[0] = -1;
			analyzer.textSplitting().analyze(term, (value, surface, start, end, increment) ->
				endOffset[0] = Math.max(endOffset[0], end));
			if (endOffset[0] != Normalizer.normalize(term, Normalizer.Form.NFC).length()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Resolves the fields the constraint searches in every processed scope, and verifies they may be searched there.
	 *
	 * Without `inAttribute` the constraint searches every searchable field of the collection in the scope: its entity
	 * attributes (global attributes included), the attributes of its references and its associated data; places
	 * reached through a `COMPOSITION` reference are not its fields and are resolved separately. With `inAttribute` it
	 * searches the named entity or global attributes only - a name repeated within or across selectors counts once,
	 * and each must be searchable in every processed scope, as an attribute filter must be filterable in every one.
	 * Here reference attributes and associated data are reachable only through the default: `inAttribute` names
	 * reference attributes only inside `referenceHaving` (see `resolveReferenceFields`), and associated data cannot
	 * be named at all.
	 *
	 * @param textMatches  the constraint
	 * @param entitySchema schema of the queried collection
	 * @param scopes       the processed scopes
	 * @return own searched fields per scope, possibly empty when a composition is declared
	 * @throws AttributeNotFoundException when an `inAttribute` names an attribute the collection does not declare
	 * @throws AttributeNotSearchableException when an `inAttribute` names an attribute that is not searchable at all,
	 *                                        or not in some processed scope
	 * @throws EvitaInvalidUsageException     when a processed scope has neither a searchable field nor a reference
	 *                                        declaring composition
	 */
	@Nonnull
	private static Map<Scope, FulltextFieldDefinition[]> resolveFields(
		@Nonnull TextMatches textMatches,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Set<Scope> scopes
	) {
		final Map<Scope, FulltextFieldDefinition[]> result = new EnumMap<>(Scope.class);
		final InAttribute[] inAttributes = textMatches.getInAttributes();
		if (inAttributes.length == 0) {
			for (final Scope scope : scopes) {
				final FulltextFieldDefinition[] fields = collectSearchableFields(entitySchema, scope);
				Assert.isTrue(
					fields.length > 0 || declaresComposition(entitySchema, scope),
					() -> new EvitaInvalidUsageException(
						"Constraint `textMatches` cannot be used on the entity collection `" + entitySchema.getName() +
							"` in scope `" + scope + "` - the collection declares no attribute or associated data " +
							"searchable there and no reference composing the text of its referenced entities. Make " +
							"at least one localized String attribute or associated data `searchable` in the schema, " +
							"or declare a reference `searchableThrough(COMPOSITION)` in that scope!"
					)
				);
				result.put(scope, fields);
			}
		} else {
			final Set<String> names = collectSelectedAttributeNames(inAttributes);
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
	 * included, the attributes of each of its references, and its associated data. A reflected reference whose
	 * original is not available yet contributes only its locally declared attributes, see
	 * {@link FulltextFieldDefinition#availableReferenceAttributes(ReferenceSchemaContract)}.
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
			for (final AttributeSchemaContract attributeSchema :
				FulltextFieldDefinition.availableReferenceAttributes(referenceSchema).values()) {
				if (attributeSchema.isSearchableInScope(scope)) {
					fields.add(new FulltextFieldDefinition(
						FulltextFieldKey.referenceAttribute(referenceSchema.getName(), attributeSchema.getName()),
						attributeSchema.getAnalyzer(), attributeSchema.getLengthPivot()));
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
	 * @param source schema-only composed origin, null for own fields
	 * @param analyzer the shared compiled content identity
	 * @param fields the fields searched in this scope
	 * @param alternatives per word, index 0 the original and then each part, each with its analyzed alternatives;
	 *                     empty where the chain dropped it
	 * @param surfaces per word, aligned with `alternatives`, the typed terms normalized by the query surface chain for
	 *                 the surface-form lane; empty where that chain dropped them
	 * @param fragments the final word's unfinished fragments, index 0 the original and then each part, normalized
	 *                  around stop removal and stemming; empty wherever the unfinished-word policy does not apply,
	 *                  which includes every part but the last
	 */
	private record AnalyzedFieldGroup(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull FulltextFieldDefinition[] fields,
		@Nonnull String[][][] alternatives, @Nonnull String[][][] surfaces, @Nonnull String[][] fragments,
		@Nullable SearchableSource source
	) { }

	/**
	 * Runs a group's word chain over every term of each shared word, separately for the original and each of its
	 * parts. Position increments emitted inside the word chain never create a new query word; every nonempty emitted
	 * term is an alternative of the original or part it was analyzed from.
	 * After the raw split the chain applies the group's query text phase to each word first; after the text-aware
	 * split the words have been through that phase already, and the chain without it keeps a text step that is not
	 * idempotent from changing them a second time.
	 *
	 * @param analyzer the group's compiled chains
	 * @param words the locale's shared words
	 * @param textSplit whether the words come from the text-aware split, which applied the common query text phase
	 * @return alternatives aligned with shared words, each containing the original then its offset-distinct parts
	 */
	@Nonnull
	private static String[][][] analyzeGroups(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull QueryToken[] words, boolean textSplit
	) {
		final FulltextAnalyzer wordChain = textSplit ? analyzer.textSplitWord() : analyzer.word();
		final String[][][] alternatives = new String[words.length][][];
		for (int i = 0; i < words.length; i++) {
			alternatives[i] = new String[words[i].wordCount()][];
			for (int part = 0; part < alternatives[i].length; part++) {
				final QueryToken word = words[i].wordAt(part);
				final Set<String> terms = CollectionUtils.createLinkedHashSet(4);
				for (final String term : word.terms()) {
					wordChain.analyze(term, (value, surface, start, end, increment) -> {
						if (!value.isEmpty()) {
							terms.add(value);
						}
					});
				}
				alternatives[i][part] = terms.toArray(String[]::new);
			}
		}
		return alternatives;
	}

	/**
	 * Prepares lane lookup words from shared split terms. The text-aware variant keeps splitting transformations
	 * while applying the profile's query text phase exactly once.
	 * @param analyzer profile's cached query surface preparation
	 * @param words shared split words and their parts
	 * @param textSplit whether shared splitting already applied the query text phase
	 * @return normalized lane words aligned with the analyzed stem alternatives
	 */
	@Nonnull
	private static String[][][] analyzeSurfaces(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull QueryToken[] words, boolean textSplit
	) {
		final FulltextAnalyzer chain = textSplit ? analyzer.textSplitSurface() : analyzer.surface();
		final String[][][] surfaces = new String[words.length][][];
		for (int i = 0; i < words.length; i++) {
			surfaces[i] = new String[words[i].wordCount()][];
			for (int part = 0; part < surfaces[i].length; part++) {
				final Set<String> terms = CollectionUtils.createLinkedHashSet(2);
				for (final String term : words[i].wordAt(part).terms()) {
					chain.analyze(term, (value, surface, start, end, increment) -> {
						if (!value.isEmpty()) {
							terms.add(value);
						}
					});
				}
				surfaces[i][part] = terms.toArray(String[]::new);
			}
		}
		return surfaces;
	}

	/**
	 * Analyzes text into mandatory query words, regrouping split originals and their parts by offset containment.
	 * Position increments do not define groups: a flattened word-number graph gives the second part increment one.
	 * The original or any part satisfies the word. Ranking counts covered parts, with an original hit covering all
	 * parts, so complete coverage precedes partial matches before the matched-word byte saturates. Ordinary words
	 * still count once.
	 *
	 * A term spanning exactly the current word's corrected offsets is an alternative of it. A term whose span lies
	 * strictly inside is a part, terms of one span sharing that part. A term starting before the word, ending after it
	 * or starting at its end begins the next word.
	 *
	 * The caller supplies text-aware splitting only when every group's resolved query text phase is equal.
	 * With heterogeneous phases it supplies raw splitting: a word boundary a group's text step removes is then
	 * not removed for that group. Either choice is independent of group iteration order. Length-changing text
	 * readers must correct offsets to the original NFC input. When final-word offsets do not reach that input
	 * end, the final word is treated as finished, disabling prefix and open-ended expansion.
	 *
	 * @param analyzer the shared splitting chain, optionally preceded by the common query text phase
	 * @param text     the text of the query
	 * @return the words in text order, each carrying its offset-distinct parts; empty when the analysis produced no
	 *         term
	 * @throws EvitaInvalidUsageException when the text has more words than the scorer can count
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
	 * Expands each group's alternatives only in its current field generations. A shared word leaves the query only
	 * when every group with a current field drops its original and all of its parts. Groups without current fields do
	 * not participate. A group drops
	 * a word its chain removed, and a folded stop word no field holds - folded stop recognition is per group and is
	 * suppressed by any corpus collision, including postings of retired fields. A dropped unfinished final word still
	 * stays while its fragment completes to a longer dictionary term, so that a stop word on the way to a longer word
	 * can be finished; typo neighbours alone never keep it. A kept word with no expansion remains a word that matches
	 * nothing.
	 *
	 * The unfinished-word policy applies to the final word's original and its last part only. A split word lays its
	 * expansions out as the original's, then each part's, and its row of part ends marks where each range ends; the
	 * formula receives the rows only when some word is split. The original's range keeps no inexact term a part
	 * matched exactly in the same field, so a part reached by the original's typo leg is credited as that part only.
	 * Before flattening, the postings budget visits the collected group/part batches in their production order.
	 * A word emptied by that cut remains mandatory, making the query match nothing. The completion gate applies again
	 * after the cut: a batch whose last completion was cut leaves, and a final word left with no batch leaves the query
	 * as if it had been closed, with the remaining words priced again for their own count.
	 *
	 * @param context query context resolving snapshot indexes and owner partitions
	 * @param entitySchema owner collection schema
	 * @param scope owner scope
	 * @param locale query locale
	 * @param ownIndex already resolved owner index, null when the owner has no own indexed text
	 * @param groups the analyzed groups and their schema field identities
	 * @param tokens the shared words with their original surface forms and their split parts
	 * @param unfinished whether the raw text ends with an unclosed word
	 * @param expansion query-local walkers and submitted-search policy shared across scopes and groups
	 * @param documentCount cardinality of the scope global index's locale bitmap, known before query execution
	 * @param scoringPolicy effective place weights and coefficient resolved before empty-index shortcuts
	 * @return retained union preparation used directly at top level and as the reference matcher's scoring input,
	 *         or empty when all words were dropped
	 */
	@Nonnull
	private static Formula prepareUnionFormula(
		@Nonnull QueryPlanningContext context, @Nonnull EntitySchemaContract entitySchema, @Nonnull Scope scope,
		@Nonnull Locale locale, @Nullable FulltextIndex ownIndex, @Nonnull List<AnalyzedFieldGroup> groups,
		@Nonnull QueryToken[] tokens, boolean unfinished, @Nonnull FulltextTermExpansion expansion, int documentCount,
		@Nonnull FulltextScoringPolicy scoringPolicy
	) {
		final List<int[]> fieldIds = new ArrayList<>(groups.size());
		boolean composed = false;
		for (final AnalyzedFieldGroup group : groups) {
			composed |= group.source() != null;
		}
		final float[] ownWeights = new float[ownIndex == null ? 0 : ownIndex.getFieldCount()];
		final List<float[]> weights = composed ? new ArrayList<>(groups.size()) : null;
		final List<FulltextIndex> indexes = composed ? new ArrayList<>(groups.size()) : null;
		final FulltextPostingsBudget.TermPreparation[] preparations = composed ?
			new FulltextPostingsBudget.TermPreparation[groups.size()] : null;
		final Map<String, FulltextTermLifter> lifters = composed ? CollectionUtils.createHashMap(4) : null;
		for (int g = 0; g < groups.size(); g++) {
			final AnalyzedFieldGroup group = groups.get(g);
			final SearchableSource source = group.source();
			final FulltextIndex index;
			final float[] fieldWeights;
			final int[] places;
			final FulltextScoringPolicy policy;
			if (source == null) {
				index = ownIndex;
				fieldWeights = ownWeights;
				places = null;
				policy = scoringPolicy;
			} else {
				final GlobalEntityIndex global = context.getGlobalEntityIndexIfExists(
					source.schema().getName(), source.scope()).orElse(null);
				index = global == null ? null : global.getFulltextIndex(locale);
				fieldWeights = new float[index == null ? 0 : index.getFieldCount()];
				places = new int[fieldWeights.length];
				policy = context.getFulltextScoringPolicy(source.schema());
			}
			final int[] ids = new int[group.fields().length];
			int count = 0;
			for (final FulltextFieldDefinition field : group.fields()) {
				final int id = index == null ? FulltextIndex.UNKNOWN_FIELD_ID : index.getFieldId(field.key());
				if (id != FulltextIndex.UNKNOWN_FIELD_ID && index.isFieldCurrent(
					id, group.analyzer().fingerprint().index(), field.lengthPivot())) {
					ids[count++] = id;
					final double weight = (double) policy.weight(field.key()) *
						(source == null ? 1f : source.reference().getSearchableThroughWeight());
					fieldWeights[id] = (float) Math.min(Float.MAX_VALUE, weight);
					if (source != null) {
						places[id] = source.places().get(field.key());
					}
				}
			}
			fieldIds.add(Arrays.copyOf(ids, count));
			if (composed) {
				indexes.add(index);
				weights.add(fieldWeights);
			}
			if (source != null) {
				final FulltextTermLifter lifter = lifters.computeIfAbsent(source.reference().getName(),
					ignored -> new FulltextTermLifter(target -> ownersOf(context, entitySchema, source.reference(),
						scope, target)));
				preparations[g] = (term, remaining) -> lifter.prepare(term, places[term.fieldId()], remaining);
			}
		}
		final List<ExpandedTerm[][]> expandedTokens = new ArrayList<>(tokens.length);
		final int[] partCounts = new int[tokens.length];
		// Batches of the final word admitted only for their completion, null when there are none.
		boolean[] completionOnly = null;
		boolean finalWordOrdinary = false;
		for (int i = 0; i < tokens.length; i++) {
			final int partCount = tokens[i].wordCount();
			final List<List<ExpandedTerm>> batches = new ArrayList<>(groups.size() * partCount);
			for (int b = 0; b < groups.size() * partCount; b++) {
				batches.add(List.of());
			}
			boolean kept = false;
			for (int g = 0; g < groups.size(); g++) {
				if (fieldIds.get(g).length == 0) {
					continue;
				}
				final AnalyzedFieldGroup group = groups.get(g);
				final FulltextIndex groupIndex = composed ? indexes.get(g) : ownIndex;
				final float[] groupWeights = composed ? weights.get(g) : ownWeights;
				for (int part = 0; part < partCount; part++) {
					final QueryToken word = tokens[i].wordAt(part);
					final String[] alternatives = group.alternatives()[i][part];
					final boolean lastUnfinished = unfinished && i == tokens.length - 1 &&
						(part == 0 || part == partCount - 1);
					final String[] fragments = lastUnfinished ? group.fragments()[part] : new String[0];
					final boolean hasFragment = expansion.expandsUnfinishedWord(fragments, lastUnfinished);
					// Stop recognition depends on exact corpus collisions, never on a fuzzy neighbour.
					final boolean dropped = alternatives.length == 0 ||
						group.analyzer().isFoldedStopWord(word.surfaceForm()) &&
							!isKnownToIndex(groupIndex, new QueryToken(word.surfaceForm(), alternatives));
					if (dropped && !hasFragment) {
						continue;
					}
					final List<ExpandedTerm> expanded = expansion.expand(
						groupIndex, fieldIds.get(g), groupWeights, alternatives, group.surfaces()[i][part], fragments,
						lastUnfinished);
					if (dropped && !containsCompletion(expanded)) {
						continue;
					}
					batches.set(g * partCount + part, expanded);
					kept = true;
					if (dropped) {
						// Only the final word takes the unfinished policy, so only its batches get here.
						if (completionOnly == null) {
							completionOnly = new boolean[batches.size()];
						}
						completionOnly[g * partCount + part] = true;
					} else if (i == tokens.length - 1) {
						finalWordOrdinary = true;
					}
				}
			}
			if (kept) {
				if (partCount > 1) {
					// A field belongs to one analyzer group, so exact part collisions stay within that group.
					for (int g = 0; g < groups.size(); g++) {
						dropTermsPartsMatchExactly(batches.subList(g * partCount, (g + 1) * partCount));
					}
				}
				final ExpandedTerm[][] collected = new ExpandedTerm[batches.size()][];
				for (int b = 0; b < batches.size(); b++) {
					collected[b] = batches.get(b).toArray(ExpandedTerm[]::new);
				}
				partCounts[expandedTokens.size()] = partCount;
				expandedTokens.add(collected);
			}
		}
		if (expandedTokens.isEmpty()) {
			return EmptyFormula.INSTANCE;
		}
		ExpandedTerm[][][] words = expandedTokens.toArray(ExpandedTerm[][][]::new);
		ExpandedTerm[][][] retained = retain(words, documentCount, preparations, partCounts);
		if (completionOnly != null) {
			// The completion gate applies again to the batches whose last completion the cut removed.
			final int last = words.length - 1;
			final ExpandedTerm[][] finalWord = retained[last].clone();
			boolean admitted = finalWordOrdinary;
			for (int b = 0; b < completionOnly.length; b++) {
				if (completionOnly[b]) {
					if (containsCompletion(Arrays.asList(finalWord[b])) ||
						!containsCompletion(Arrays.asList(words[last][b]))) {
						admitted = true;
					} else {
						finalWord[b] = new ExpandedTerm[0];
					}
				}
			}
			if (!admitted) {
				if (last == 0) {
					return EmptyFormula.INSTANCE;
				}
				// The word leaves as if it had been closed, so the remaining words are priced for their own count.
				words = Arrays.copyOf(words, last);
				retained = retain(words, documentCount, preparations, partCounts);
			} else {
				retained[last] = finalWord;
			}
		}
		return FulltextPostingsBudget.toFormula(retained, Arrays.copyOf(partCounts, words.length), scoringPolicy);
	}

	/**
	 * Removes from the original's expansions every inexact term that one of its parts matched exactly in the same
	 * field. The original's typo leg can reach a part's own term - `abcdefgh_x` reaches `abcdefgh` two deletions away -
	 * and a hit in the original's range covers every part, so a document holding that one part would tie with one
	 * holding the whole word. The term stays in the part's range with the same postings and impacts, so the word's
	 * match set, best distance, exactness and impact do not change; only the coverage credit does. The original's own
	 * exact terms stay even where a part's typo leg reaches them, because the original's range is scored first and
	 * its hit already covers every part.
	 *
	 * @param parts one analyzer group's expansions of the word per range, the original first and then each part; the
	 *              original's list is shortened in place only when a term is dropped, because an original the
	 *              group never expanded is an immutable empty placeholder
	 */
	private static void dropTermsPartsMatchExactly(@Nonnull List<List<ExpandedTerm>> parts) {
		final List<ExpandedTerm> original = parts.get(0);
		int retained = 0;
		for (int i = 0; i < original.size(); i++) {
			final ExpandedTerm term = original.get(i);
			if (term.exactness() == FulltextTermExactness.EXACT || !isMatchedExactlyByPart(term, parts)) {
				original.set(retained++, term);
			}
		}
		if (retained < original.size()) {
			original.subList(retained, original.size()).clear();
		}
	}

	/**
	 * Checks whether some part of a split word matched the term exactly in the same field.
	 *
	 * @param term  an expansion of the original
	 * @param parts the word's expansions per range, the original first
	 * @return true when a part's range holds an exact expansion of the same field and term
	 */
	private static boolean isMatchedExactlyByPart(@Nonnull ExpandedTerm term, @Nonnull List<List<ExpandedTerm>> parts) {
		for (int part = 1; part < parts.size(); part++) {
			for (final ExpandedTerm candidate : parts.get(part)) {
				if (candidate.exactness() == FulltextTermExactness.EXACT && candidate.fieldId() == term.fieldId() &&
					candidate.term().equals(term.term())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks whether a word's expansion completes its unfinished fragment. Typo neighbours alone never count, so a
	 * dropped word is not turned into a mandatory one by a fuzzy match.
	 *
	 * @param expanded the word's expansion within one group
	 * @return true when some term was reached as a prefix completion
	 */
	private static boolean containsCompletion(@Nonnull List<ExpandedTerm> expanded) {
		for (final ExpandedTerm term : expanded) {
			if (term.exactness() == FulltextTermExactness.PREFIX) {
				return true;
			}
		}
		return false;
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
	 * @param terms       the distinct terms the analyzer emitted at the word's span, its variant stems included
	 * @param endOffset corrected end in the NFC raw query, or -1 for a synthetic lookup-only token
	 * @param parts offset-distinct contained split parts, empty for an ordinary word
	 */
	record QueryToken(
		@Nonnull String surfaceForm, @Nonnull String[] terms, int endOffset, @Nonnull QueryToken[] parts
	) {
		/**
		 * Creates an unsplit word with corrected raw offsets.
		 * @param surfaceForm original surface for stop recognition
		 * @param terms distinct analyzed alternatives
		 * @param endOffset corrected raw end of this word
		 */
		QueryToken(@Nonnull String surfaceForm, @Nonnull String[] terms, int endOffset) {
			this(surfaceForm, terms, endOffset, new QueryToken[0]);
		}
		/**
		 * Creates a lookup-only word without raw query offsets.
		 * @param surfaceForm surface used for stop recognition
		 * @param terms analyzed alternatives to look up
		 */
		QueryToken(@Nonnull String surfaceForm, @Nonnull String[] terms) {
			this(surfaceForm, terms, -1);
		}

		/**
		 * Returns how many words the expansions of this word are grouped by: the original, then each of its parts.
		 *
		 * @return one for an ordinary word, one more than the number of parts for a split one
		 */
		int wordCount() {
			return this.parts.length + 1;
		}

		/**
		 * Returns the original or one of its parts, by the index {@link #wordCount()} counts with.
		 *
		 * @param part zero for the original, `i` for the `i`-th part
		 * @return this word for zero, otherwise the part
		 */
		@Nonnull
		QueryToken wordAt(int part) {
			return part == 0 ? this : this.parts[part - 1];
		}
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
		/** Corrected raw end of the current position, including its alternatives. */
		private int currentEndOffset = -1;
		/** Corrected raw start of the original word. */
		private int currentStartOffset = -1;
		/** Collectors for offset-distinct parts of the current original. */
		private final List<TokenCollector> parts = new ArrayList<>(2);

		/** Creates a collector for one original word stream or one of its contained parts. */
		private TokenCollector() { }

		@Override
		public void accept(
			@Nonnull String term,
			@Nonnull Supplier<String> surfaceForm,
			int startOffset,
			int endOffset,
			int positionIncrement
		) {
			// a span outside the current word begins the next one; a wider span at the same start never follows a
			// narrower one, because the word-number split emits each original before the parts nested in it
			if (this.currentSurfaceForm != null &&
				(startOffset < this.currentStartOffset || endOffset > this.currentEndOffset ||
					startOffset >= this.currentEndOffset)) {
				flush();
			}
			// a span strictly inside the current word is one of its parts, collected apart from its alternatives
			if (this.currentSurfaceForm != null &&
				(startOffset > this.currentStartOffset || endOffset < this.currentEndOffset)) {
				TokenCollector target = findPart(startOffset, endOffset);
				if (target == null) {
					target = new TokenCollector();
					this.parts.add(target);
				}
				target.accept(term, surfaceForm, startOffset, endOffset, positionIncrement);
				return;
			}
			if (this.currentSurfaceForm == null) {
				this.currentSurfaceForm = surfaceForm.get();
				this.currentStartOffset = startOffset;
			}
			this.currentEndOffset = Math.max(this.currentEndOffset, endOffset);
			// a variant stem may repeat the term before it - one lookup per distinct term is enough
			if (!this.currentToken.contains(term)) {
				this.currentToken.add(term);
			}
		}

		/**
		 * Finds the collector of the part of the current word spanning exactly the given offsets.
		 *
		 * @param startOffset corrected raw start of the part
		 * @param endOffset   corrected raw end of the part
		 * @return the part's collector, or null when no part of the current word has this span yet
		 */
		@Nullable
		private TokenCollector findPart(int startOffset, int endOffset) {
			for (final TokenCollector part : this.parts) {
				if (part.currentStartOffset == startOffset && part.currentEndOffset == endOffset) {
					return part;
				}
			}
			return null;
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
				final QueryToken[] splitParts = new QueryToken[this.parts.size()];
				for (int i = 0; i < splitParts.length; i++) {
					// a part collector holds exactly one word: it receives only terms of its own span
					splitParts[i] = this.parts.get(i).getTokens()[0];
				}
				this.tokens.add(
					new QueryToken(
						Objects.requireNonNull(this.currentSurfaceForm), this.currentToken.toArray(String[]::new),
						this.currentEndOffset, splitParts
					)
				);
				this.currentToken.clear();
			}
			this.currentSurfaceForm = null;
			this.currentEndOffset = -1;
			this.currentStartOffset = -1;
			this.parts.clear();
		}

	}

}
