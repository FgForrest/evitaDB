/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.order.Random;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.EntityPropertyConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all order type constraints
 * ({@link OrderConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @see EvitaQLConstraintVisitor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class EvitaQLOrderConstraintVisitor extends EvitaQLBaseConstraintVisitor<OrderConstraint> {

	protected final EvitaQLClassifierTokenVisitor classifierTokenVisitor = new EvitaQLClassifierTokenVisitor();
	protected final EvitaQLValueTokenVisitor orderDirectionValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(OrderDirection.class);


	@Override
	public OrderConstraint visitOrderByConstraint(@Nonnull EvitaQLParser.OrderByConstraintContext ctx) {
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
	public OrderConstraint visitAttributeNaturalConstraint(@Nonnull EvitaQLParser.AttributeNaturalConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				final String attributeName = ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier();
				if (ctx.args.value == null) {
					return new AttributeNatural(attributeName);
				} else {
					return new AttributeNatural(
						attributeName,
						ctx.args.value
							.accept(orderDirectionValueTokenVisitor)
							.asEnum(OrderDirection.class)
					);
				}
			}
		);
	}

	@Override
	public OrderConstraint visitPriceNaturalConstraint(@Nonnull EvitaQLParser.PriceNaturalConstraintContext ctx) {
		return parse(
			ctx,
			() -> {
				if (ctx.args == null) {
					return new PriceNatural();
				} else {
					return new PriceNatural(
						ctx.args.value
							.accept(orderDirectionValueTokenVisitor)
							.asEnum(OrderDirection.class)
					);
				}
			}
		);
	}

	@Override
	public OrderConstraint visitRandomConstraint(@Nonnull EvitaQLParser.RandomConstraintContext ctx) {
		return parse(ctx, Random::new);
	}

	@Override
	public OrderConstraint visitReferencePropertyConstraint(EvitaQLParser.ReferencePropertyConstraintContext ctx) {
		return parse(
			ctx,
			() -> new ReferenceProperty(
				ctx.args.classifier.accept(classifierTokenVisitor).asSingleClassifier(),
				ctx.args.constrains
					.stream()
					.map(c -> visitChildConstraint(c, OrderConstraint.class))
					.toArray(OrderConstraint[]::new)
			)
		);
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
}
