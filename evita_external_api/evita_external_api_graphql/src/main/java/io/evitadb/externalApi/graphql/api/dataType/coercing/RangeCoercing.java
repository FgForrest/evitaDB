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

package io.evitadb.externalApi.graphql.api.dataType.coercing;

import graphql.language.ArrayValue;
import graphql.language.Node;
import graphql.language.NullValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Range;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.DateTimeException;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * {@link Coercing} for converting between Java's side {@link DateTimeRange} and client string.
 *
 * @param <T> client tuple component Java type
 * @param <E> range end type
 * @param <R> range type
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class RangeCoercing<E, R extends Range<E>, T> implements Coercing<R, T[]> {

    protected abstract Class<R> getRangeClass();

    protected abstract Class<T> getTupleComponentClass();

    @Override
    public T[] serialize(@Nonnull Object dataFetcherResult) throws CoercingSerializeException {
        if (!getRangeClass().isAssignableFrom(dataFetcherResult.getClass())) {
            throw new CoercingSerializeException("Range data fetcher result is not a '" + getRangeClass().getName() + "'.");
        }
        try {
            //noinspection unchecked
            final R range = (R) dataFetcherResult;
            return createTuple(
                formatRangeEnd(range.getPreciseFrom()),
                formatRangeEnd(range.getPreciseTo())
            );
        } catch (DateTimeException ex) {
            throw new CoercingSerializeException(ex);
        }
    }

    @Nonnull
    @Override
    public R parseValue(@Nonnull Object input) throws CoercingParseValueException {
        if (!(input instanceof Collection<?>)) {
            throw new CoercingParseValueException("Range input value is not a tuple (array).");
        }
        //noinspection unchecked
        final Collection<T> tuple = (Collection<T>) input;
        if (tuple.size() != 2) {
            throw new CoercingParseValueException("Range input value is not a tuple with 2 items.");
        }
        try {
            final Iterator<T> tupleIterator = tuple.iterator();
            return createRange(
                parseRangeEnd(tupleIterator.next()),
                parseRangeEnd(tupleIterator.next())
            );
        } catch (RuntimeException ex) {
            throw new CoercingParseValueException(ex.getMessage(), ex);
        }
    }

    @Nonnull
    @Override
    public R parseLiteral(@Nonnull Object input) throws CoercingParseLiteralException {
        if (!(input instanceof ArrayValue)) {
            throw new CoercingParseLiteralException("Range input value is not a tuple (list).");
        }
        try {
            //noinspection rawtypes
            final List<Node> items = ((ArrayValue) input).getChildren();
            if (items.size() != 2) {
                throw new CoercingParseLiteralException("Range input value is not a tuple with 2 items.");
            }
            return createRange(
                parseRangeEndNode(items.get(0)),
                parseRangeEndNode(items.get(1))
            );
        } catch (DateTimeParseException | IllegalArgumentException | ArithmeticException ex) {
            throw new CoercingParseLiteralException(ex);
        }
    }

    @Nonnull
    protected abstract T[] createTuple(@Nullable T from, @Nullable T to);

    @Nonnull
    protected abstract R createRange(@Nullable E from, @Nullable E to);

    @Nonnull
    protected abstract T extractRangeEndFromNode(@Nonnull Object node);

    @Nullable
    protected abstract T formatRangeEnd(@Nullable E end);

    @Nullable
    protected abstract E parseRangeEnd(@Nullable T end);

    @Nullable
    private E parseRangeEndNode(@Nullable Object endNode) {
        return Optional.ofNullable(endNode)
            .map(i -> {
                if (i instanceof NullValue) {
                    return null;
                }
                return parseRangeEnd(extractRangeEndFromNode(i));
            })
            .orElse(null);
    }

}
