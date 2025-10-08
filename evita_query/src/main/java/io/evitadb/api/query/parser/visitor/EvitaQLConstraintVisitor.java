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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;

/**
 * Implementation of {@link EvitaQLVisitor} which works as delegating visitor for
 * {@link Constraint} parsing. Constraints being parsed are delegated to visitor by it's type (filter, order,
 * require or head) of parsing context. Constraint visitors are separated by type only for better readability (it reflects
 * rules structure of grammar).
 *
 * @see EvitaQLHeadConstraintVisitor
 * @see EvitaQLFilterConstraintVisitor
 * @see EvitaQLOrderConstraintVisitor
 * @see EvitaQLRequireConstraintVisitor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class EvitaQLConstraintVisitor extends EvitaQLBaseConstraintVisitor<Constraint<?>> {

    protected final EvitaQLHeadConstraintVisitor headConstraintVisitor = new EvitaQLHeadConstraintVisitor();
    protected final EvitaQLFilterConstraintVisitor filterConstraintVisitor = new EvitaQLFilterConstraintVisitor();
    protected final EvitaQLOrderConstraintVisitor orderConstraintVisitor = new EvitaQLOrderConstraintVisitor();
    protected final EvitaQLRequireConstraintVisitor requireConstraintVisitor = new EvitaQLRequireConstraintVisitor();


    @Override
    public Constraint<?> visitConstraint(EvitaQLParser.ConstraintContext ctx) {
        final EvitaQLParser.HeadConstraintContext headConstraintContext = ctx.headConstraint();
        if (headConstraintContext != null) {
            return headConstraintContext.accept(this.headConstraintVisitor);
        }

        final EvitaQLParser.FilterConstraintContext filterConstraintContext = ctx.filterConstraint();
        if (filterConstraintContext != null) {
            return filterConstraintContext.accept(this.filterConstraintVisitor);
        }

        final EvitaQLParser.OrderConstraintContext orderConstraintContext = ctx.orderConstraint();
        if (orderConstraintContext != null) {
            return orderConstraintContext.accept(this.orderConstraintVisitor);
        }

        final EvitaQLParser.RequireConstraintContext requireConstraintContext = ctx.requireConstraint();
        if (requireConstraintContext != null) {
            return requireConstraintContext.accept(this.requireConstraintVisitor);
        }

        throw new EvitaSyntaxException(ctx, "No supported constraint found.");
    }
}
