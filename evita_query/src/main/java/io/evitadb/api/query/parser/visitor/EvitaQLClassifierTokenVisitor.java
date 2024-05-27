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

import io.evitadb.api.query.parser.Classifier;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.utils.Assert;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.List;

/**
 * <p>Implementation of {@link EvitaQLVisitor} for parsing classifier literals, parameters and their variadic variants.</p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class EvitaQLClassifierTokenVisitor extends EvitaQLBaseVisitor<Classifier> {

    protected final EvitaQLParameterVisitor parameterVisitor = new EvitaQLParameterVisitor();

    @Override
    public Classifier visitPositionalParameterVariadicClassifierTokens(@Nonnull EvitaQLParser.PositionalParameterVariadicClassifierTokensContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.positionalParameter().accept(parameterVisitor);
                return parseVariadicArguments(ctx, argument);
            }
        );
    }

    @Override
    public Classifier visitNamedParameterVariadicClassifierTokens(@Nonnull EvitaQLParser.NamedParameterVariadicClassifierTokensContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.namedParameter().accept(parameterVisitor);
                return parseVariadicArguments(ctx, argument);
            }
        );
    }

    @Override
    public Classifier visitExplicitVariadicClassifierTokens(@Nonnull EvitaQLParser.ExplicitVariadicClassifierTokensContext ctx) {
        return parse(
            ctx,
            () -> new Classifier(
                ctx.classifierTokens
                    .stream()
                    .map(vt -> vt.accept(this).asSingleClassifier())
                    .toList()
            )
        );
    }

    @Override
    public Classifier visitPositionalParameterClassifierToken(@Nonnull EvitaQLParser.PositionalParameterClassifierTokenContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.positionalParameter().accept(parameterVisitor);
                assertClassifierIsString(ctx, argument.getClass());
                return new Classifier((String) argument);
            }
        );
    }

    @Override
    public Classifier visitNamedParameterClassifierToken(@Nonnull EvitaQLParser.NamedParameterClassifierTokenContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.namedParameter().accept(parameterVisitor);
                assertClassifierIsString(ctx, argument.getClass());
                return new Classifier((String) argument);
            }
        );
    }

    @Override
    public Classifier visitStringClassifierToken(@Nonnull EvitaQLParser.StringClassifierTokenContext ctx) {
        return parse(
            ctx,
            () -> new Classifier(
                ctx.getText().substring(1, ctx.getText().length() - 1)
            )
        );
    }


    protected static void assertClassifierIsString(@Nonnull ParserRuleContext ctx, @Nonnull Class<?> argumentType) {
        Assert.isTrue(
            argumentType.equals(String.class),
            () -> new EvitaQLInvalidQueryError(ctx, "Classifier argument must be of type `String`")
        );
    }

    /**
     * Parses list of arguments from client or creates new list from single argument because only list expected.
     * Supports iterables, arrays and single values.
     */
    @Nonnull
    protected Classifier parseVariadicArguments(@Nonnull ParserRuleContext ctx, @Nonnull Object argument) {
        if (argument instanceof final Iterable<?> iterableArgument) {
            final Iterator<?> iterator = iterableArgument.iterator();
            if (iterator.hasNext()) {
                assertClassifierIsString(ctx, iterator.next().getClass());
            }
            //noinspection unchecked
            return new Classifier((Iterable<String>) argument);
        } else if (argument.getClass().isArray()) {
            assertClassifierIsString(ctx, argument.getClass().getComponentType());
            return new Classifier((String[]) argument);
        } else {
            assertClassifierIsString(ctx, argument.getClass());
            return new Classifier(List.of((String) argument));
        }
    }
}
