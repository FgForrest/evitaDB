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

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * {@link Coercing} for converting between Java's side {@link LongNumberRange} and client tuple (array).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class LongNumberRangeCoercing extends RangeCoercing<Long, LongNumberRange, String> {

    @Override
    protected Class<LongNumberRange> getRangeClass() {
        return LongNumberRange.class;
    }

    @Override
    protected Class<String> getTupleComponentClass() {
        return String.class;
    }

    @Nonnull
    @Override
    protected String[] createTuple(@Nullable String from, @Nullable String to) {
        return new String[] { from, to };
    }

    @Nonnull
    @Override
    protected LongNumberRange createRange(@Nullable Long left, @Nullable Long right) {
        if (left != null && right != null) {
            return LongNumberRange.between(left, right);
        } else if (left != null) {
            return LongNumberRange.from(left);
        } else if (right != null) {
            return LongNumberRange.to(right);
        } else {
            throw new GraphQLInvalidArgumentException("Both left and right arguments cannot be null!");
        }
    }

    @Nonnull
    @Override
    protected String extractRangeEndFromNode(@Nonnull Object node) {
        if (!(node instanceof StringValue)) {
            throw new CoercingParseLiteralException("Item of range input value is not a string.");
        }
        return ((StringValue) node).getValue();
    }

    @Nullable
    @Override
    protected String formatRangeEnd(@Nullable Long end) {
        return Optional.ofNullable(end)
            .map(Object::toString)
            .orElse(null);
    }

    @Nullable
    @Override
    protected Long parseRangeEnd(@Nullable String end) {
        return Optional.ofNullable(end)
            .map(Long::parseLong)
            .orElse(null);
    }

}
