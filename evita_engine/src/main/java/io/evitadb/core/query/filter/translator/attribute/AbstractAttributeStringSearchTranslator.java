/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.query.filter.translator.attribute;


import io.evitadb.api.query.filter.AbstractAttributeFilterStringSearchConstraintLeaf;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;

/**
 * AbstractAttributeStringSearchTranslator is an abstract class that extends the AbstractAttributeTranslator.
 * It provides methods to generate filtering formulas based on string attribute searches, applying specific
 * predicates for filtering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class AbstractAttributeStringSearchTranslator extends AbstractAttributeTranslator {
	/**
	 * The description of the filter.
	 */
	private final String description;
	/**
	 * The function that will extract formula matching the searched string from the filter index.
	 */
	private final BiFunction<FilterIndex, String, Formula> filterIndexResolver;
	/**
	 * The predicate to test each attribute string value.
	 */
	private final BiPredicate<String, String> stringPredicate;

	/**
	 * Asserts that the provided attribute definition is of type String.
	 * This method ensures that the attribute constraint is applied only
	 * to attributes with a String type.
	 *
	 * @param attributeConstraint the attribute filter string search constraint
	 *                            that requires the attribute to be of type String
	 * @param attributeDefinition the schema definition of the attribute being validated
	 */
	static void assertStringType(
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull AttributeSchemaContract attributeDefinition
	) {
		Assert.isTrue(
			String.class.equals(attributeDefinition.getPlainType()),
			() -> attributeConstraint.getClass().getSimpleName() + " constraint can be used only on String attributes - `" + attributeDefinition.getName() + "` is `" + attributeDefinition.getType() + "`!"
		);
	}

	/**
	 * Transforms a {@link Predicate} of type {@link String} to a {@link Predicate} of type {@link Stream}<{@link Optional}<{@link AttributeValue}>>.
	 * The transformation involves inspecting the stream of attribute values, checking if each attribute
	 * meets the given string predicate.
	 *
	 * @param predicate the predicate to test each attribute string value.
	 * @return a predicate applied to a stream of optional attribute values.
	 */
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> transformPredicate(@Nonnull Predicate<String> predicate) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = Objects.requireNonNull(attr.get().value());
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[]) theValue).map(String.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((String) theValue);
					}
				}
			}
		);
	}

	/**
	 * Builds the prefetch-path predicate so it compares strings under the same canonical equivalence the
	 * inverted index uses. The {@link FilterIndex} normalizes every stored String key and every incoming
	 * search term to Unicode NFD; the prefetched raw entity attribute value may be in any canonically
	 * equivalent form (typically precomposed NFC), so both the search term and each candidate value are
	 * normalized to NFD here before applying {@link #stringPredicate}. The search term is normalized once and
	 * the resulting predicate is reused for every (possibly array-element) value.
	 *
	 * @param textToSearch the raw search term supplied in the query
	 * @return a predicate over a single String attribute value that is interchangeable with the index path
	 */
	@Nonnull
	private Predicate<String> createCanonicalPredicate(@Nonnull String textToSearch) {
		final String normalizedTextToSearch = Normalizer.normalize(textToSearch, Normalizer.Form.NFD);
		return value -> value != null
			&& this.stringPredicate.test(Normalizer.normalize(value, Normalizer.Form.NFD), normalizedTextToSearch);
	}

	/**
	 * Creates an alternative bitmap filter for processing attribute conditions.
	 *
	 * @param filterByVisitor     the visitor handling the filter-by processing context
	 * @param attributeConstraint the attribute filter string search constraint leaf that provides the attribute name and the text to search for
	 * @param attributeName       the name of the attribute to filter by
	 * @param predicate           the predicate to test each attribute string value
	 * @return an instance of AttributeBitmapFilter configured with the specified parameters
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull String attributeName,
		@Nonnull Predicate<String> predicate
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				assertStringType(attributeConstraint, attributeSchema);
				return transformPredicate(predicate);
			}
		);
	}

	/**
	 * Tells whether the {@link TrigramIndex} can answer this constraint - i.e. whether a value matching it necessarily
	 * contains the search term as a contiguous run of code points, which is what makes a trigram intersection a sound
	 * candidate generator for it.
	 *
	 * Default `false`, so a subclass opts in deliberately. `contains` and `ends with` do; `starts with` does not,
	 * despite qualifying on the soundness test, because its buckets form one contiguous run from an anchor and the
	 * anchored walk that exploits that beats any candidate generation.
	 *
	 * @return whether the trigram substring index may serve this constraint
	 */
	protected boolean isServedByTrigramIndex() {
		return false;
	}

	/**
	 * Resolves the records of one entity index, taking the attribute's {@link TrigramIndex} when there is one and it is
	 * worth taking, and the historical bucket scan otherwise.
	 *
	 * The fallback here is the SCAN, never an empty result - an absent, unusable or unprofitable accelerator must
	 * change how fast the answer arrives and nothing else. The one genuinely empty case, an index the attribute has no
	 * filter index in at all, is the pre-existing behaviour of `FilterByVisitor#applyOnFilterIndexes` and is kept
	 * verbatim.
	 *
	 * ## How a reduced index is served by an accelerator it does not own
	 *
	 * A trigram index is hosted once per `(attribute, locale)` of the GLOBAL entity index and never per reduced index -
	 * of which a large catalog has hundreds of thousands. `hoistedGlobalSubstringFormulas` therefore carries ONE
	 * already-verified answer per scope, computed over the global index by
	 * {@link #hoistGlobalSubstringFormulas} before this method is invoked for any index, and this method intersects it
	 * with the target index's own primary keys. Both sides speak owner entity primary keys for an entity-level
	 * attribute - `AttributeIndex#createAttributeKey` files it under the very same key inside a reduced index as inside
	 * the global one - so the intersection is exactly what that index's own scan would have produced.
	 *
	 * ## The crossing that must NOT be attempted
	 *
	 * What crosses from the global index to a reduced one here is a formula over ENTITY PRIMARY KEYS. Handing the
	 * reduced index's own tree the global candidate VALUE IDS instead is available-looking, compiles, and is silently
	 * wrong: a reduced index's inverted index mints no value ids at all, so
	 * {@link InvertedIndex#getRecordsOfValueIdsMatching} answers with an empty {@link MatchedBuckets} and
	 * {@link InvertedIndex#getValueById} answers `null`. That shape returns an empty result rather than refusing, and
	 * passes any test whose fixture is small enough for the empty answer to look plausible.
	 *
	 * @param entityIndex                    the entity index being resolved
	 * @param hoistedGlobalSubstringFormulas the per-scope global substring answers, empty when the trigram path was
	 *                                       declined or does not apply
	 * @param referenceSchema                the reference schema owning the attribute, or `null` for entity-level
	 *                                       attributes
	 * @param attributeDefinition            the schema of the attribute being filtered
	 * @param locale                         the locale the attribute is filed under, or `null` when it is not localized
	 * @param textToSearch                   the raw search term supplied in the query
	 * @return the matching entity primary keys of that index
	 */
	@Nonnull
	private Formula resolveFromIndex(
		@Nonnull EntityIndex entityIndex,
		@Nonnull Map<Scope, Formula> hoistedGlobalSubstringFormulas,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nullable Locale locale,
		@Nonnull String textToSearch
	) {
		final FilterIndex filterIndex = entityIndex.getFilterIndex(referenceSchema, attributeDefinition, locale);
		if (filterIndex == null) {
			return EmptyFormula.INSTANCE;
		}
		final Formula globalSubstringFormula = hoistedGlobalSubstringFormulas.get(entityIndex.getIndexKey().scope());
		if (globalSubstringFormula == null) {
			return this.filterIndexResolver.apply(filterIndex, textToSearch);
		}
		// the global index IS the whole primary key universe, so intersecting it with its own primary keys would only
		// add a formula node - it takes the hoisted answer verbatim, exactly as it did before reduced indexes joined in
		return entityIndex instanceof GlobalEntityIndex ?
			globalSubstringFormula :
			FormulaFactory.and(entityIndex.getAllPrimaryKeysFormula(), globalSubstringFormula);
	}

	/**
	 * Computes, once for the whole target index set, the global substring answer each of its members will be
	 * intersected with - one per {@link Scope} the set spans, since a {@link TrigramIndex} is hosted per global index
	 * and a global index per scope.
	 *
	 * ## Why this is hoisted rather than done per index
	 *
	 * `FilterByVisitor#applyOnIndexes` runs its lambda once per index in the target set, and a `hierarchyWithin` over a
	 * broad category builds one reduced index per node of the requested subtree - so the intersection and the exact
	 * verification behind it would otherwise be repeated hundreds or thousands of times over the very same global tree.
	 * The shape is the one `HierarchyOfReferenceTranslator` already runs in production: one memoised global formula,
	 * AND-ed against each reduced index's own primary keys, OR-ed across the fan-out.
	 *
	 * ## Why the operand is built from the global index rather than cloned out of the plan
	 *
	 * `ExtraResultPlanningVisitor#canUseShortcut` documents why reusing an already-planned formula as a global operand
	 * is unsafe: the planner may have selected a REDUCED_ENTITY index set, so the clone's leaves express a narrower
	 * primary key universe than the composition assumes. Resolving the {@link GlobalEntityIndex} from the query context
	 * makes the operand's universe the whole collection by construction - and the context is pinned to one catalog
	 * version for the whole query, so the global index and the reduced ones it is composed with cannot straddle a
	 * version boundary.
	 *
	 * ## What the gate is priced against
	 *
	 * The one computation displaces the scan of EVERY member of the target set, so it is priced against their sum
	 * rather than against the global tree's own bucket count - see {@link #sumDistinctValuesUpTo}, which is where the
	 * summation and its early exit live. Per scope, because a scope's operand is composed only with that scope's
	 * members.
	 *
	 * ## Confinement to entity-level attributes
	 *
	 * The trigram path stays behind `referenceSchema == null`. Today a reference attribute can never carry a trigram
	 * index - the schema layer refuses any filter capability on one, and `GlobalEntityIndex#maintainsTrigramIndex`
	 * asserts the same premise on the write side - but that schema restriction is documented as liftable, and the
	 * `instanceof GlobalEntityIndex` test that used to close the hole incidentally is gone. Were it lifted, global
	 * postings for a reference attribute would mean "the owner carries this value on SOME reference of that type",
	 * while a reduced index means one specific reference, and the intersection would answer over-broadly. The guard
	 * below is what makes that day take the scan instead of composing a wrong answer.
	 *
	 * @param filterByVisitor     the filter-by processing context
	 * @param attributeConstraint the constraint being translated, which keys the per-query memo
	 * @param referenceSchema     the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeDefinition the schema of the attribute being filtered
	 * @param locale              the locale the attribute is filed under, or `null` when it is not localized
	 * @param textToSearch        the raw search term supplied in the query
	 * @return the global substring answer per scope, empty when no scope takes the trigram path
	 */
	@Nonnull
	private Map<Scope, Formula> hoistGlobalSubstringFormulas(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nullable Locale locale,
		@Nonnull String textToSearch
	) {
		// an unknown entity type means the target set is the catalog index rather than any entity index, and a trigram
		// index is hosted per entity collection - there is nothing to hoist, and nothing to ask an entity schema for
		if (!isServedByTrigramIndex() || referenceSchema != null || !filterByVisitor.isEntityTypeKnown()) {
			return Collections.emptyMap();
		}
		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		final String entityType = processingScope.getEntitySchemaOrThrowException().getName();
		final AttributeIndexKey attributeIndexKey = AttributeIndex.createAttributeKey(
			null, attributeDefinition, locale
		);

		// the accelerator is resolved BEFORE anything walks the target set, and the walk that prices the gate lives
		// inside `TrigramSubstringSearch#match` - so an attribute that keeps no accelerator, by far the common case
		// since only a SUBSTRING-declaring attribute has one, costs two map lookups per scope and nothing else
		EnumMap<Scope, Formula> hoisted = null;
		for (final Scope scope : processingScope.getScopes()) {
			final GlobalEntityIndex globalEntityIndex = queryContext
				.getGlobalEntityIndexIfExists(entityType, scope)
				.orElse(null);
			final TrigramIndex trigramIndex = globalEntityIndex == null ?
				null : globalEntityIndex.getTrigramIndex(attributeIndexKey);
			if (trigramIndex == null) {
				continue;
			}
			final Formula globalSubstringFormula = createGlobalSubstringFormula(
				filterByVisitor, attributeConstraint, attributeDefinition, locale, textToSearch,
				scope, globalEntityIndex, trigramIndex
			);
			if (globalSubstringFormula != null) {
				if (hoisted == null) {
					hoisted = new EnumMap<>(Scope.class);
				}
				hoisted.put(scope, globalSubstringFormula);
			}
		}
		return hoisted == null ? Collections.emptyMap() : hoisted;
	}

	/**
	 * Sums the distinct values the scan of `scope`'s members of the target set would visit, stopping the moment the
	 * running total reaches `threshold`.
	 *
	 * ## Why walking the target set to decide is not the bug it looks like
	 *
	 * The gate is a threshold question rather than a request for a total, so the walk is bounded by `threshold` and
	 * not by the width of the fan-out - a `hierarchyWithin` over a broad category can offer hundreds of thousands of
	 * reduced indexes, and this stops after however few of them carry `threshold` distinct values between them. The
	 * worst case is exhausting the set, which happens exactly when the answer is "decline": one `getFilterIndex` plus
	 * one `getBucketCount` per index. That is strictly dominated by the scan it is deciding against, which resolves
	 * the very same filter indexes and then visits every bucket of every one of them.
	 *
	 * Indexes with no filter index for the attribute are skipped - they contribute no scan, and the per-index step
	 * answers `EmptyFormula` for them regardless.
	 *
	 * @param filterByVisitor     the filter-by processing context, whose index stream IS the target set
	 * @param scope               the scope whose members are counted, since each scope is served by its own global
	 *                            index and composed only with its own members
	 * @param attributeDefinition the schema of the attribute being filtered
	 * @param locale              the locale the attribute is filed under, or `null` when it is not localized
	 * @param threshold           the total the caller compares against, and the point at which counting may stop
	 * @return the summed distinct value count, truncated at `threshold`
	 */
	private static long sumDistinctValuesUpTo(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Scope scope,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nullable Locale locale,
		long threshold
	) {
		// iterated rather than folded: the early exit is the whole point, and a stream cannot break out of a fold
		final Iterator<EntityIndex> targetIndexes = filterByVisitor.getEntityIndexStream().iterator();
		long scannedDistinctValues = 0L;
		while (scannedDistinctValues < threshold && targetIndexes.hasNext()) {
			final EntityIndex entityIndex = targetIndexes.next();
			if (entityIndex.getIndexKey().scope() != scope) {
				continue;
			}
			final FilterIndex filterIndex = entityIndex.getFilterIndex(null, attributeDefinition, locale);
			if (filterIndex != null) {
				scannedDistinctValues += filterIndex.getInvertedIndex().getBucketCount();
			}
		}
		return scannedDistinctValues;
	}

	/**
	 * Builds the global substring answer of a single scope, or returns `null` when that scope must take the scan.
	 *
	 * The result is memoised on {@link QueryPlanningContext#computeOnlyOnce}, which lives on the root planning context
	 * - so it is shared by every candidate plan and every nested sub-query of the same client query rather than
	 * recomputed per plan. Beside the global index's own id the key carries the trigram index's id, which names the
	 * `(attribute, locale)` the answer was narrowed by and changes on every write to it.
	 *
	 * ## Why the size of the displaced scan is NOT part of the key
	 *
	 * The memoised artefact is a pure function of the trigram index, the shared value tree and the pattern; the scan
	 * size decides only whether this method gets as far as building it. A plan whose gate declined never reaches the
	 * memo at all, so no entry of it can leak into one - and keying on the size would merely stop two plans that both
	 * accelerated from sharing the identical answer. It would become mandatory the moment the GATE itself moved
	 * inside the memo, which is exactly why {@link TrigramSubstringSearch#match} stays outside it.
	 *
	 * ## What a second candidate plan pays, and why that is deliberate
	 *
	 * `match` runs before the memo, so a second candidate plan that also accelerates over this scope intersects and
	 * verifies the candidates again and then discards them on the memo hit, keeping only the fold. Three things make
	 * that the right boundary rather than an accident of where it fell:
	 *
	 * - the gate is a function of the TARGET SET, which differs per candidate plan, while the key deliberately is
	 *   not. Moving `match` inside the supplier would move the gate inside a key that cannot tell two target sets
	 *   apart, so a plan whose own gate declined would inherit the accepted answer of whichever plan was translated
	 *   first - a cost decision silently reversed, and reversed differently depending on the planner's candidate
	 *   ordering;
	 * - it is not a regression against keying on the scan size. That key made the second plan MISS the memo, so it
	 *   intersected, verified AND folded; it now intersects, verifies, and reuses the fold. Strictly less work;
	 * - what remains is one redundant intersection-and-verification per query, in the case where two plans accelerate
	 *   over the same scope - against the one-per-index this hoist removes.
	 *
	 * Recovering even that would mean splitting `match` at its own documented seam - steps 1-4 (pre-flights,
	 * cardinality probe, gate) outside the memo and steps 5-8 (intersect, resolve, verify) inside its supplier, so
	 * that the decline conditions stay whole inside {@link TrigramSubstringSearch} rather than smearing into this
	 * file. That is a wider API change than this increment needs, and is left as a follow-up.
	 *
	 * @param filterByVisitor     the filter-by processing context
	 * @param attributeConstraint the constraint being translated, which keys the per-query memo
	 * @param attributeDefinition the schema of the attribute being filtered
	 * @param locale              the locale the attribute is filed under, or `null` when it is not localized
	 * @param textToSearch        the raw search term supplied in the query
	 * @param scope               the scope being served, whose members alone price the gate
	 * @param globalEntityIndex   the global index of that scope
	 * @param trigramIndex        that index's accelerator for this attribute and locale
	 * @return the global substring answer, or `null` when this scope must take the scan
	 */
	@Nullable
	private Formula createGlobalSubstringFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nullable Locale locale,
		@Nonnull String textToSearch,
		@Nonnull Scope scope,
		@Nonnull GlobalEntityIndex globalEntityIndex,
		@Nonnull TrigramIndex trigramIndex
	) {
		final FilterIndex globalFilterIndex = globalEntityIndex.getFilterIndex(null, attributeDefinition, locale);
		if (globalFilterIndex == null) {
			return null;
		}
		final InvertedIndex sharedValueTree = globalFilterIndex.getInvertedIndex();
		final MatchedBuckets matched = TrigramSubstringSearch.match(
			trigramIndex, sharedValueTree, textToSearch, this.stringPredicate,
			threshold -> sumDistinctValuesUpTo(filterByVisitor, scope, attributeDefinition, locale, threshold)
		);
		if (matched == null) {
			return null;
		}
		return filterByVisitor.getQueryContext().computeOnlyOnce(
			List.of((EntityIndex) globalEntityIndex),
			attributeConstraint,
			// EAGER assembly - the one place in this path that presupposes eager evaluation
			() -> sharedValueTree.toFormula(matched, TrigramSubstringSearch.versionIdsOf(trigramIndex)),
			trigramIndex.getId()
		);
	}

	/**
	 * Translates the given attribute string search constraint into a filtering formula suitable for application on a filter index.
	 * This method is intended for internal use to assist the filtering process by generating an appropriate formula based on the given
	 * constraints and the current state of the filter visitor.
	 *
	 * @param attributeConstraint the attribute filter string search constraint leaf that provides the attribute name and the text to search for
	 * @param filterByVisitor     the filter by visitor context that provides methods to retrieve attribute schema, check entity type knowledge,
	 *                            and apply filters on index
	 * @return a Formula object representing the filtering formula based on the given constraints and visitor context
	 */
	@Nonnull
	protected Formula translateInternal(
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final String attributeName = attributeConstraint.getAttributeName();
		final String textToSearch = attributeConstraint.getTextToSearch();
		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(
			filterByVisitor, attributeName, AttributeTrait.FILTERABLE
		);

		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final AttributeSchemaContract attributeDefinition = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeDefinition);
			assertStringType(attributeConstraint, attributeDefinition);

			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema();
			final Locale locale = attributeDefinition.isLocalized() ? filterByVisitor.getLocale() : null;
			// hoisted out of the per-index lambda on purpose: the global computation is amortized across the whole
			// target set, which is what makes it affordable for a fan-out of reduced indexes at all
			final Map<Scope, Formula> hoistedGlobalSubstringFormulas = hoistGlobalSubstringFormulas(
				filterByVisitor, attributeConstraint, referenceSchema, attributeDefinition, locale, textToSearch
			);
			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeDefinition instanceof GlobalAttributeSchemaContract,
				attributeKey,
				filterByVisitor.applyOnIndexes(
					entityIndex -> resolveFromIndex(
						entityIndex, hoistedGlobalSubstringFormulas, referenceSchema, attributeDefinition,
						locale, textToSearch
					)
				)
			);

			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					createAlternativeBitmapFilter(
						filterByVisitor, attributeConstraint, attributeName,
						createCanonicalPredicate(textToSearch)
					)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute " + this.description + " filter",
				createAlternativeBitmapFilter(
					filterByVisitor, attributeConstraint, attributeName,
					createCanonicalPredicate(textToSearch)
				)
			);
		}
	}

}
