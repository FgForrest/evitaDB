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
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

import javax.annotation.Nonnull;
import java.math.BigInteger;

/**
 * {@link Coercing} for converting between Java's side {@link Short} and client int.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ShortCoercing implements Coercing<Short, Integer> {

    @Nonnull
    @Override
    public Integer serialize(@Nonnull Object dataFetcherResult) throws CoercingSerializeException {
        if (!(dataFetcherResult instanceof Short)) {
            throw new CoercingSerializeException("Short data fetcher result is not a short.");
        }
        return (int) dataFetcherResult;
    }

    @Nonnull
    @Override
    public Short parseValue(@Nonnull Object input) throws CoercingParseValueException {
        if (!(input instanceof Integer)) {
            throw new CoercingParseValueException("Short input value is not a integer.");
        }
        try {
            return new BigInteger(input.toString()).shortValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new CoercingParseValueException(ex.getMessage(), ex);
        }
    }

    @Nonnull
    @Override
    public Short parseLiteral(@Nonnull Object input) throws CoercingParseLiteralException {
        if (!(input instanceof IntValue)) {
            throw new CoercingParseValueException("Short input value is not a integer.");
        }
        try {
            return ((IntValue) input).getValue().shortValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new CoercingParseLiteralException(ex.getMessage(), ex);
        }
    }
}
