/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.AttributeInRangeNowConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;

import javax.annotation.Nonnull;
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

	protected final EvitaQLClassifierTokenVisitor classifierTokenVisitor = new EvitaQLClassifierTokenVisitor();
	protected final EvitaQLValueTokenVisitor comparableValueTokenVisitor = EvitaQLValueTokenVisitor.withComparableTypesAllowed();
	protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);
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


	@Override
	public FilterConstraint visitFilterByConstraint(@Nonnull EvitaQLParser.FilterByConstraintContext ctx) {
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
	public FilterConstraint visitFilterGroupByConstraint(@Nonnull EvitaQLParser.FilterGroupByConstraintContext ctx) {
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
	public FilterConstraint visitAndConstraint(@Nonnull EvitaQLParser.AndConstraintContext ctx) {
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
	public FilterConstraint visitOrConstraint(@Nonnull EvitaQLParser.OrConstraintContext ctx) {
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
	public FilterConstraint visitNotConstraint(@Nonnull EvitaQLParser.NotConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Not(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}

	@Override
	public FilterConstraint visitUserFilterConstraint(@Nonnull EvitaQLParser.UserFilterConstraintContext ctx) {
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
	public FilterConstraint visitAttributeEqualsConstraint(@Nonnull EvitaQLParser.AttributeEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEquals(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeGreaterThanConstraint(@Nonnull EvitaQLParser.AttributeGreaterThanConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeGreaterThan(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeGreaterThanEqualsConstraint(@Nonnull EvitaQLParser.AttributeGreaterThanEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeGreaterThanEquals(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeLessThanConstraint(@Nonnull EvitaQLParser.AttributeLessThanConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeLessThan(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeLessThanEqualsConstraint(@Nonnull EvitaQLParser.AttributeLessThanEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeLessThanEquals(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(comparableValueTokenVisitor).asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeBetweenConstraint(@Nonnull EvitaQLParser.AttributeBetweenConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeBetween(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.valueFrom
					.accept(comparableValueTokenVisitor)
					.asSerializableAndComparable(),
				ctx.args.valueTo
					.accept(comparableValueTokenVisitor)
					.asSerializableAndComparable()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeInSetConstraint(@Nonnull EvitaQLParser.AttributeInSetConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String classifier = ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier();
				if (ctx.args.values == null) {
					return new AttributeInSet(classifier);
				}
				return new AttributeInSet(
					classifier,
					ctx.args.values
						.accept(comparableValueTokenVisitor)
						.asSerializableArray()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitAttributeContainsConstraint(@Nonnull EvitaQLParser.AttributeContainsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeContains(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeStartsWithConstraint(@Nonnull EvitaQLParser.AttributeStartsWithConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeStartsWith(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEndsWithConstraint(@Nonnull EvitaQLParser.AttributeEndsWithConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEndsWith(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value.accept(stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsTrueConstraint(@Nonnull EvitaQLParser.AttributeEqualsTrueConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEquals(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				Boolean.TRUE
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeEqualsFalseConstraint(@Nonnull EvitaQLParser.AttributeEqualsFalseConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeEquals(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				Boolean.FALSE
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsConstraint(@Nonnull EvitaQLParser.AttributeIsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeIs(
				ctx.args.classifier
					.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.value
					.accept(attributeSpecialValueValueTokenVisitor)
					.asEnum(AttributeSpecialValue.class)
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsNullConstraint(@Nonnull EvitaQLParser.AttributeIsNullConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeIs(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				AttributeSpecialValue.NULL
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeIsNotNullConstraint(@Nonnull EvitaQLParser.AttributeIsNotNullConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeIs(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				AttributeSpecialValue.NOT_NULL
			)
		);
	}

	@Override
	public FilterConstraint visitAttributeInRangeConstraint(@Nonnull EvitaQLParser.AttributeInRangeConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String attributeName = ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier();
				final Value attributeValue = ctx.args.value.accept(inRangeValueTokenVisitor);

				if (Number.class.isAssignableFrom(attributeValue.getType())) {
					return new AttributeInRange(attributeName, attributeValue.asNumber());
				} else if (OffsetDateTime.class.isAssignableFrom(attributeValue.getType())) {
					return new AttributeInRange(attributeName, attributeValue.asOffsetDateTime());
				} else {
					throw new EvitaQLInvalidQueryError(
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
				final String attributeName = ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier();
				return new AttributeInRange(attributeName);
			}
		);
	}

	@Override
	public FilterConstraint visitEntityPrimaryKeyInSetConstraint(@Nonnull EvitaQLParser.EntityPrimaryKeyInSetConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new EntityPrimaryKeyInSet();
				}
				return new EntityPrimaryKeyInSet(
					ctx.args.values.accept(intValueTokenVisitor).asIntegerArray()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitEntityLocaleEqualsConstraint(@Nonnull EvitaQLParser.EntityLocaleEqualsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityLocaleEquals(
				ctx.args.value
					.accept(localeValueTokenVisitor)
					.asLocale()
			)
		);
	}

	@Override
	public FilterConstraint visitPriceInCurrencyConstraint(@Nonnull EvitaQLParser.PriceInCurrencyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceInCurrency(
				ctx.args.value
					.accept(currencyValueTokenVisitor)
					.asCurrency()
			)
		);
	}

	@Override
	public FilterConstraint visitPriceInPriceListsConstraints(@Nonnull EvitaQLParser.PriceInPriceListsConstraintsContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new PriceInPriceLists();
				}
				return new PriceInPriceLists(
					ctx.args.classifiers.accept(classifierTokenVisitor).asClassifierArray()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitPriceValidInNowConstraint(@Nonnull EvitaQLParser.PriceValidInNowConstraintContext ctx) {
		return parse(ctx, PriceValidIn::new);
	}

	@Override
	public FilterConstraint visitPriceValidInConstraint(@Nonnull EvitaQLParser.PriceValidInConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				return new PriceValidIn(
					ctx.args.value
						.accept(offsetDateTimeValueTokenVisitor)
						.asOffsetDateTime()
				);
			}
		);
	}

	@Override
	public FilterConstraint visitPriceBetweenConstraint(@Nonnull EvitaQLParser.PriceBetweenConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PriceBetween(
				ctx.args.valueFrom.accept(priceBetweenArgValueTokenVisitor).asNumber(BigDecimal.class),
				ctx.args.valueTo.accept(priceBetweenArgValueTokenVisitor).asNumber(BigDecimal.class)
			)
		);
	}

	@Override
	public FilterConstraint visitFacetHavingConstraint(@Nonnull EvitaQLParser.FacetHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> new FacetHaving(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.filterConstraint().accept(this)
			)
		);
	}

	@Override
	public FilterConstraint visitReferenceHavingConstraint(@Nonnull EvitaQLParser.ReferenceHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.classifierWithFilterConstraintArgs() != null) {
					return new ReferenceHaving(
						ctx.classifierWithFilterConstraintArgs().classifier.accept(classifierTokenVisitor).asSingleClassifier(),
						visitChildConstraint(ctx.classifierWithFilterConstraintArgs().filter, FilterConstraint.class)
					);
				} else {
					return new ReferenceHaving(
						ctx.classifierArgs().classifier.accept(classifierTokenVisitor).asSingleClassifier()
					);
				}
			}
		);
	}



	@Override
	public FilterConstraint visitHierarchyWithinConstraint(@Nonnull EvitaQLParser.HierarchyWithinConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyWithin(
				ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier(),
				visitChildConstraint(ctx.args.ofParent, FilterConstraint.class),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, HierarchySpecificationFilterConstraint.class))
					.toArray(HierarchySpecificationFilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyWithinSelfConstraint(@Nonnull EvitaQLParser.HierarchyWithinSelfConstraintContext ctx) {
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
	public FilterConstraint visitHierarchyWithinRootConstraint(@Nonnull EvitaQLParser.HierarchyWithinRootConstraintContext ctx) {
		return parse(
			ctx,
			() -> new HierarchyWithinRoot(
				ctx.args.classifier
					.accept(classifierTokenVisitor)
					.asSingleClassifier(),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, HierarchySpecificationFilterConstraint.class))
					.toArray(HierarchySpecificationFilterConstraint[]::new)
			)
		);
	}

	@Override
	public FilterConstraint visitHierarchyWithinRootSelfConstraint(@Nonnull EvitaQLParser.HierarchyWithinRootSelfConstraintContext ctx) {
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
	public FilterConstraint visitHierarchyDirectRelationConstraint(@Nonnull EvitaQLParser.HierarchyDirectRelationConstraintContext ctx) {
		return parse(ctx, HierarchyDirectRelation::new);
	}

	@Override
	public FilterConstraint visitHierarchyHavingConstraint(@Nonnull EvitaQLParser.HierarchyHavingConstraintContext ctx) {
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
	public FilterConstraint visitHierarchyExcludingRootConstraint(@Nonnull EvitaQLParser.HierarchyExcludingRootConstraintContext ctx) {
		return parse(ctx, HierarchyExcludingRoot::new);
	}

	@Override
	public FilterConstraint visitHierarchyExcludingConstraint(@Nonnull EvitaQLParser.HierarchyExcludingConstraintContext ctx) {
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
	public FilterConstraint visitEntityHavingConstraint(@Nonnull EvitaQLParser.EntityHavingConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityHaving(visitChildConstraint(ctx.args.filter, FilterConstraint.class))
		);
	}
}
