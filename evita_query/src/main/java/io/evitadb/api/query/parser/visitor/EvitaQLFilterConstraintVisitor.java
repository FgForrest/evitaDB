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
import io.evitadb.api.query.parser.grammar.EvitaQLParser.HierarchyAnyHavingConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.dataType.Scope;

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

	protected final EvitaQLValueTokenVisitor comparableValueTokenVisitor = EvitaQLValueTokenVisitor.withComparableTypesAllowed();
	protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);
	protected final EvitaQLValueTokenVisitor stringValueListTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		String.class,
		String[].class,
		Iterable.class
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
	protected final EvitaQLValueTokenVisitor inRangeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		byte.class,
		Byte.class,
		short.class,
		Short.class,
		int.class,
		Integer.class,
		long.class,
		Long.class,
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
	protected final EvitaQLValueTokenVisitor offsetDateTimeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(OffsetDateTime.class);
	protected final EvitaQLValueTokenVisitor localeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Locale.class);
	protected final EvitaQLValueTokenVisitor currencyValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class, Currency.class);
	protected final EvitaQLValueTokenVisitor attributeSpecialValueValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(AttributeSpecialValue.class);
	protected final EvitaQLValueTokenVisitor scopeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(Scope.class);

	@Override
	public FilterConstraint visitFilterByConstraint(EvitaQLParser.FilterByConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FilterBy(
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
			() -> new FilterGroupBy(
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
					return new And();
				}
				return new And(
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
					return new Or();
				}
				return new Or(
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
			() -> new Not(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}

	@Override
	public FilterConstraint visitUserFilterConstraint(EvitaQLParser.UserFilterConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new UserFilter();
				}
				return new UserFilter(
					ctx.args.constraints
						.stream()
						.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
						.toArray(FilterConstraint[]::new)
				);
			}
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsConstraint(EvitaQLParser.AttributeEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeGreaterThanConstraint(EvitaQLParser.AttributeGreaterThanConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeGreaterThan(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeGreaterThanEqualsConstraint(EvitaQLParser.AttributeGreaterThanEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeGreaterThanEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeLessThanConstraint(EvitaQLParser.AttributeLessThanConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeLessThan(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeLessThanEqualsConstraint(EvitaQLParser.AttributeLessThanEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeLessThanEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeBetweenConstraint(EvitaQLParser.AttributeBetweenConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeBetween(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.valueFrom
					.accept(this.comparableValueTokenVisitor)
					.asSerializableAndComparable(),
				ctx.args.valueTo
					.accept(this.comparableValueTokenVisitor)
					.asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeInSetConstraint(EvitaQLParser.AttributeInSetConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier.accept(this.stringValueTokenVisitor).asString();
				if (ctx.args.values == null) {
					return new AttributeInSet(classifier);
				}
				return new AttributeInSet(
					classifier,
					ctx.args.values
						.accept(this.comparableValueTokenVisitor)
						.asSerializableArray()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitAttributeContainsConstraint(EvitaQLParser.AttributeContainsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeContains(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeStartsWithConstraint(EvitaQLParser.AttributeStartsWithConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeStartsWith(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEndsWithConstraint(EvitaQLParser.AttributeEndsWithConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEndsWith(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.value.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsTrueConstraint(EvitaQLParser.AttributeEqualsTrueConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				Boolean.TRUE
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsFalseConstraint(EvitaQLParser.AttributeEqualsFalseConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEquals(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				Boolean.FALSE
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsConstraint(EvitaQLParser.AttributeIsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeIs(
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
			() -> new AttributeIs(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				AttributeSpecialValue.NULL
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsNotNullConstraint(EvitaQLParser.AttributeIsNotNullConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeIs(
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

				if (Number.class.isAssignableFrom(attributeValue.getType())) {
					return new AttributeInRange(attributeName, attributeValue.asNumber());
				} else if (OffsetDateTime.class.isAssignableFrom(attributeValue.getType())) {
					return new AttributeInRange(attributeName, attributeValue.asOffsetDateTime());
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
				return new AttributeInRange(attributeName);
			}
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyInSetConstraint(EvitaQLParser.EntityPrimaryKeyInSetConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new EntityPrimaryKeyInSet();
				}
				return new EntityPrimaryKeyInSet(
					ctx.args.values.accept(this.intValueTokenVisitor).asIntegerArray()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitEntityLocaleEqualsConstraint(EvitaQLParser.EntityLocaleEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityLocaleEquals(
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
			() -> new PriceInCurrency(
				ctx.args.value
					.accept(this.currencyValueTokenVisitor)
					.asCurrency()
			)
		);
	}

	@Override
	public FilterConstraint visitPriceInPriceListsConstraints(EvitaQLParser.PriceInPriceListsConstraintsContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new PriceInPriceLists();
				}
				return new PriceInPriceLists(
					ctx.args.classifiers.accept(this.stringValueListTokenVisitor).asStringArray()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitPriceValidInNowConstraint(EvitaQLParser.PriceValidInNowConstraintContext ctx) {
		return parse(ctx, PriceValidIn::new);
	}

	@Override
	public FilterConstraint visitPriceValidInConstraint(EvitaQLParser.PriceValidInConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				return new PriceValidIn(
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
			() -> new PriceBetween(
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
					return new FacetHaving(
						ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
						ctx.args.filter1.accept(this),
						ctx.args.filter2.accept(this)
					);
				} else {
					return new FacetHaving(
						ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
						ctx.args.filter1.accept(this)
					);
				}
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
					return new ReferenceHaving(
						ctx.classifierWithFilterConstraintArgs().classifier.accept(this.stringValueTokenVisitor).asString(),
						visitChildConstraint(ctx.classifierWithFilterConstraintArgs().filter, FilterConstraint.class)
					);
				} else {
					return new ReferenceHaving(
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
			() -> new HierarchyWithin(
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
			() -> new HierarchyWithin(
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
			() -> new HierarchyWithinRoot(
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
					return new HierarchyWithinRoot();
				}
				return new HierarchyWithinRoot(
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
			() -> new HierarchyHaving(
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
			() -> new HierarchyAnyHaving(
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
			() -> new HierarchyExcluding(
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
			() -> new EntityHaving(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}

	@Override
	public FilterConstraint visitEntityScopeConstraint(EntityScopeConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityScope(ctx.args.variadicValueTokens().accept(this.scopeValueTokenVisitor).asEnumArray(Scope.class))
		);
	}

	@Override
	public FilterConstraint visitFilterInScopeConstraint(FilterInScopeConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FilterInScope(
				ctx.args.scope.accept(this.scopeValueTokenVisitor).asEnum(Scope.class),
				ctx.args.filterConstraints
					.stream()
					.map(fc -> visitChildConstraint(fc, FilterConstraint.class))
					.toArray(FilterConstraint[]::new)
			)
		);
	}
}
