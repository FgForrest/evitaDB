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
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.FormattableBigDecimal;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * {@link Coercing} for converting between Java's side {@link BigDecimal} and client string.
 * On top of basic {@link BigDecimal} conversion, it supports {@link FormattableBigDecimal} and its derivatives for
 * customized formatting.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class BigDecimalCoercing implements Coercing<BigDecimal, String> {

    @Override
    public String serialize(@Nonnull Object dataFetcherResult) throws CoercingSerializeException {
        if (dataFetcherResult instanceof BigDecimal bigDecimal) {
            return EvitaDataTypes.formatValue(bigDecimal);
        }
        if (dataFetcherResult instanceof final FormattableBigDecimal formattableBigDecimal) {
            return formattableBigDecimal.toFormattedString();
        }
        throw new CoercingSerializeException("Big decimal data fetcher result is not a form of big decimal value.");
    }

    @Nonnull
    @Override
    public BigDecimal parseValue(@Nonnull Object input) throws CoercingParseValueException {
        if (!(input instanceof String)) {
            throw new CoercingParseValueException("Big decimal input is not a string.");
        }
        try {
            return new BigDecimal((String) input);
        } catch (NumberFormatException ex) {
            throw new CoercingParseValueException(ex.getMessage(), ex);
        }
    }

    @Nonnull
    @Override
    public BigDecimal parseLiteral(@Nonnull Object input) throws CoercingParseLiteralException {
        if (!(input instanceof StringValue)) {
            throw new CoercingParseLiteralException("Big decimal input is not a StringValue.");
        }
        try {
            return new BigDecimal(((StringValue) input).getValue());
        } catch (NumberFormatException ex) {
            throw new CoercingParseLiteralException(ex.getMessage(), ex);
        }
    }
}
