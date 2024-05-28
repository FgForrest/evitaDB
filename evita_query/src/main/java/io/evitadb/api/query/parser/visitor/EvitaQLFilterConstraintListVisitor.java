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
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Implementation of {@link EvitaQLVisitor} which works as delegating visitor for
 * {@link FilterConstraint} list parsing. Parsing of individual constraints is delegated to {@link EvitaQLFilterConstraintVisitor}.

 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class EvitaQLFilterConstraintListVisitor extends EvitaQLBaseVisitor<List<FilterConstraint>> {

    protected final EvitaQLFilterConstraintVisitor filterConstraintVisitor = new EvitaQLFilterConstraintVisitor();

    @Override
    public List<FilterConstraint> visitFilterConstraintList(@Nonnull EvitaQLParser.FilterConstraintListContext ctx) {
        return parse(
            ctx,
            () -> ctx.constraints
                .stream()
                .map(c -> c.accept(filterConstraintVisitor))
                .toList()
        );
    }
}
