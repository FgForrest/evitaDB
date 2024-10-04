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

package io.evitadb.externalApi.graphql.api.dataType;

import graphql.Scalars;
import graphql.scalar.GraphqlBooleanCoercing;
import graphql.scalar.GraphqlIntCoercing;
import graphql.scalar.GraphqlStringCoercing;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import io.evitadb.externalApi.graphql.api.dataType.coercing.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static graphql.schema.GraphQLScalarType.newScalar;

/**
 * Entry point for all GraphQL scalars supported by Evita.
 * This list was created because Evita GraphQL API uses scalars from multiple places, and it could become difficult to
 * track all the places from which the scalars come from if it wasn't managed in single place.
 *
 * Note: most of the time these scalars are registered implicitly by including them in objects in schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GraphQLScalars {

    public static final Coercing<?, ?> STRING_COERCING = new GraphqlStringCoercing();
    public static final GraphQLScalarType STRING = Scalars.GraphQLString;

    public static final Coercing<?, ?> BOOLEAN_COERCING = new GraphqlBooleanCoercing();
    public static final GraphQLScalarType BOOLEAN = Scalars.GraphQLBoolean;

    public static final Coercing<?, ?> BYTE_COERCING = new ByteCoercing();
    public static final GraphQLScalarType BYTE = newScalar()
        .name("Byte")
        .description("A 8-bit signed integer")
        .coercing(BYTE_COERCING)
        .build();

    public static final Coercing<?, ?> SHORT_COERCING = new ShortCoercing();
    public static final GraphQLScalarType SHORT = newScalar()
        .name("Short")
        .description("A 16-bit signed integer")
        .coercing(SHORT_COERCING)
        .build();

    public static final Coercing<?, ?> INT_COERCING = new GraphqlIntCoercing();
    public static final GraphQLScalarType INT = Scalars.GraphQLInt;

    public static final Coercing<?, ?> LONG_COERCING = new LongCoercing();
    public static final GraphQLScalarType LONG = newScalar()
        .name("Long")
        .description("A 64-bit signed integer")
        .coercing(LONG_COERCING)
        .build();

    public static final Coercing<?, ?> BIG_DECIMAL_COERCING = new BigDecimalCoercing();
    public static final GraphQLScalarType BIG_DECIMAL = newScalar()
        .name("BigDecimal")
        .description("An arbitrary precision signed decimal")
        .coercing(BIG_DECIMAL_COERCING)
        .build();

    public static final Coercing<?, ?> LOCAL_DATE_COERCING = new LocalDateCoercing();
    public static final GraphQLScalarType LOCAL_DATE = newScalar()
        .name("Date")
        .description("An RFC-3339 compliant Full Date Scalar")
        .coercing(LOCAL_DATE_COERCING)
        .build();

    public static final Coercing<?, ?> LOCAL_TIME_COERCING = new LocalTimeCoercing();
    public static final GraphQLScalarType LOCAL_TIME = newScalar()
        .name("LocalTime")
        .description("24-hour clock time value string in the format `hh:mm:ss` or `hh:mm:ss.sss`.")
        .coercing(LOCAL_TIME_COERCING)
        .build();

    public static final Coercing<?, ?> LOCAL_DATE_TIME_COERCING = new LocalDateTimeCoercing();
    public static final GraphQLScalarType LOCAL_DATE_TIME = newScalar()
        .name("LocalDateTime")
        .description("ISO 8601 date time without offset.")
        .coercing(LOCAL_DATE_TIME_COERCING)
        .build();

    public static final Coercing<?, ?> OFFSET_DATE_TIME_COERCING = new OffsetDateTimeCoercing();
    public static final GraphQLScalarType OFFSET_DATE_TIME = newScalar()
        .name("OffsetDateTime")
        .description("ISO 8601 date time with offset.")
        .coercing(OFFSET_DATE_TIME_COERCING)
        .build();

    public static final Coercing<?, ?> DATE_TIME_RANGE_COERCING = new DateTimeRangeCoercing();
    public static final GraphQLScalarType DATE_TIME_RANGE = newScalar()
        .name("DateTimeRange")
        .description("Range of ISO 8601 offset date times.")
        .coercing(DATE_TIME_RANGE_COERCING)
        .build();

    public static final Coercing<?, ?> BIG_DECIMAL_NUMBER_RANGE_COERCING = new BigDecimalNumberRangeCoercing();
    public static final GraphQLScalarType BIG_DECIMAL_NUMBER_RANGE = newScalar()
        .name("BigDecimalNumberRange")
        .description("Range of an arbitrary precision signed decimal values.")
        .coercing(BIG_DECIMAL_NUMBER_RANGE_COERCING)
        .build();

    public static final Coercing<?, ?> BYTE_NUMBER_RANGE_COERCING = new ByteNumberRangeCoercing();
    public static final GraphQLScalarType BYTE_NUMBER_RANGE = newScalar()
        .name("ByteNumberRange")
        .description("Range of a 8-bit signed integer values.")
        .coercing(BYTE_NUMBER_RANGE_COERCING)
        .build();

    public static final Coercing<?, ?> SHORT_NUMBER_RANGE_COERCING = new ShortNumberRangeCoercing();
    public static final GraphQLScalarType SHORT_NUMBER_RANGE = newScalar()
        .name("ShortNumberRange")
        .description("Range of a 16-bit signed integer values.")
        .coercing(SHORT_NUMBER_RANGE_COERCING)
        .build();

    public static final Coercing<?, ?> INTEGER_NUMBER_RANGE_COERCING = new IntegerNumberRangeCoercing();
    public static final GraphQLScalarType INTEGER_NUMBER_RANGE = newScalar()
        .name("IntegerNumberRange")
        .description("Range of a 32-bit signed integer values.")
        .coercing(INTEGER_NUMBER_RANGE_COERCING)
        .build();

    public static final Coercing<?, ?> LONG_NUMBER_RANGE_COERCING = new LongNumberRangeCoercing();
    public static final GraphQLScalarType LONG_NUMBER_RANGE = newScalar()
        .name("LongNumberRange")
        .description("Range of a 64-bit signed integer values.")
        .coercing(LONG_NUMBER_RANGE_COERCING)
        .build();

    public static final Coercing<?, ?> LOCALE_COERCING = new LocaleCoercing();
    public static final GraphQLScalarType LOCALE = newScalar()
        .name("Locale")
        .description("A IETF BCP 47 language tag")
        .coercing(LOCALE_COERCING)
        .build();

    public static final Coercing<?, ?> CURRENCY_COERCING = new CurrencyCoercing();
    public static final GraphQLScalarType CURRENCY = newScalar()
        .name("Currency")
        .description("Currency in ISO 4217 format.")
        .coercing(CURRENCY_COERCING)
        .build();

    public static final Coercing<?, ?> UUID_COERCING = new UuidCoercing();
    public static final GraphQLScalarType UUID = newScalar()
        .name("UUID")
        .description("UUID in string format.")
        .coercing(UUID_COERCING)
        .build();

    public static final Coercing<?, ?> PREDECESSOR_COERCING = new PredecessorCoercing();
    public static final GraphQLScalarType PREDECESSOR = newScalar()
        .name("Predecessor")
        .description("Predecessor in number format.")
        .coercing(PREDECESSOR_COERCING)
        .build();

    public static final Coercing<?, ?> REFERENCED_ENTITY_PREDECESSOR_COERCING = new ReferencedEntityPredecessorCoercing();
    public static final GraphQLScalarType REFERENCED_ENTITY_PREDECESSOR = newScalar()
        .name("ReferencedEntityPredecessor")
        .description("An inverse Predecessor (using referenced entity PK instead of this entity PK) in number format.")
        .coercing(REFERENCED_ENTITY_PREDECESSOR_COERCING)
        .build();

    public static final Coercing<?, ?> OBJECT_COERCING = new ObjectCoercing();
    public static final GraphQLScalarType COMPLEX_DATA_OBJECT = newScalar()
        .name("ComplexDataObject")
        .description("A generic complex data object")
        .coercing(OBJECT_COERCING)
        .build();
    public static final GraphQLScalarType OBJECT = newScalar()
        .name("Object")
        .description("A generic JSON object")
        .coercing(OBJECT_COERCING)
        .build();

    public static final Coercing<?, ?> EXPRESSION_COERCING = new ExpressionCoercing();
    public static final GraphQLScalarType EXPRESSION = newScalar()
        .name("ExpressionFactory")
        .description("ExpressionFactory statement allowing simple mathematics formulas and boolean algebra including support for externally provide data via input variables.")
        .coercing(EXPRESSION_COERCING)
        .build();

    public static final AnyCoercing ANY_COERCING = new AnyCoercing();
    /**
     * Allows any simple scalar type on output. And forbid any input values. It basically delegates serialization
     * to coercings bellow.
     *
     * <b>Note: should be used only in edge cases and only as output type, never is input type. Please consider other
     * ways that allow specifying specific types in schema first.</b>
     */
    public static final GraphQLScalarType ANY = newScalar()
        .name("Any")
        .description("Generic scalar that may contain any supported simple scalar.")
        .coercing(ANY_COERCING)
        .build();

}
