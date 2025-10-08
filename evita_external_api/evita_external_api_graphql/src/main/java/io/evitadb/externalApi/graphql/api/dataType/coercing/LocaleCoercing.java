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

package io.evitadb.externalApi.graphql.api.dataType.coercing;

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;

import javax.annotation.Nonnull;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link Coercing} for converting between Java's side {@link Locale} and client string.
 * This is used mainly as type in data (attribute values, a. data values,...) where we don't know all possible values.
 *
 * It uses standardized {@link Locale#toLanguageTag()} as string representation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class LocaleCoercing implements Coercing<Locale, String> {

    @Override
    public String serialize(@Nonnull Object dataFetcherResult) throws CoercingSerializeException {
        if (!(dataFetcherResult instanceof Locale)) {
            throw new CoercingSerializeException("Locale data fetcher result is not a Locale.");
        }
        try {
            return ((Locale) dataFetcherResult).toLanguageTag();
        } catch (DateTimeException ex) {
            throw new CoercingSerializeException(ex.getMessage(), ex);
        }
    }

    @Nonnull
    @Override
    public Locale parseValue(@Nonnull Object input) throws CoercingParseValueException {
        if (!(input instanceof String)) {
            throw new CoercingParseValueException("Locale input is not a string.");
        }
        try {
            return Objects.requireNonNull(EvitaDataTypes.toTargetType((String) input, Locale.class));
        } catch (UnsupportedDataTypeException | InconvertibleDataTypeException ex) {
            throw new CoercingParseValueException(ex.getMessage(), ex);
        }
    }

    @Nonnull
    @Override
    public Locale parseLiteral(@Nonnull Object input) throws CoercingParseLiteralException {
        if (!(input instanceof StringValue)) {
            throw new CoercingParseValueException("Locale input is not a StringValue.");
        }
        try {
            return Objects.requireNonNull(EvitaDataTypes.toTargetType(((StringValue) input).getValue(), Locale.class));
        } catch (UnsupportedDataTypeException | InconvertibleDataTypeException ex) {
            throw new CoercingParseLiteralException(ex.getMessage(), ex);
        }
    }
}
