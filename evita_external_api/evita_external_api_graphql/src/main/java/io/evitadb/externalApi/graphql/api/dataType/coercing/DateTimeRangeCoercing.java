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
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * {@link Coercing} for converting between Java's side {@link DateTimeRange} and client string.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class DateTimeRangeCoercing extends RangeCoercing<OffsetDateTime, DateTimeRange, String> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;


    @Override
    protected Class<DateTimeRange> getRangeClass() {
        return DateTimeRange.class;
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
    protected DateTimeRange createRange(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
        if (from != null && to != null) {
            return DateTimeRange.between(from, to);
        } else if (from != null) {
            return DateTimeRange.since(from);
        } else if (to != null) {
            return DateTimeRange.until(to);
        } else {
            throw new GraphQLInvalidArgumentException("Datetime range can never be created with both bounds null!");
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
    protected String formatRangeEnd(@Nullable OffsetDateTime end) {
        return Optional.ofNullable(end)
            .map(e -> e.truncatedTo(ChronoUnit.MILLIS).format(FORMATTER))
            .orElse(null);
    }

    @Nullable
    @Override
    protected OffsetDateTime parseRangeEnd(@Nullable String end) {
        return Optional.ofNullable(end)
            .map(e -> OffsetDateTime.parse(e, FORMATTER))
            .orElse(null);
    }
}
