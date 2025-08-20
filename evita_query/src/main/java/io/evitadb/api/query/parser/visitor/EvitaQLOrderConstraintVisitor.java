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
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.parser.EnumWrapper;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.*;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all order type constraints
 * ({@link OrderConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 * @see EvitaQLConstraintVisitor
 */
public class EvitaQLOrderConstraintVisitor extends EvitaQLBaseConstraintVisitor<OrderConstraint> {

	protected final EvitaQLFilterConstraintVisitor filterConstraintVisitor = new EvitaQLFilterConstraintVisitor();
	protected final EvitaQLValueTokenVisitor comparableValueTokenVisitor = EvitaQLValueTokenVisitor.withComparableTypesAllowed();
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
	protected final EvitaQLValueTokenVisitor orderDirectionValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(OrderDirection.class);
	protected final EvitaQLValueTokenVisitor traversalModeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(TraversalMode.class);
	protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);
	protected final EvitaQLValueTokenVisitor orderDirectionOrStringTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(
		OrderDirection.class,
		String.class,
		String[].class,
		Iterable.class
	);
	protected final EvitaQLValueTokenVisitor scopeValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(Scope.class);

	@Override
	public OrderConstraint visitOrderByConstraint(EvitaQLParser.OrderByConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new OrderBy();
				}
				return new OrderBy(
					ctx.args.constraints
						.stream()
						.map(oc -> visitChildConstraint(oc, OrderConstraint.class))
						.toArray(OrderConstraint[]::new)
				);
			}
		);
	}

	@Override
	public OrderConstraint visitOrderGroupByConstraint(EvitaQLParser.OrderGroupByConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new OrderGroupBy();
				}
				return new OrderGroupBy(
					ctx.args.constraints
						.stream()
						.map(oc -> visitChildConstraint(oc, OrderConstraint.class))
						.toArray(OrderConstraint[]::new)
				);
			}
		);
	}

	@Override
	public OrderConstraint visitAttributeNaturalConstraint(EvitaQLParser.AttributeNaturalConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String attributeName = ctx.args.classifier.accept(this.stringValueTokenVisitor).asString();
				if (ctx.args.value == null) {
					return new AttributeNatural(attributeName);
				} else {
					return new AttributeNatural(
						attributeName,
						ctx.args.value
							.accept(this.orderDirectionValueTokenVisitor)
							.asEnum(OrderDirection.class)
					);
				}
			}
		);
	}

	@Override
	public OrderConstraint visitAttributeSetExactConstraint(AttributeSetExactConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeSetExact(
				ctx.args.attributeName.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.attributeValues.accept(this.comparableValueTokenVisitor).asSerializableArray()
			)
		);
	}

	@Override
	public OrderConstraint visitAttributeSetInFilterConstraint(AttributeSetInFilterConstraintContext ctx) {
		return parse(
			ctx,
			() -> new AttributeSetInFilter(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString()
			)
		);
	}

	@Override
	public OrderConstraint visitPriceNaturalConstraint(EvitaQLParser.PriceNaturalConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new PriceNatural();
				} else {
					return new PriceNatural(
						ctx.args.value
							.accept(this.orderDirectionValueTokenVisitor)
							.asEnum(OrderDirection.class)
					);
				}
			}
		);
	}

	@Override
	public OrderConstraint visitPriceDiscountConstraint(PriceDiscountConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final LinkedList<Serializable> settings = Arrays.stream(ctx.args.values
						.accept(this.orderDirectionOrStringTokenVisitor)
						.asSerializableArray())
					.collect(Collectors.toCollection(LinkedList::new));

				// due to the varargs of any value in QL, we don't know which enum is where, on top of that it can be
				// enum directly or just wrapper
				final Serializable firstSettings = settings.peekFirst();
				if (firstSettings instanceof OrderDirection) {
					return new PriceDiscount(
						castArgument(ctx, settings.pop(), OrderDirection.class),
						settings.stream()
							.map(it -> castArgument(ctx, it, String.class))
							.toArray(String[]::new)
					);
				}
				if (firstSettings instanceof EnumWrapper enumWrapper && enumWrapper.canBeMappedTo(OrderDirection.class)) {
					return new PriceDiscount(
						castArgument(ctx, settings.pop(), EnumWrapper.class)
							.toEnum(OrderDirection.class),
						settings.stream()
							.map(it -> castArgument(ctx, it, String.class))
							.toArray(String[]::new)
					);
				}
				return new PriceDiscount(
					OrderDirection.DESC,
					settings.stream()
						.map(it -> castArgument(ctx, it, String.class))
						.toArray(String[]::new)
				);
			}
		);
	}

	@Override
	public OrderConstraint visitRandomConstraint(EvitaQLParser.RandomConstraintContext ctx) {
		return parse(ctx, () -> Random.INSTANCE);
	}

	@Override
	public OrderConstraint visitRandomWithSeedConstraint(RandomWithSeedConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Random(ctx.args.value.accept(this.intValueTokenVisitor).asLong())
		);
	}

	@Override
	public OrderConstraint visitReferencePropertyConstraint(EvitaQLParser.ReferencePropertyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new ReferenceProperty(
				ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, OrderConstraint.class))
					.toArray(OrderConstraint[]::new)
			)
		);
	}

	@Override
	public OrderConstraint visitTraverseByEntityPropertyConstraint(TraverseByEntityPropertyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new TraverseByEntityProperty(
				ctx.args.traversalMode == null ?
					null : ctx.args.traversalMode.accept(this.traversalModeValueTokenVisitor).asEnum(TraversalMode.class),
				ctx.args.constraints == null || ctx.args.constraints.isEmpty() ?
					new OrderConstraint[] { new EntityPrimaryKeyNatural(OrderDirection.ASC) } :
					ctx.args.constraints
						.stream()
						.map(c -> visitChildConstraint(c, OrderConstraint.class))
						.toArray(OrderConstraint[]::new)
			)
		);
	}

	@Override
	public OrderConstraint visitPickFirstByByEntityPropertyConstraint(PickFirstByByEntityPropertyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new PickFirstByEntityProperty(
				ctx.args.constraints
					.stream()
					.map(c -> visitChildConstraint(c, OrderConstraint.class))
					.toArray(OrderConstraint[]::new)
			)
		);
	}

	@Nullable
	@Override
	public OrderConstraint visitEntityPrimaryKeyExactNatural(EntityPrimaryKeyExactNaturalContext ctx) {
		return parse(
			ctx,
			() -> new EntityPrimaryKeyNatural(
				Optional.ofNullable(ctx.args)
					.map(ValueArgsContext::valueToken)
					.map(it -> it.accept(this.orderDirectionValueTokenVisitor).asEnum(OrderDirection.class))
					.orElse(OrderDirection.ASC)
			)
		);
	}

	@Override
	public OrderConstraint visitEntityPrimaryKeyExactConstraint(EntityPrimaryKeyExactConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityPrimaryKeyExact(
				ctx.args.values.accept(this.intValueTokenVisitor).asIntegerArray()
			)
		);
	}

	@Override
	public OrderConstraint visitEntityPrimaryKeyInFilterConstraint(EntityPrimaryKeyInFilterConstraintContext ctx) {
		return parse(ctx, EntityPrimaryKeyInFilter::new);
	}

	@Override
	public OrderConstraint visitEntityPropertyConstraint(EntityPropertyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityProperty(
				ctx.args.constraints
					.stream()
					.map(c -> visitChildConstraint(c, OrderConstraint.class))
					.toArray(OrderConstraint[]::new)
			)
		);
	}

	@Override
	public OrderConstraint visitEntityGroupPropertyConstraint(EntityGroupPropertyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new EntityGroupProperty(
				ctx.args.constraints
					.stream()
					.map(c -> visitChildConstraint(c, OrderConstraint.class))
					.toArray(OrderConstraint[]::new)
			)
		);
	}

	@Override
	public OrderConstraint visitSegmentsConstraint(SegmentsConstraintContext ctx) {
		return parse(
			ctx,
			() -> new Segments(
				ctx.args.constraints.stream()
					.map(it -> it.accept(this))
					.map(it -> {
						if (it instanceof Segment theSegment) {
							return theSegment;
						} else {
							throw new EvitaSyntaxException(ctx, "Only `segment` is accepted as parameter of `segments` order constraint!");
						}
					})
					.toArray(Segment[]::new)
			)
		);
	}

	@Override
	public OrderConstraint visitSegmentLimitConstraint(SegmentLimitConstraintContext ctx) {
		return parse(
			ctx,
			() -> new SegmentLimit(
				ctx.args.valueToken().accept(this.intValueTokenVisitor).asInt()
			)
		);
	}

	@Override
	public OrderConstraint visitSegmentConstraint(SegmentConstraintContext ctx) {
		final FilterConstraint filterConstraint = ctx.args.entityHaving == null ?
			null : ctx.args.filterConstraint().accept(this.filterConstraintVisitor);
		Assert.isTrue(
			filterConstraint == null || filterConstraint instanceof EntityHaving,
			"Only `entityHaving` is accepted as first parameter of `segment` order constraint!"
		);

		final OrderConstraint orderByConstraint = ctx.args.orderBy.accept(this);
		Assert.isTrue(
			orderByConstraint instanceof OrderBy,
			"Only `orderBy` is accepted as " + (filterConstraint == null ? "first" : "second") + " parameter of `segment` order constraint!"
		);

		final OrderConstraint limitConstraint = ctx.args.limit == null ?
			null : ctx.args.limit.accept(this);
		Assert.isTrue(
			limitConstraint == null || limitConstraint instanceof SegmentLimit,
			"Only `limit` is accepted as " + (filterConstraint == null ? "second" : "third") + " parameter of `segment` order constraint!"
		);

		return parse(
			ctx,
			() -> new Segment(
				(EntityHaving) filterConstraint,
				(OrderBy) orderByConstraint,
				(SegmentLimit) limitConstraint
			)
		);
	}

	@Override
	public OrderConstraint visitOrderInScopeConstraint(OrderInScopeConstraintContext ctx) {
		return parse(
			ctx,
			() -> new OrderInScope(
				ctx.args.scope.accept(this.scopeValueTokenVisitor).asEnum(Scope.class),
				ctx.args.orderConstraints
					.stream()
					.map(fc -> visitChildConstraint(fc, OrderConstraint.class))
					.toArray(OrderConstraint[]::new)
			)
		);
	}

}
