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

import graphql.language.IntValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Optional;

/**
 * {@link Coercing} for converting between Java's side {@link ByteNumberRange} and client tuple (array).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ShortNumberRangeCoercing extends RangeCoercing<Short, ShortNumberRange, Integer> {

    @Override
    protected Class<ShortNumberRange> getRangeClass() {
        return ShortNumberRange.class;
    }

    @Override
    protected Class<Integer> getTupleComponentClass() {
        return Integer.class;
    }

    @Nonnull
    @Override
    protected Integer[] createTuple(@Nullable Integer from, @Nullable Integer to) {
        return new Integer[] { from, to };
    }

    @Nonnull
    @Override
    protected ShortNumberRange createRange(@Nullable Short left, @Nullable Short right) {
        if (left != null && right != null) {
            return ShortNumberRange.between(left, right);
        } else if (left != null) {
            return ShortNumberRange.from(left);
        } else if (right != null) {
            return ShortNumberRange.to(right);
        } else {
            throw new GraphQLInvalidArgumentException("Both left and right arguments cannot be null!");
        }
    }

    @Nonnull
    @Override
    protected Integer extractRangeEndFromNode(@Nonnull Object node) {
        if (!(node instanceof IntValue)) {
            throw new CoercingParseLiteralException("Item of range input value is not a integer.");
        }
        return ((IntValue) node).getValue().intValueExact();
    }

    @Nullable
    @Override
    protected Integer formatRangeEnd(@Nullable Short end) {
        return Optional.ofNullable(end)
            .map(e -> (Integer) (int) e)
            .orElse(null);
    }

    @Nullable
    @Override
    protected Short parseRangeEnd(@Nullable Integer end) {
        return Optional.ofNullable(end)
            .map(e -> new BigInteger(end.toString()).shortValueExact())
            .orElse(null);
    }

}
