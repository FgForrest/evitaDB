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

import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.HeadContainerConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLParser.LabelConstraintContext;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;

/**
 * Implementation of {@link EvitaQLVisitor} for parsing all head type constraints
 * ({@link HeadConstraint}).
 * This visitor should not be used directly if not needed instead use generic {@link EvitaQLConstraintVisitor}.
 *
 * @see EvitaQLConstraintVisitor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class EvitaQLHeadConstraintVisitor extends EvitaQLBaseConstraintVisitor<HeadConstraint> {

    protected final EvitaQLValueTokenVisitor comparableValueTokenVisitor = EvitaQLValueTokenVisitor.withComparableTypesAllowed();
    protected final EvitaQLValueTokenVisitor stringValueTokenVisitor = EvitaQLValueTokenVisitor.withAllowedTypes(String.class);

    @Override
    public HeadConstraint visitCollectionConstraint(EvitaQLParser.CollectionConstraintContext ctx) {
        return parse(
            ctx,
            () -> new Collection(
                ctx.args.classifier.accept(this.stringValueTokenVisitor).asString()
            )
        );
    }

    @Override
    public HeadConstraint visitHeadContainerConstraint(HeadContainerConstraintContext ctx) {
        return parse(
            ctx,
            () -> {
                if (ctx.args == null) {
                    return new Head();
                }
                return new Head(
                    ctx.args.headConstraint()
                        .stream()
                        .map(hc -> visitChildConstraint(hc, HeadConstraint.class))
                        .toArray(HeadConstraint[]::new)
                );
            }
        );
    }

    @Override
    public HeadConstraint visitLabelConstraint(LabelConstraintContext ctx) {
        return parse(
            ctx,
            () -> new Label(
                ctx.args.classifier.accept(this.stringValueTokenVisitor).asString(),
                ctx.args.value
                    .accept(this.comparableValueTokenVisitor)
                    .asSerializable()
            )
        );
    }
}
