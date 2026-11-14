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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.parser.EnumWrapper;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.Value;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static io.evitadb.api.query.parser.grammar.EvitaQLParser.*;

/**
 * <p>Implementation of {@link EvitaQLVisitor} for parsing all values: parameters, literals and their variadic variants.
 * It produces wrapper {@link Value} for all parsed values.</p>
 *
 * <p>When creating new instance one can specify which data types are allowed and which not. This allows specifying
 * which data types are allowed in current context (e.g. in some constraint's parameters only integers are allowed).
 * These data types must conform to {@link EvitaDataTypes#getSupportedDataTypes()}.</p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public class EvitaQLValueTokenVisitor extends EvitaQLBaseVisitor<Value> {

    private static final String EXPECTED_OFFSET_DATE_TIME_FORMAT = "yyyy-MM-ddTHH:mm:ss.sss+-HH:mm";
    private static final String EXPECTED_LOCAL_DATE_TIME_FORMAT = "yyyy-MM-ddTHH:mm:ss.sss";
    private static final String EXPECTED_LOCAL_TIME_FORMAT = "HH:mm:ss.sss";

    protected final EvitaQLParameterVisitor parameterVisitor = new EvitaQLParameterVisitor();

    /**
     * Set of {@link EvitaDataTypes} which are allowed to be parsed.
     */
    protected final Set<Class<?>> allowedDataTypes;

    /**
     * Creates value token visitor with custom array of data types allowed for parsing.
     */
    private EvitaQLValueTokenVisitor(@Nonnull Set<Class<?>> allowedDataTypes) {
        this.allowedDataTypes = allowedDataTypes;
    }

    /**
     * Creates value token visitor with custom array of data types allowed for parsing.
     */
    private EvitaQLValueTokenVisitor(@Nonnull Class<?>... allowedDataTypes) {
        this(Set.of(allowedDataTypes));
    }


    /**
     * Creates value token visitor with all data types allowed for parsing.
     */
    public static EvitaQLValueTokenVisitor withAllDataTypesAllowed() {
        return new EvitaQLValueTokenVisitor(
            String.class,
            byte.class,
            Byte.class,
            short.class,
            Short.class,
            int.class,
            Integer.class,
            long.class,
            Long.class,
            boolean.class,
            Boolean.class,
            char.class,
            Character.class,
            BigDecimal.class,
            OffsetDateTime.class,
            LocalDateTime.class,
            LocalDate.class,
            LocalTime.class,
            DateTimeRange.class,
            BigDecimalNumberRange.class,
            LongNumberRange.class,
            IntegerNumberRange.class,
            ShortNumberRange.class,
            ByteNumberRange.class,
            Locale.class,
            Currency.class,
            UUID.class,
            Enum.class
        );
    }

    /**
     * Creates value token visitor with all comparable types allowed for parsing.
     */
    public static EvitaQLValueTokenVisitor withComparableTypesAllowed() {
        return new EvitaQLValueTokenVisitor(
            String.class,
            byte.class,
            Byte.class,
            short.class,
            Short.class,
            int.class,
            Integer.class,
            long.class,
            Long.class,
            boolean.class,
            Boolean.class,
            char.class,
            Character.class,
            BigDecimal.class,
            OffsetDateTime.class,
            LocalDateTime.class,
            LocalDate.class,
            LocalTime.class,
            DateTimeRange.class,
            BigDecimalNumberRange.class,
            LongNumberRange.class,
            IntegerNumberRange.class,
            ShortNumberRange.class,
            ByteNumberRange.class,
            UUID.class
        );
    }

    /**
     * Creates value token visitor with custom array of data types allowed for parsing.
     */
    public static EvitaQLValueTokenVisitor withAllowedTypes(@Nonnull Class<?>... allowedDataTypes) {
        return new EvitaQLValueTokenVisitor(allowedDataTypes);
    }


    @Override
    public Value visitPositionalParameterVariadicValueTokens(PositionalParameterVariadicValueTokensContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.positionalParameter().accept(this.parameterVisitor);
                return parseVariadicArguments(ctx, argument);
            }
        );
    }

    @Override
    public Value visitNamedParameterVariadicValueTokens(NamedParameterVariadicValueTokensContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.namedParameter().accept(this.parameterVisitor);
                return parseVariadicArguments(ctx, argument);
            }
        );
    }

    @Override
    public Value visitExplicitVariadicValueTokens(ExplicitVariadicValueTokensContext ctx) {
        return parse(
            ctx,
            () -> new Value(
                ctx.valueTokens
                    .stream()
                    .map(vt -> vt.accept(this).getActualValue())
                    .toList()
            )
        );
    }

    @Override
    public Value visitPositionalParameterValueToken(PositionalParameterValueTokenContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.positionalParameter().accept(this.parameterVisitor);
				if (argument != null) {
					assertDataTypeIsAllowed(ctx, argument.getClass());
				}
                return new Value(argument);
            }
        );
    }

    @Override
    public Value visitNamedParameterValueToken(NamedParameterValueTokenContext ctx) {
        return parse(
            ctx,
            () -> {
                final Object argument = ctx.namedParameter().accept(this.parameterVisitor);
				if (argument != null) {
					assertDataTypeIsAllowed(ctx, argument.getClass());
				}
                return new Value(argument);
            }
        );
    }

    @Override
    public Value visitIntValueToken(IntValueTokenContext ctx) {
        return parse(
            ctx,
            Long.class,
            () -> Long.valueOf(ctx.getText())
        );
    }

    @Override
    public Value visitStringValueToken(StringValueTokenContext ctx) {
        return parse(
            ctx,
            String.class,
            () -> StringUtils.translateEscapes(ctx.getText().substring(1, ctx.getText().length() - 1))
        );
    }

    @Override
    public Value visitFloatValueToken(FloatValueTokenContext ctx) {
        return parse(
            ctx,
            BigDecimal.class,
            () -> new BigDecimal(ctx.getText())
        );
    }

    @Override
    public Value visitBooleanValueToken(BooleanValueTokenContext ctx) {
        return parse(
            ctx,
            Boolean.class,
            () -> Boolean.parseBoolean(ctx.getText())
        );
    }

    @Override
    public Value visitDateValueToken(DateValueTokenContext ctx) {
        return parse(
            ctx,
            LocalDate.class,
            () -> LocalDate.from(DateTimeFormatter.ISO_LOCAL_DATE.parse(ctx.getText()))
        );
    }

    @Override
    public Value visitTimeValueToken(TimeValueTokenContext ctx) {
        return parse(
            ctx,
            LocalTime.class,
            () -> {
                try {
                    return LocalTime.from(DateTimeFormatter.ISO_LOCAL_TIME.parse(ctx.getText())).truncatedTo(ChronoUnit.MILLIS);
                } catch (DateTimeException ex) {
                    throw new EvitaInvalidUsageException(String.format(
                        "%s. Expected time in variation of format `%s`.", ex.getMessage(), EXPECTED_LOCAL_TIME_FORMAT
                    ));
                }
            }
        );
    }

    @Override
    public Value visitDateTimeValueToken(DateTimeValueTokenContext ctx) {
        return parse(
            ctx,
            LocalDateTime.class,
            () -> {
                try {
                    return LocalDateTime.from(DateTimeFormatter.ISO_LOCAL_DATE_TIME.parse(ctx.getText())).truncatedTo(ChronoUnit.MILLIS);
                } catch (DateTimeException ex) {
                    throw new EvitaInvalidUsageException(String.format(
                        "%s. Expected date time in variation of format `%s`.", ex.getMessage(), EXPECTED_LOCAL_DATE_TIME_FORMAT
                    ));
                }
            }
        );
    }

    @Override
    public Value visitOffsetDateTimeValueToken(OffsetDateTimeValueTokenContext ctx) {
        return parse(
            ctx,
            OffsetDateTime.class,
            () -> {
                try {
                    return OffsetDateTime.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(ctx.getText())).truncatedTo(ChronoUnit.MILLIS);
                } catch (DateTimeException ex) {
                    throw new EvitaInvalidUsageException(String.format(
                        "%s. Expected date time in variation of format `%s`.", ex.getMessage(), EXPECTED_OFFSET_DATE_TIME_FORMAT
                    ));
                }
            }
        );
    }

    @Override
    public Value visitEnumValueToken(EnumValueTokenContext ctx) {
        assertLiteralIsAllowed(ctx);
        assertSubclassOfDataTypeIsAllowed(ctx, Enum.class);

        return new Value(
            EnumWrapper.fromString(ctx.getText())
        );
    }

    @Override
    public Value visitFloatNumberRangeValueToken(FloatNumberRangeValueTokenContext ctx) {
        return parse(
            ctx,
            BigDecimalNumberRange.class,
            () -> BigDecimalNumberRange.fromString(ctx.getText())
        );
    }

    @Override
    public Value visitIntNumberRangeValueToken(IntNumberRangeValueTokenContext ctx) {
        return parse(
            ctx,
            LongNumberRange.class,
            () -> LongNumberRange.fromString(ctx.getText())
        );
    }

    @Override
    public Value visitDateTimeRangeValueToken(DateTimeRangeValueTokenContext ctx) {
        return parse(
            ctx,
            DateTimeRange.class,
            () -> DateTimeRange.fromString(ctx.getText())
        );
    }

    @Override
    public Value visitUuidValueToken(UuidValueTokenContext ctx) {
        return parse(
            ctx,
            UUID.class,
            () -> java.util.UUID.fromString(ctx.getText())
        );
    }

    /**
     * Executes parsing function. Any non-parser exception will be wrapped into parser exception.
     */
    protected Value parse(@Nonnull ParserRuleContext ctx, @Nonnull Class<?> allowedDataType, @Nonnull Supplier<Object> parser) {
        return super.parse(
            ctx,
            () -> {
                assertLiteralIsAllowed(ctx);
                assertDataTypeIsAllowed(ctx, allowedDataType);
                return new Value(parser.get());
            }
        );
    }

    /**
     * Checks if literal values are allowed in current mode.
     */
    protected void assertLiteralIsAllowed(@Nonnull ParserRuleContext ctx) {
        final ParseMode mode = ParserExecutor.getContext().getMode();
        Assert.isTrue(
            mode == ParseMode.UNSAFE,
            () -> new EvitaSyntaxException(
                ctx,
                "Literal value is forbidden in mode `" + mode + "`. For literal use mode `" + ParseMode.UNSAFE + "` but check documentation for potential risks."
            )
        );
    }

    /**
     * Checks if the data type is allowed as output type by caller.
     *
     * @param ctx context of literal being parsed
     * @param dataType data type to check
     */
    protected void assertDataTypeIsAllowed(@Nonnull ParserRuleContext ctx, @Nonnull Class<?> dataType) {
        if ((this.allowedDataTypes != null) && this.allowedDataTypes.stream().anyMatch(type -> type.isAssignableFrom(dataType))) {
            return;
        }

        throw new EvitaSyntaxException(
            ctx,
            String.format(
                "Data type `%s` is not allowed in `%s`.",
                dataType,
                ctx.getText()
            )
        );
    }

    /**
     * Checks if the data type is allowed as output type by caller.
     *
     * @param ctx context of literal being parsed
     * @param dataType data type to check
     */
    protected void assertSubclassOfDataTypeIsAllowed(@Nonnull ParserRuleContext ctx, @Nonnull Class<?> dataType) {
        if ((this.allowedDataTypes != null) && this.allowedDataTypes.stream().anyMatch(dataType::isAssignableFrom)) {
            return;
        }

        throw new EvitaSyntaxException(
            ctx,
            String.format(
                "Data type `%s` is not allowed in `%s`.",
                dataType,
                ctx.getText()
            )
        );
    }

    /**
     * Parses list of arguments from client or creates new list from single argument because only list expected.
     * Supports iterables, arrays and single values.
	 * Every defined item is checked against the allowed types; null items are kept for the constraint factory,
	 * which ignores or refuses them.
	 *
	 * @param ctx argument syntax, used for diagnostics
	 * @param argument bound list or single value
	 * @return the list wrapped as a value
	 * @throws EvitaSyntaxException when the argument is a lone null, which could mean either no list or one
	 *                              undefined item, or when an item has a type that is not allowed here
     */
    @Nonnull
	protected Value parseVariadicArguments(@Nonnull ParserRuleContext ctx, @Nullable Object argument) {
		if (argument == null) {
			ParserRuleContext constraint = ctx;
			while (constraint.getParent() != null && !(constraint instanceof FilterConstraintContext)
				&& !(constraint instanceof OrderConstraintContext) && !(constraint instanceof RequireConstraintContext)
				&& !(constraint instanceof HeadConstraintContext)) {
				constraint = constraint.getParent();
			}
			final String constraintName = constraint.getStart().getText();
			throw new EvitaSyntaxException(ctx, "A lone null parameter in the list of `" + constraintName
				+ "` is ambiguous. Bind an empty list or leave the constraint out.");
		}
        if (argument instanceof final Iterable<?> iterableArgument) {
			for (final Object item : iterableArgument) {
				if (item != null) {
					assertDataTypeIsAllowed(ctx, item.getClass());
				}
            }
            return new Value(argument);
        } else if (argument.getClass().isArray()) {
			final Class<?> component = argument.getClass().getComponentType();
			if (component != Object.class && component != Serializable.class) {
				assertDataTypeIsAllowed(ctx, component);
			}
			final int length = Array.getLength(argument);
			for (int i = 0; i < length; i++) {
				final Object item = Array.get(argument, i);
				if (item != null) {
					assertDataTypeIsAllowed(ctx, item.getClass());
				}
			}
            return new Value(argument);
        } else {
			assertDataTypeIsAllowed(ctx, argument.getClass());
            return new Value(List.of(argument));
        }
    }
}
