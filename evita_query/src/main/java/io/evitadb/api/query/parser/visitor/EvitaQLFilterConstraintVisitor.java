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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.parser.Value;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.AttributeInRangeNowConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.EntityScopeConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.FacetIncludingChildrenConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.FacetIncludingChildrenExceptConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.FacetIncludingChildrenHavingConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.FilterInScopeConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.GroupHavingConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.HierarchyAnyHavingConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all filter type constraints
 * ({@link FilterConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 * @see EvitaQLConstraintVisitor
 */
public class EvitaQLFilterConstraintVisitor extends EvitaQLBaseConstraintVisitor<FilterConstraint> {

	/**
	 * Parses serializable attribute values for comparison and membership filters.
	 */
	@Nonnull
	protected final EvitaQLValueTokenVisitor attributeValueTokenVisitor =
		EvitaQLValueTokenVisitor.withAttributeValueTypesAllowed();
	protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);
	/**
	 * Parses a list of strings. A bound array or collection is the whole list only when it is bound to a lone
	 * parameter, so it is not an allowed item of a longer sequence.
	 */
	@Nonnull
	protected final EvitaQLValueTokenVisitor stringValueListTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		String.class
	);
	protected final EvitaQLValueTokenVisitor intValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class
	);
	/**
	 * Parses range points as numbers or date-times. The Java factory normalizes finite floats and doubles to decimals.
	 */
	@Nonnull
	protected final EvitaQLValueTokenVisitor inRangeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class,
		Float.class,
		Double.class,
		BigDecimal.class,
		OffsetDateTime.class
	);
	protected final EvitaQLValueTokenVisitor priceBetweenArgValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class,
		BigDecimal.class
	);
	/**
	 * Parses numeric histogram bounds, including finite floats and doubles that convert to decimals.
	 */
	@Nonnull
	protected final EvitaQLValueTokenVisitor numericValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class,
		Float.class,
		Double.class,
		BigDecimal.class
	);
	protected final EvitaQLValueTokenVisitor offsetDateTimeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(OffsetDateTime.class);
	protected final EvitaQLValueTokenVisitor localeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Locale.class);
	protected final EvitaQLValueTokenVisitor currencyValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Currency.class);
	protected final EvitaQLValueTokenVisitor attributeSpecialValueValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(AttributeSpecialValue.class);
	protected final EvitaQLValueTokenVisitor scopeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(Scope.class);

	@Override
	public FilterConstraint visitFilterByConstraint(EvitaQLParser.FilterByConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.filterBy(
				ctx.args.constraints
					.stream()
					.map(c -> visitChildConstraint(c, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitFilterGroupByConstraint(EvitaQLParser.FilterGroupByConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.filterGroupBy(
				ctx.args.constraints
					.stream()
					.map(c -> visitChildConstraint(c, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitAndConstraint(EvitaQLParser.AndConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.and();
				}
				return QueryConstraints.and(
					ctx.args.constraints
						.stream()
						.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
						.toArray(FilterConstraint[]::new)
				);
			}
		);
	}

	@Override
	public FilterConstraint visitOrConstraint(EvitaQLParser.OrConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.or();
				}
				return QueryConstraints.or(
					ctx.args.constraints
						.stream()
						.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
						.toArray(FilterConstraint[]::new)
				);
			}
		);
	}

	@Override
	public FilterConstraint visitNotConstraint(EvitaQLParser.NotConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.not(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}

	@Override
	public FilterConstraint visitUserFilterConstraint(EvitaQLParser.UserFilterConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.userFilter();
				}
				return QueryConstraints.userFilter(
					ctx.args.constraints
						.stream()
						.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
						.toArray(FilterConstraint[]::new)
				);
			}
		);
	}

	/**
	 * Parses a filter that matches an attribute equal to the supplied value.
	 *
	 * @param ctx attribute name and value to match
	 * @return the equality filter, or null when the value is undefined
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeEqualsConstraint(
		@Nonnull EvitaQLParser.AttributeEqualsConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.attributeValueTokenVisitor).asSerializable()
			)
		);
	}

	/**
	 * Parses a filter that matches an attribute greater than a threshold.
	 *
	 * @param ctx attribute name and exclusive lower bound
	 * @return the lower-bound filter, or null when the threshold is undefined
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeGreaterThanConstraint(
		@Nonnull EvitaQLParser.AttributeGreaterThanConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeGreaterThan(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.attributeValueTokenVisitor).asSerializable()
			)
		);
	}

	/**
	 * Parses a filter that matches an attribute greater than or equal to a threshold.
	 *
	 * @param ctx attribute name and inclusive lower bound
	 * @return the lower-bound filter, or null when the threshold is undefined
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeGreaterThanEqualsConstraint(
		@Nonnull EvitaQLParser.AttributeGreaterThanEqualsConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeGreaterThanEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.attributeValueTokenVisitor).asSerializable()
			)
		);
	}

	/**
	 * Parses a filter that matches an attribute less than a threshold.
	 *
	 * @param ctx attribute name and exclusive upper bound
	 * @return the upper-bound filter, or null when the threshold is undefined
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeLessThanConstraint(
		@Nonnull EvitaQLParser.AttributeLessThanConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeLessThan(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.attributeValueTokenVisitor).asSerializable()
			)
		);
	}

	/**
	 * Parses a filter that matches an attribute less than or equal to a threshold.
	 *
	 * @param ctx attribute name and inclusive upper bound
	 * @return the upper-bound filter, or null when the threshold is undefined
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeLessThanEqualsConstraint(
		@Nonnull EvitaQLParser.AttributeLessThanEqualsConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeLessThanEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.attributeValueTokenVisitor).asSerializable()
			)
		);
	}

	/**
	 * Parses a filter that matches an attribute within the supplied bounds.
	 *
	 * @param ctx attribute name and inclusive bounds
	 * @return the range filter, or null when both bounds are undefined
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeBetweenConstraint(
		@Nonnull EvitaQLParser.AttributeBetweenConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeBetween(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.valueFrom.accept(this.attributeValueTokenVisitor).asSerializable(),
				ctx.args.valueTo.accept(this.attributeValueTokenVisitor).asSerializable()
			)
		);
	}

	/**
	 * Parses an attribute filter, selecting the factory according to the shape of the value arguments.
	 *
	 * @param ctx attribute name and optional list of values
	 * @return the attribute filter, or null for an undefined single value
	 */
	@Nullable
	@Override
	public FilterConstraint visitAttributeInSetConstraint(@Nonnull EvitaQLParser.AttributeInSetConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier.accept(this.stringValueTokenVisitor).asString();
				if (ctx.args.values == null) {
					return QueryConstraints.attributeInSet(classifier);
				}
				final Value values = ctx.args.values.accept(this.attributeValueTokenVisitor);
				final Serializable[] arguments = values.asSerializableArray();
				return values.isSingleVariadicArgument() ?
					QueryConstraints.attributeInSet(classifier, arguments[0]) :
					QueryConstraints.attributeInSet(classifier, arguments);
			}
		);
	}

	@Override
	public FilterConstraint visitAttributeContainsConstraint(EvitaQLParser.AttributeContainsConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeContains(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeStartsWithConstraint(EvitaQLParser.AttributeStartsWithConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeStartsWith(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEndsWithConstraint(EvitaQLParser.AttributeEndsWithConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeEndsWith(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsTrueConstraint(EvitaQLParser.AttributeEqualsTrueConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				Boolean.TRUE
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsFalseConstraint(EvitaQLParser.AttributeEqualsFalseConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				Boolean.FALSE
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsConstraint(EvitaQLParser.AttributeIsConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeIs(
				ctx.args.classifier
					.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value
					.accept(this.attributeSpecialValueValueTokenVisitor)
					.asEnum(AttributeSpecialValue.class)
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsNullConstraint(EvitaQLParser.AttributeIsNullConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeIs(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				AttributeSpecialValue.NULL
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsNotNullConstraint(EvitaQLParser.AttributeIsNotNullConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.attributeIs(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				AttributeSpecialValue.NOT_NULL
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeInRangeConstraint(EvitaQLParser.AttributeInRangeConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String attributeName = ctx.args.classifier.accept(this.stringValueTokenVisitor).asString();
				final Value attributeValue = ctx.args.value.accept(this.inRangeValueTokenVisitor);

				if (attributeValue.getActualValue() == null
					|| Number.class.isAssignableFrom(attributeValue.getType())) {
					return QueryConstraints.attributeInRange(attributeName, attributeValue.asNumber());
				} else if (OffsetDateTime.class.isAssignableFrom(attributeValue.getType())) {
					return QueryConstraints.attributeInRange(attributeName, attributeValue.asOffsetDateTime());
				} else {
					throw new EvitaSyntaxException(
						ctx,
						"Filter constraint `attributeInRange` requires arguments!");
				}
			}
		);
	}

	@Override
	public FilterConstraint visitAttributeInRangeNowConstraint(AttributeInRangeNowConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String attributeName = ctx.args.classifier.accept(this.stringValueTokenVisitor).asString();
				return QueryConstraints.attributeInRangeNow(attributeName);
			}
		);
	}

	/**
	 * Parses a primary key filter, preserving the distinction between one key and a key list.
	 *
	 * @param ctx optional primary key arguments
	 * @return the primary key filter, or null for an undefined single key
	 */
	@Nullable
	@Override
	public FilterConstraint visitEntityPrimaryKeyInSetConstraint(
		@Nonnull EvitaQLParser.EntityPrimaryKeyInSetConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.entityPrimaryKeyInSet();
				}
				final Value values = ctx.args.values.accept(this.intValueTokenVisitor);
				final Integer[] arguments = values.asIntegerArray();
				return values.isSingleVariadicArgument() ?
					QueryConstraints.entityPrimaryKeyInSet(arguments[0]) :
					QueryConstraints.entityPrimaryKeyInSet(arguments);
			}
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyGreaterThanConstraint(
		EvitaQLParser.EntityPrimaryKeyGreaterThanConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.entityPrimaryKeyGreaterThan(
				ctx.args.value.accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyGreaterThanEqualsConstraint(
		EvitaQLParser.EntityPrimaryKeyGreaterThanEqualsConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.entityPrimaryKeyGreaterThanEquals(
				ctx.args.value.accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyLessThanConstraint(
		EvitaQLParser.EntityPrimaryKeyLessThanConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.entityPrimaryKeyLessThan(
				ctx.args.value.accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyLessThanEqualsConstraint(
		EvitaQLParser.EntityPrimaryKeyLessThanEqualsConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.entityPrimaryKeyLessThanEquals(
				ctx.args.value.accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyBetweenConstraint(
		EvitaQLParser.EntityPrimaryKeyBetweenConstraintContext ctx
	) {
		return parse(
			ctx,
			() -> QueryConstraints.entityPrimaryKeyBetween(
				ctx.args.valueFrom.accept(this.intValueTokenVisitor).asInt(),
				ctx.args.valueTo.accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public FilterConstraint visitEntityLocaleEqualsConstraint(EvitaQLParser.EntityLocaleEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.entityLocaleEquals(
				ctx.args.value
					.accept(this.localeValueTokenVisitor)
					.asLocale()
			)
		);
	}

	@Override
	public FilterConstraint visitPriceInCurrencyConstraint(EvitaQLParser.PriceInCurrencyConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.priceInCurrency(
				ctx.args.value
					.accept(this.currencyValueTokenVisitor)
					.asCurrency()
			)
		);
	}

	/**
	 * Parses a price list filter, preserving the distinction between one price list name and a list of names.
	 *
	 * @param ctx optional price list arguments
	 * @return the price list filter, or null for an undefined single price list
	 */
	@Nullable
	@Override
	public FilterConstraint visitPriceInPriceListsConstraints(
		@Nonnull EvitaQLParser.PriceInPriceListsConstraintsContext ctx
	) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.priceInPriceLists();
				}
				final Value values = ctx.args.classifiers.accept(this.stringValueListTokenVisitor);
				final String[] arguments = values.asStringArray();
				return values.isSingleVariadicArgument() ?
					QueryConstraints.priceInPriceLists(arguments[0]) :
					QueryConstraints.priceInPriceLists(arguments);
			}
		);
	}

	@Override
	public FilterConstraint visitPriceValidInNowConstraint(EvitaQLParser.PriceValidInNowConstraintContext ctx) {
		return parse(ctx, QueryConstraints::priceValidInNow);
	}

	@Override
	public FilterConstraint visitPriceValidInConstraint(EvitaQLParser.PriceValidInConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				return QueryConstraints.priceValidIn(
					ctx.args.value
						.accept(this.offsetDateTimeValueTokenVisitor)
						.asOffsetDateTime()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitPriceBetweenConstraint(EvitaQLParser.PriceBetweenConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.priceBetween(
				ctx.args.valueFrom.accept(this.priceBetweenArgValueTokenVisitor).asNumber(BigDecimal.class),
				ctx.args.valueTo.accept(this.priceBetweenArgValueTokenVisitor).asNumber(BigDecimal.class)
			)
		);
	}

	@Override
	public FilterConstraint visitFacetHavingConstraint(EvitaQLParser.FacetHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args.filter2 != null) {
					return QueryConstraints.facetHaving(
						ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
						ctx.args.filter1.accept(this),
						ctx.args.filter2.accept(this)
					);
				} else {
					return QueryConstraints.facetHaving(
						ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
						ctx.args.filter1.accept(this)
					);
				}
			}
		);
	}

	@Override
	public FilterConstraint visitHistogramHavingConstraint(EvitaQLParser.HistogramHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String referenceName = ctx.args.classifier
					.accept(this.stringValueTokenVisitor)
					.asString();
				final String histogramName = ctx.args.histogramName == null
					? null
					: ctx.args.histogramName.accept(this.stringValueTokenVisitor).asString();
				// histogram bounds are inherently numeric; restrict allowed token types to numeric scalars
				// and coerce to BigDecimal — the canonical type for histogram bounds across the engine
				// (HistogramHavingTranslator stashes bounds as BigDecimal and AttributeBetweenTranslator
				// narrows back to the attribute's indexed plain type via EvitaDataTypes.toTargetType).
				final BigDecimal from;
				final BigDecimal to;
				if (ctx.args.valueFrom != null && ctx.args.valueTo != null) {
					from = ctx.args.valueFrom.accept(this.numericValueTokenVisitor).asNumber(BigDecimal.class);
					to = ctx.args.valueTo.accept(this.numericValueTokenVisitor).asNumber(BigDecimal.class);
				} else {
					throw new EvitaSyntaxException(ctx,
						"HistogramHaving requires at least one of from / to to be non-null!");
				}
				final GroupHaving groupSelector = ctx.args.groupSelector == null
					? null
					: visitChildConstraint(ctx.args.groupSelector, GroupHaving.class);
				return ctx.args.histogramName == null ?
					QueryConstraints.histogramHaving(referenceName, from, to, groupSelector) :
					QueryConstraints.histogramHaving(referenceName, histogramName, from, to, groupSelector);
			}
		);
	}

	@Override
	public FilterConstraint visitFacetIncludingChildrenConstraint(FacetIncludingChildrenConstraintContext ctx) {
		return parse(ctx, FacetIncludingChildren::new);
	}

	@Override
	public FilterConstraint visitFacetIncludingChildrenHavingConstraint(FacetIncludingChildrenHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetIncludingChildren(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}

	@Override
	public FilterConstraint visitFacetIncludingChildrenExceptConstraint(FacetIncludingChildrenExceptConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetIncludingChildrenExcept(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}

	@Override
	public FilterConstraint visitReferenceHavingConstraint(EvitaQLParser.ReferenceHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.classifierWithFilterConstraintArgs() != null) {
					return QueryConstraints.referenceHaving(
						ctx.classifierWithFilterConstraintArgs().classifier.accept(this.stringValueTokenVisitor).asString(),
						visitChildConstraint(ctx.classifierWithFilterConstraintArgs().filter, FilterConstraint.class)
					);
				} else {
					return QueryConstraints.referenceHaving(
						ctx.classifierArgs().classifier.accept(this.stringValueTokenVisitor).asString()
					);
				}
			}
		);
	}



	@Override
	public FilterConstraint visitHierarchyWithinConstraint(EvitaQLParser.HierarchyWithinConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.hierarchyWithin(
				ctx.args.classifier
					.accept(this.stringValueTokenVisitor)
					.asString(),
				visitChildConstraint(ctx.args.ofParent, FilterConstraint.class),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, HierarchySpecificationFilterConstraint.class))
					.toArray(HierarchySpecificationFilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyWithinSelfConstraint(EvitaQLParser.HierarchyWithinSelfConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.hierarchyWithinSelf(
				visitChildConstraint(ctx.args.ofParent, FilterConstraint.class),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, HierarchySpecificationFilterConstraint.class))
					.toArray(HierarchySpecificationFilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyWithinRootConstraint(EvitaQLParser.HierarchyWithinRootConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.hierarchyWithinRoot(
				ctx.args.classifier
					.accept(this.stringValueTokenVisitor)
					.asString(),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, HierarchySpecificationFilterConstraint.class))
					.toArray(HierarchySpecificationFilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyWithinRootSelfConstraint(EvitaQLParser.HierarchyWithinRootSelfConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return QueryConstraints.hierarchyWithinRootSelf();
				}
				return QueryConstraints.hierarchyWithinRootSelf(
					ctx.args.constrains
						.stream()
						.map(c -> visitChildConstraint(c, HierarchySpecificationFilterConstraint.class))
						.toArray(HierarchySpecificationFilterConstraint[]::new)
				);
			}
		);
	}

	@Override
	public FilterConstraint visitHierarchyDirectRelationConstraint(EvitaQLParser.HierarchyDirectRelationConstraintContext ctx) {
		return parse(ctx, HierarchyDirectRelation::new);
	}

	@Override
	public FilterConstraint visitHierarchyHavingConstraint(EvitaQLParser.HierarchyHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.having(
				ctx.args.constraints
					.stream()
					.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyAnyHavingConstraint(HierarchyAnyHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.anyHaving(
				ctx.args.constraints
					.stream()
					.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyExcludingRootConstraint(EvitaQLParser.HierarchyExcludingRootConstraintContext ctx) {
		return parse(ctx, HierarchyExcludingRoot::new);
	}

	@Override
	public FilterConstraint visitHierarchyExcludingConstraint(EvitaQLParser.HierarchyExcludingConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.excluding(
				ctx.args.constraints
					.stream()
					.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitEntityHavingConstraint(EvitaQLParser.EntityHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.entityHaving(
				ctx.args.constraints.stream()
					.map(c -> visitChildConstraint(c, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitGroupHavingConstraint(GroupHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.groupHaving(
				ctx.args.constraints.stream()
					.map(c -> visitChildConstraint(c, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitEntityScopeConstraint(EntityScopeConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.scope(
				ctx.args.variadicValueTokens().accept(this.scopeValueTokenVisitor).asEnumArray(Scope.class)
			)
		);
	}

	@Override
	public FilterConstraint visitFilterInScopeConstraint(FilterInScopeConstraintContext ctx) {
		return parse(
			ctx,
			() -> QueryConstraints.inScope(
				ctx.args.scope.accept(this.scopeValueTokenVisitor).asEnum(Scope.class),
				ctx.args.filterConstraints
					.stream()
					.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}
}
