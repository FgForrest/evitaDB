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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.api.query.require.Require;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing top level query ({@link Query}).
 * This visitor is meant to be used when you need to parse whole query from string.
 *
 * @see EvitaQLConstraintVisitor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class EvitaQLQueryVisitor extends EvitaQLBaseVisitor<Query> {

    protected final EvitaQLConstraintVisitor constraintVisitor = new EvitaQLConstraintVisitor();


    @Override
    public Query visitQuery(@Nonnull EvitaQLParser.QueryContext ctx) {
        return parse(
            ctx,
            () -> {
                final List<Constraint<?>> constraints = ctx.args.constraints
                    .stream()
                    .map(con -> con.accept(constraintVisitor))
                    .collect(Collectors.toList());

                final Collection collectionConstraint = findEntitiesConstraint(ctx, constraints);
                final FilterBy filterByConstraint = findFilterByConstraint(ctx, constraints);
                final OrderBy orderByConstraint = findOrderByConstraint(ctx, constraints);
                final Require requireConstraint = findRequireConstraint(ctx, constraints);

                return Query.query(
                    collectionConstraint,
                    filterByConstraint,
                    orderByConstraint,
                    requireConstraint
                );
            }
        );
    }

    /**
     * Tries to find single {@link Collection} constraint in top level constraints
     *
     * @param topLevelConstraints top level constraints directly from query args
     * @return found {@link Collection}
     * @throws EvitaQLInvalidQueryError if no appropriated constraint found
     */
    protected Collection findEntitiesConstraint(@Nonnull ParserRuleContext ctx, @Nonnull List<Constraint<?>> topLevelConstraints) {
        final List<Constraint<?>> headConstraints = topLevelConstraints.stream()
                .filter(HeadConstraint.class::isInstance)
                .toList();

        if ((headConstraints.size() != 1) || !(headConstraints.get(0) instanceof Collection)) {
            throw new EvitaQLInvalidQueryError(
                ctx,
                "Query can have only one top level head constraint -> `collection`."
            );
        }
        return (Collection) headConstraints.get(0);
    }

    /**
     * Tries to find single {@link FilterBy} constraint in top level constraints
     *
     * @param topLevelConstraints top level constraints directly from query args
     * @return {@code null} if not appropriated constraint found or found {@link FilterBy}
     * @throws EvitaQLInvalidQueryError if there is more top level filter constraints or found constraint is not appropriated type
     */
    protected FilterBy findFilterByConstraint(@Nonnull ParserRuleContext ctx, @Nonnull List<Constraint<?>> topLevelConstraints) {
        final List<Constraint<?>> filterConstraints = topLevelConstraints.stream()
                .filter(FilterConstraint.class::isInstance)
                .toList();

        if (filterConstraints.isEmpty()) {
            return null;
        }
        if ((filterConstraints.size() > 1) || !(filterConstraints.get(0) instanceof FilterBy)) {
            throw new EvitaQLInvalidQueryError(
                ctx,
                "Query can have only one top level filter constraint -> \"filterBy\"."
            );
        }
        return (FilterBy) filterConstraints.get(0);
    }

    /**
     * Tries to find single {@link OrderBy} constraint in top level constraints
     *
     * @param topLevelConstraints top level constraints directly from query args
     * @return {@code null} if not appropriated constraint found or found {@link OrderBy}
     * @throws EvitaQLInvalidQueryError if there is more top level order constraints or found constraint is not appropriated type
     */
    protected OrderBy findOrderByConstraint(@Nonnull ParserRuleContext ctx, @Nonnull List<Constraint<?>> topLevelConstraints) {
        final List<Constraint<?>> orderConstraints = topLevelConstraints.stream()
                .filter(OrderConstraint.class::isInstance)
                .toList();

        if (orderConstraints.isEmpty()) {
            return null;
        }
        if ((orderConstraints.size() > 1) || !(orderConstraints.get(0) instanceof OrderBy)) {
            throw new EvitaQLInvalidQueryError(
                ctx,
                "Query can have only one top level order constraint -> \"orderBy\"."
            );
        }
        return (OrderBy) orderConstraints.get(0);
    }

    /**
     * Tries to find single {@link Require} constraint in top level constraints
     *
     * @param topLevelConstraints top level constraints directly from query args
     * @return {@code null} if not appropriated constraint found or found {@link Require}
     * @throws EvitaQLInvalidQueryError if there is more top level require constraints or found constraint is not appropriated type
     */
    protected Require findRequireConstraint(@Nonnull ParserRuleContext ctx, @Nonnull List<Constraint<?>> topLevelConstraints) {
        final List<Constraint<?>> requireConstraints = topLevelConstraints.stream()
                .filter(RequireConstraint.class::isInstance)
                .toList();

        if (requireConstraints.isEmpty()) {
            return null;
        }
        if ((requireConstraints.size() > 1) || !(requireConstraints.get(0) instanceof Require)) {
            throw new EvitaQLInvalidQueryError(
                ctx,
                "Query can have only one top level require constraint -> \"require\"."
            );
        }
        return (Require) requireConstraints.get(0);
    }
}
