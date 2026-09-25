/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.NegationResolution;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeIs} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeIsTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeIs> {

	/**
	 * Translates an "IS NULL" attribute condition into a corresponding Formula.
	 *
	 * During the candidate discovery of a `referenceHaving` ({@link NegationResolution#PER_ROW}) the constraint
	 * widens to the super set, exactly as a `not` does: the attribute is still resolved first, so an undeclared one
	 * is refused just the same.
	 *
	 * @param attributeName   the name of the attribute to be checked for null values
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return a Formula representing the translated "IS NULL" condition for the specified attribute
	 */
	@Nonnull
	private static Formula translateIsNull(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final Set<Scope> scopes = processingScope.getScopes();
			final AttributeSchemaContract attributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE)
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema();
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);
			if (processingScope.getNegationResolution() == NegationResolution.PER_ROW) {
				// a type-level index can only say that SOME row of a partition carries the attribute, so an exact
				// subtraction here drops every partition holding a null row next to a non-null one; the caller
				// re-examines every candidate row by row, so widening keeps each one a candidate and leaves the null
				// test to be settled inside the index it belongs to - see NegationResolution
				return filterByVisitor.getSuperSetFormula();
			}

			// if attribute is unique prefer O(1) hash map lookup over inverted index
			if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)
			) {
				return wrapFormula(
					attributeSchema,
					attributeKey,
					FutureNotFormula.postProcess(
						createNullGloballyUniqueSubtractionFormula(globalAttributeSchema, filterByVisitor),
						EnclosingContainerRelation.DISJUNCTION
					)
				);
			} else {
				return wrapFormula(
					attributeSchema,
					attributeKey,
					createNullSubtractionFormula(referenceSchema, attributeSchema, filterByVisitor)
				);
			}
		} else {
			return new EntityFilteringFormula(
				"attribute is filter",
				createAlternativeNullBitmapFilter(attributeName, filterByVisitor)
			);
		}
	}

	/**
	 * Creates the formula selecting the records that carry no value of the attribute: one `superSet \ carriers`
	 * subtraction per entity index in scope, joined by a disjunction.
	 *
	 * The per-index formulas are built through {@link FilterByVisitor#applyOnIndexes(java.util.function.Function)},
	 * which tags each with the index that produced it whenever a `referenceHaving` body is being translated - the tag
	 * is what lets `ReferenceBodyTransposer` answer the null test one reference row at a time, instead of reading it
	 * as index-independent and combining a null row of one partition with a sibling constraint met by another.
	 *
	 * Per index the contribution is:
	 *
	 * - every record of the index, when nothing in it carries the attribute - that index is one where every record
	 *   is null, not one that has nothing to say;
	 * - nothing, when {@link #subtractionMayYieldRecords(Formula, Formula)} proves every record carries it;
	 * - the subtraction otherwise.
	 *
	 * An all-empty result collapses to {@link EmptyFormula} during planning.
	 *
	 * @param referenceSchema the reference schema the attribute belongs to, or NULL for an entity attribute
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return the null formula, or {@link EmptyFormula} when no index can hold a null record
	 */
	@Nonnull
	private static Formula createNullSubtractionFormula(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final Locale locale = filterByVisitor.getLocale();
		final Set<Locale> everyLocale = attributeSchema.isLocalized() ?
			getLocalesTheValueMayBeStoredIn(filterByVisitor) : Set.of();
		// `applyOnIndexes`, never `applyOnFilterIndexes`: it turns an index without the attribute into EMPTY before
		// the lambda runs, and for a null test that is the index where EVERY record matches
		return filterByVisitor.applyOnIndexes(
			entityIndex -> {
				final Formula superSet = entityIndex.getAllPrimaryKeysFormula();
				if (superSet instanceof EmptyFormula) {
					// nothing is tracked here, so nothing can be null here
					return EmptyFormula.INSTANCE;
				}
				final Formula carriers = getCarriersFormula(
					entityIndex, referenceSchema, attributeSchema, locale, everyLocale
				);
				if (carriers == null) {
					// nothing in this index carries the attribute, so every record in it is null
					return superSet;
				}
				return subtractionMayYieldRecords(carriers, superSet) ?
					new NotFormula(carriers, superSet) : EmptyFormula.INSTANCE;
			}
		);
	}

	/**
	 * Creates the formula selecting the records that carry a value of the attribute: the carriers of every entity
	 * index in scope, joined by a disjunction.
	 *
	 * The carriers come from {@link #getCarriersFormula} - the very source {@link #createNullSubtractionFormula}
	 * subtracts - so that per index the null and the not-null test always split the records between them. A unique
	 * index cannot stand in for them: a localized attribute unique across locales in one requested scope and within
	 * a locale in another admits a query without a locale, the unique index of the second scope cannot be looked up
	 * without one, and the type-level index of a reference keeps no usable unique index at all. The per-index
	 * formulas are built through
	 * {@link FilterByVisitor#applyOnIndexes(java.util.function.Function)} so they carry the tag the row-scoping
	 * rebuild of a `referenceHaving` body relies on; an index keeping no structure for the attribute contributes
	 * nothing.
	 *
	 * @param referenceSchema the reference schema the attribute belongs to, or NULL for an entity attribute
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return the not-null formula, or {@link EmptyFormula} when no index holds a carrier
	 */
	@Nonnull
	private static Formula createNotNullFormula(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final Locale locale = filterByVisitor.getLocale();
		final Set<Locale> everyLocale = attributeSchema.isLocalized() ?
			getLocalesTheValueMayBeStoredIn(filterByVisitor) : Set.of();
		return filterByVisitor.applyOnIndexes(
			entityIndex -> {
				final Formula carriers = getCarriersFormula(
					entityIndex, referenceSchema, attributeSchema, locale, everyLocale
				);
				return carriers == null ? EmptyFormula.INSTANCE : carriers;
			}
		);
	}

	/**
	 * Returns the records of the index that carry a value of the attribute, or NULL when the index keeps no
	 * structure for it at all - which means none of its records carries a value.
	 *
	 * Only filter indexes are read, for unique attributes too: {@link EntityIndex#upsertAttribute} writes the filter
	 * index for every attribute that is unique **or** filterable, on every index type, whereas the unique index is
	 * not kept everywhere - {@link io.evitadb.index.ReducedGroupEntityIndex#insertUniqueAttribute} is a no-op
	 * (entities sharing a group make per-group uniqueness meaningless), and the type-level index of a reference holds
	 * an empty unique index for a localized reference attribute unique across locales. A filter index is split per
	 * locale for every localized attribute, so a localized attribute is read in every locale whenever the question
	 * is not bound to the query locale:
	 *
	 * - when it is unique across locales in the scope of the index, carrying a value means carrying it in **any**
	 *   locale - the uniqueness itself ignores the locale, and this is also what lets such an attribute be queried
	 *   without a locale (see `createAttributeKey`);
	 * - when no query locale was requested at all, which is admitted only because another requested scope keeps the
	 *   attribute unique across locales - reading the missing locale alone would report every record as null.
	 *
	 * Every other localized attribute is read in the query locale.
	 *
	 * @param entityIndex     the index to read
	 * @param referenceSchema the reference schema the attribute belongs to, or NULL for an entity attribute
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param locale          the query locale, or NULL when none was requested
	 * @param everyLocale     the locales a value may be stored in, read for a localized attribute not bound to the
	 *                        query locale
	 * @return the carrying records, or NULL when the index keeps no structure for the attribute
	 */
	@Nullable
	private static Formula getCarriersFormula(
		@Nonnull EntityIndex entityIndex,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale,
		@Nonnull Set<Locale> everyLocale
	) {
		if (!attributeSchema.isLocalized()) {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(referenceSchema, attributeSchema, null);
			return filterIndex == null ? null : filterIndex.getAllRecordsFormula();
		}
		final Scope scope = entityIndex.getIndexKey().scope();
		final boolean uniqueAcrossLocales = attributeSchema.isUniqueInScope(scope) &&
			!attributeSchema.isUniqueWithinLocaleInScope(scope);
		if (locale != null && !uniqueAcrossLocales) {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(referenceSchema, attributeSchema, locale);
			return filterIndex == null ? null : filterIndex.getAllRecordsFormula();
		}
		final Formula[] carriers = everyLocale.stream()
			.map(it -> entityIndex.getFilterIndex(referenceSchema, attributeSchema, it))
			.filter(Objects::nonNull)
			.map(FilterIndex::getAllRecordsFormula)
			.toArray(Formula[]::new);
		return carriers.length == 0 ? null : FormulaFactory.or(carriers);
	}

	/**
	 * Returns every locale a value of a localized attribute may be stored in: the locales of the queried collection,
	 * which owns the value, together with those of the entity whose indexes the processing scope reads - the two
	 * differ when a bidirectional `referenceHaving` rewrite reads the owner's reference attributes from the indexes
	 * of the referenced collection. A locale in which nothing is stored costs a single missed lookup per index.
	 *
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return the locales to read the attribute in
	 */
	@Nonnull
	private static Set<Locale> getLocalesTheValueMayBeStoredIn(@Nonnull FilterByVisitor filterByVisitor) {
		final Set<Locale> queriedLocales = filterByVisitor.getSchema().getLocales();
		final EntitySchemaContract indexedSchema = filterByVisitor.getProcessingScope().getEntitySchema();
		if (indexedSchema == null || queriedLocales.containsAll(indexedSchema.getLocales())) {
			return queriedLocales;
		}
		final Set<Locale> locales = new LinkedHashSet<>(queriedLocales);
		locales.addAll(indexedSchema.getLocales());
		return locales;
	}

	/**
	 * Tells whether `superSet \ subtracted` may still yield records, and therefore whether building a
	 * {@link NotFormula} for it is worth the nodes it costs.
	 *
	 * Answers conservatively, and the asymmetry is deliberate: FALSE is returned only when emptiness is *certain*,
	 * so anything this method cannot settle cheaply is reported as "may yield records" and left to the execution
	 * phase, which computes the real difference. A wrong FALSE would silently drop records from the answer; a
	 * wrong TRUE costs only the nodes it was trying to save.
	 *
	 * An index whose every record carries a value for the attribute contributes nothing to an `attributeIs(NULL)`
	 * disjunction, yet it still costs a {@link NotFormula} and its two operands in the tree - and the enclosing
	 * disjunction folds only constant operands, so those nodes would survive planning, hashing, cost estimation and
	 * every post-processor walk. Dropping them here is what lets an all-empty disjunction collapse to
	 * {@link EmptyFormula} instead.
	 *
	 * The test is the exact subset relation rather than a cardinality comparison: equal sizes would only imply an
	 * empty difference under the assumption that the filter index never holds a record the entity index does not,
	 * and this code does not need to rest on that. {@link PersistentRoaringBitmap#contains(PersistentRoaringBitmap)}
	 * walks containers with an early exit, so the check is cheap and allocation-free.
	 *
	 * @param subtracted the records that do carry a value
	 * @param superSet   all records tracked by the index
	 * @return FALSE only when the difference is certainly empty, TRUE whenever it may hold records
	 */
	private static boolean subtractionMayYieldRecords(@Nonnull Formula subtracted, @Nonnull Formula superSet) {
		if (superSet instanceof EmptyFormula) {
			// nothing is tracked here, so nothing can remain after the subtraction
			return false;
		}
		if (!(subtracted instanceof ConstantFormula subtractedConstant) ||
			!(superSet instanceof ConstantFormula superSetConstant)
		) {
			// the operands are not plain bitmaps - leave the subtraction to the execution phase
			return true;
		}
		// `contains` answers TRUE when every tracked record carries a value - the difference is then empty,
		// which is precisely the case this method reports as FALSE, hence the negation
		return !RoaringBitmapBackedBitmap.getRoaringBitmap(subtractedConstant.getDelegate())
			.contains(RoaringBitmapBackedBitmap.getRoaringBitmap(superSetConstant.getDelegate()));
	}

	/**
	 * Creates an array of Formulas for filtering entities where a specified attribute is null based on information
	 * in unique indexes. The formulas apply a subtraction operation to filter out records with non-null attributes.
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param filterByVisitor     the visitor responsible for filtering operations
	 * @return an array of Formulas representing the null filterable subtraction conditions
	 */
	@Nonnull
	private static Formula[] createNullGloballyUniqueSubtractionFormula(
		@Nonnull GlobalAttributeSchemaContract attributeDefinition,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		return new Formula[]{
			filterByVisitor.applyOnGlobalUniqueIndexes(
				attributeDefinition,
				uniqueIndex -> new NotFormula(
					uniqueIndex.getRecordIdsFormula(filterByVisitor.getEntityType(), filterByVisitor.getEntityTypeClassifierResolver()),
					FormulaFactory.or(
						filterByVisitor.getEntityIndexStream()
							.map(EntityIndex::getAllPrimaryKeysFormula)
							.toArray(Formula[]::new)
					)
				)
			)
		};
	}

	/**
	 * Aggregates the provided formulas into a single Formula (either empty formula or disjunctive join).
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param attributeKey        the key of the attribute being processed
	 * @param formula            an array of formulas to be aggregated
	 * @return an AbstractFormula that represents the aggregation of the input formulas
	 */
	@Nonnull
	private static Formula wrapFormula(
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula formula
	) {
		if (formula instanceof EmptyFormula) {
			return formula;
		} else {
			return new AttributeFormula(
				attributeDefinition instanceof GlobalAttributeSchemaContract,
				attributeKey,
				formula
			);
		}
	}

	/**
	 * Translates an "IS NOT NULL" attribute condition into a corresponding Formula.
	 *
	 * @param attributeName   the name of the attribute to be checked for non-null values
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return a Formula representing the translated "IS NOT NULL" condition for the specified attribute
	 */
	@Nonnull
	private static Formula translateIsNotNull(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final Set<Scope> scopes = processingScope.getScopes();
			final AttributeSchemaContract attributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE)
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);
			// if attribute is unique prefer O(1) hash map lookup over histogram
			if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)
			) {
				return new AttributeFormula(
					true,
					attributeKey,
					filterByVisitor.applyOnGlobalUniqueIndexes(
						globalAttributeSchema,
						index -> {
							final Bitmap recordIds = index.getRecordIds(filterByVisitor.getEntityType(), filterByVisitor.getEntityTypeClassifierResolver());
							return recordIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
						}
					)
				);
			} else {
				final AttributeFormula filteringFormula = new AttributeFormula(
					attributeSchema instanceof GlobalAttributeSchemaContract,
					attributeKey,
					createNotNullFormula(processingScope.getReferenceSchema(), attributeSchema, filterByVisitor)
				);
				// the prefetch alternative is offered only for an attribute unique in none of the requested scopes
				if (filterByVisitor.isPrefetchPossible() &&
					scopes.stream().noneMatch(attributeSchema::isUniqueInScope)
				) {
					return new SelectionFormula(
						filteringFormula,
						createAlternativeNotNullBitmapFilter(attributeKey.attributeName(), filterByVisitor)
					);
				} else {
					return filteringFormula;
				}
			}
		} else {
			return new EntityFilteringFormula(
				"attribute is filter",
				createAlternativeNotNullBitmapFilter(attributeName, filterByVisitor)
			);
		}
	}

	/**
	 * Creates an AttributeBitmapFilter that checks for the absence of a specified attribute.
	 *
	 * @param attributeName   the name of the attribute to be checked for absence
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return an AttributeBitmapFilter instance configured to check for attribute absence
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeNullBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			Objects.requireNonNull(processingScope.getRequirements()),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> optionalStream -> optionalStream.noneMatch(Optional::isPresent),
			AttributeTrait.FILTERABLE
		);
	}

	/**
	 * Creates an AttributeBitmapFilter that ensures a specified attribute is not null.
	 *
	 * @param attributeName   the name of the attribute to check for non-null values
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return an AttributeBitmapFilter instance configured to check for non-null attribute values
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeNotNullBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			Objects.requireNonNull(processingScope.getRequirements()),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> optionalStream -> optionalStream.anyMatch(Optional::isPresent),
			AttributeTrait.FILTERABLE
		);
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeIs attributeIs, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeIs.getAttributeName();

		// also consider the possibly more special values supported in the future
		return switch (attributeIs.getAttributeSpecialValue()) {
			case NULL -> translateIsNull(attributeName, filterByVisitor);
			case NOT_NULL -> translateIsNotNull(attributeName, filterByVisitor);
		};
	}

}
