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

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.IntValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import io.evitadb.dataType.ReferencedEntityPredecessor;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * {@link Coercing} for converting between Java's side {@link ReferencedEntityPredecessor} and client integer.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ReferencedEntityPredecessorCoercing implements Coercing<ReferencedEntityPredecessor, Integer> {

    @Override
    public Integer serialize(
        @Nonnull Object dataFetcherResult,
        @Nonnull GraphQLContext graphQLContext,
        @Nonnull Locale locale
    ) throws CoercingSerializeException {
        if (!(dataFetcherResult instanceof ReferencedEntityPredecessor referencedEntityPredecessor)) {
            throw new CoercingSerializeException("ReferencedEntityPredecessor data fetcher result is not a ReferencedEntityPredecessor.");
        }
        return referencedEntityPredecessor.predecessorPk();
    }

    @Nonnull
    @Override
    public ReferencedEntityPredecessor parseValue(
        @Nonnull Object input,
        @Nonnull GraphQLContext graphQLContext,
        @Nonnull Locale locale
    ) throws CoercingParseValueException {
        if (!(input instanceof Integer integerInput)) {
            throw new CoercingParseValueException("Predecessor input value is not an integer.");
        }
        try {
            return new ReferencedEntityPredecessor(integerInput);
        } catch (IllegalArgumentException ex) {
            throw new CoercingParseValueException(ex.getMessage(), ex);
        }
    }

    @Nonnull
    @Override
    public ReferencedEntityPredecessor parseLiteral(
        @Nonnull Value<?> input,
        @Nonnull CoercedVariables variables,
        @Nonnull GraphQLContext graphQLContext,
        @Nonnull Locale locale
    ) throws CoercingParseLiteralException {
        if (!(input instanceof IntValue intValue)) {
            throw new CoercingParseValueException("ReferencedEntityPredecessor input value is not an integer.");
        }
        try {
            return new ReferencedEntityPredecessor(intValue.getValue().intValueExact());
        } catch (IllegalArgumentException ex) {
            throw new CoercingParseLiteralException(ex.getMessage(), ex);
        }
    }
}
