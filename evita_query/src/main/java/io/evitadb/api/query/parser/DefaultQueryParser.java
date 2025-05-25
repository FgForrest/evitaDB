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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryParser;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.visitor.EvitaQLFilterConstraintListVisitor;
import io.evitadb.api.query.parser.visitor.EvitaQLHeadConstraintListVisitor;
import io.evitadb.api.query.parser.visitor.EvitaQLOrderConstraintListVisitor;
import io.evitadb.api.query.parser.visitor.EvitaQLQueryVisitor;
import io.evitadb.api.query.parser.visitor.EvitaQLRequireConstraintListVisitor;
import io.evitadb.api.query.parser.visitor.EvitaQLValueTokenVisitor;
import org.antlr.v4.runtime.BailErrorStrategy;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link QueryParser} using ANTLR4 parser and lexer.
 *
 * <b>Note: </b>the generated ANTLR4 parser is set to not to recover from syntax errors using {@link BailErrorStrategy}
 * so an exception is immediately thrown.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class DefaultQueryParser implements QueryParser {
    private static final DefaultQueryParser INSTANCE = new DefaultQueryParser();

    private final EvitaQLQueryVisitor queryVisitor = new EvitaQLQueryVisitor();
    private final EvitaQLHeadConstraintListVisitor headConstraintListVisitor = new EvitaQLHeadConstraintListVisitor();
    private final EvitaQLFilterConstraintListVisitor filterConstraintListVisitor = new EvitaQLFilterConstraintListVisitor();
    private final EvitaQLOrderConstraintListVisitor orderConstraintListVisitor = new EvitaQLOrderConstraintListVisitor();
    private final EvitaQLRequireConstraintListVisitor requireConstraintListVisitor = new EvitaQLRequireConstraintListVisitor();
    private final EvitaQLValueTokenVisitor valueTokenVisitor = EvitaQLValueTokenVisitor.withAllDataTypesAllowed();

    /**
     * @return thread safe instance of this class
     */
    public static DefaultQueryParser getInstance() {
        return INSTANCE;
    }


    @Nonnull
    @Override
    public Query parseQuery(@Nonnull String query, @Nonnull Object... positionalArguments) {
        return parseQuery(query, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public Query parseQuery(@Nonnull String query, @Nonnull List<Object> positionalArguments) {
        return parseQuery(query, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public Query parseQuery(@Nonnull String query, @Nonnull Map<String, Object> namedArguments) {
        return parseQuery(query, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public Query parseQuery(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseQuery(query, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public Query parseQuery(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseQuery(query, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Object... positionalArguments) {
        return parseHeadConstraintList(headConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseHeadConstraintList(headConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseHeadConstraintList(headConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseHeadConstraintList(headConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseHeadConstraintList(headConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Object... positionalArguments) {
        return parseFilterConstraintList(filterConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseFilterConstraintList(filterConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseFilterConstraintList(filterConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseFilterConstraintList(filterConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseFilterConstraintList(filterConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Object... positionalArguments) {
        return parseOrderConstraintList(orderConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseOrderConstraintList(orderConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseOrderConstraintList(orderConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseOrderConstraintList(orderConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseOrderConstraintList(orderConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Object... positionalArguments) {
        return parseRequireConstraintList(requireConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseRequireConstraintList(requireConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseRequireConstraintList(requireConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseRequireConstraintList(requireConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseRequireConstraintList(requireConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public <T extends Serializable> T parseValue(@Nonnull String value) {
        return parseValue(value, new ParseContext());
    }

    @Nonnull
    @Override
    public <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull Object positionalArgument) {
        return parseValue(value, new ParseContext(positionalArgument));
    }

    @Nonnull
    @Override
    public <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull Map<String, Object> namedArguments) {
        return parseValue(value, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull Map<String, Object> namedArguments, @Nonnull Object positionalArgument) {
        return parseValue(value, new ParseContext(namedArguments, positionalArgument));
    }


    @Nonnull
    @Override
    public Query parseQueryUnsafe(@Nonnull String query) {
        return parseQueryUnsafe(query, new ParseContext());
    }

    @Nonnull
    @Override
    public Query parseQueryUnsafe(@Nonnull String query, @Nonnull Object... positionalArguments) {
        return parseQueryUnsafe(query, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public Query parseQueryUnsafe(@Nonnull String query, @Nonnull List<Object> positionalArguments) {
        return parseQueryUnsafe(query, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public Query parseQueryUnsafe(@Nonnull String query, @Nonnull Map<String, Object> namedArguments) {
        return parseQueryUnsafe(query, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public Query parseQueryUnsafe(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseQueryUnsafe(query, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public Query parseQueryUnsafe(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseQueryUnsafe(query, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Object... positionalArguments) {
        return parseHeadConstraintListUnsafe(headConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseHeadConstraintListUnsafe(headConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseHeadConstraintListUnsafe(headConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseHeadConstraintListUnsafe(headConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseHeadConstraintListUnsafe(headConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Object... positionalArguments) {
        return parseFilterConstraintListUnsafe(filterConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseFilterConstraintListUnsafe(filterConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseFilterConstraintListUnsafe(filterConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseFilterConstraintListUnsafe(filterConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseFilterConstraintListUnsafe(filterConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Object... positionalArguments) {
        return parseOrderConstraintListUnsafe(orderConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseOrderConstraintListUnsafe(orderConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseOrderConstraintListUnsafe(orderConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseOrderConstraintListUnsafe(orderConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseOrderConstraintListUnsafe(orderConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Object... positionalArguments) {
        return parseRequireConstraintListUnsafe(requireConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull List<Object> positionalArguments) {
        return parseRequireConstraintListUnsafe(requireConstraintList, new ParseContext(positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments) {
        return parseRequireConstraintListUnsafe(requireConstraintList, new ParseContext(namedArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return parseRequireConstraintListUnsafe(requireConstraintList, new ParseContext(namedArguments, positionalArguments));
    }

    @Nonnull
    @Override
    public List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
        return parseRequireConstraintListUnsafe(requireConstraintList, new ParseContext(namedArguments, positionalArguments));
    }


    @Nonnull
    private Query parseQuery(@Nonnull String query, @Nonnull ParseContext context) {
        final EvitaQLParser parser = ParserFactory.getParser(query);
        return ParserExecutor.execute(
            context,
            () -> parser.queryUnit().query().accept(this.queryVisitor)
        );
    }

    @Nonnull
    public List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull ParseContext context) {
        final EvitaQLParser parser = ParserFactory.getParser(headConstraintList);
        return ParserExecutor.execute(
            context,
            () -> parser.headConstraintListUnit().headConstraintList().accept(this.headConstraintListVisitor)
        );
    }

    @Nonnull
    public List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull ParseContext context) {
        final EvitaQLParser parser = ParserFactory.getParser(filterConstraintList);
        return ParserExecutor.execute(
            context,
            () -> parser.filterConstraintListUnit().filterConstraintList().accept(this.filterConstraintListVisitor)
        );
    }

    @Nonnull
    public List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull ParseContext context) {
        final EvitaQLParser parser = ParserFactory.getParser(orderConstraintList);
        return ParserExecutor.execute(
            context,
            () -> parser.orderConstraintListUnit().orderConstraintList().accept(this.orderConstraintListVisitor)
        );
    }

    @Nonnull
    public List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull ParseContext context) {
        final EvitaQLParser parser = ParserFactory.getParser(requireConstraintList);
        return ParserExecutor.execute(
            context,
            () -> parser.requireConstraintListUnit().requireConstraintList().accept(this.requireConstraintListVisitor)
        );
    }

    @Nonnull
    public <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull ParseContext context) {
        context.setMode(ParseMode.UNSAFE);
        final EvitaQLParser parser = ParserFactory.getParser(value);
        //noinspection unchecked
        return ParserExecutor.execute(
            context,
            () -> (T) parser.valueTokenUnit().valueToken().accept(this.valueTokenVisitor).asSerializable()
        );
    }

    @Nonnull
    private Query parseQueryUnsafe(@Nonnull String query, @Nonnull ParseContext context) {
        context.setMode(ParseMode.UNSAFE);
        return parseQuery(query, context);
    }

    @Nonnull
    public List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull ParseContext context) {
        context.setMode(ParseMode.UNSAFE);
        return parseHeadConstraintList(headConstraintList, context);
    }

    @Nonnull
    public List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull ParseContext context) {
        context.setMode(ParseMode.UNSAFE);
        return parseFilterConstraintList(filterConstraintList, context);
    }

    @Nonnull
    public List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull ParseContext context) {
        context.setMode(ParseMode.UNSAFE);
        return parseOrderConstraintList(orderConstraintList, context);
    }

    @Nonnull
    public List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull ParseContext context) {
        context.setMode(ParseMode.UNSAFE);
        return parseRequireConstraintList(requireConstraintList, context);
    }
}

