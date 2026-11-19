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
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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

	/**
	 * Comparable value types accepted by EvitaQL.
	 */
	@Nonnull
	private static final Set<Class<?>> COMPARABLE_TYPES = Set.of(
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

	/**
	 * Attribute types bound before factory validation. The factories normalize floating-point numbers and enums
	 * and reject chain predecessors with an attribute-specific diagnostic.
	 */
	@Nonnull
	private static final Set<Class<?>> ATTRIBUTE_VALUE_TYPES = Set.copyOf(
		CollectionUtils.combine(
			COMPARABLE_TYPES,
			Set.of(Currency.class, Locale.class, Float.class, Double.class, float.class, double.class,
				Enum.class, ChainableType.class)
		)
	);

	/**
	 * Whether the visitor reads attribute values that will be validated by the query factories.
	 */
	private final boolean attributeValues;

    protected final EvitaQLParameterVisitor parameterVisitor = new EvitaQLParameterVisitor();

    /**
     * Set of {@link EvitaDataTypes} which are allowed to be parsed.
     */
    protected final Set<Class<?>> allowedDataTypes;

	/**
	 * Creates a visitor that resolves enum literals through an enum wrapper.
	 *
	 * @param allowedDataTypes the types permitted in this position
	 */
	private EvitaQLValueTokenVisitor(@Nonnull Set<Class<?>> allowedDataTypes) {
		this(allowedDataTypes, false);
	}

	/**
	 * Creates a visitor with position-specific validation and optional attribute value handling.
	 *
	 * @param allowedDataTypes the types permitted in this position
	 * @param attributeValues whether values are read for the attribute filter and ordering factories
	 */
	private EvitaQLValueTokenVisitor(@Nonnull Set<Class<?>> allowedDataTypes, boolean attributeValues) {
		this.allowedDataTypes = allowedDataTypes;
		this.attributeValues = attributeValues;
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
	 * Creates a visitor for comparable value positions.
	 *
	 * @return the visitor configured for comparable types
	 */
	@Nonnull
	public static EvitaQLValueTokenVisitor withComparableTypesAllowed() {
		return new EvitaQLValueTokenVisitor(COMPARABLE_TYPES);
	}

	/**
	 * Creates a visitor for attribute values, including enum names and values normalized by the Java factories.
	 *
	 * @return the visitor for attribute value positions
	 */
	@Nonnull
	static EvitaQLValueTokenVisitor withAttributeValueTypesAllowed() {
		return new EvitaQLValueTokenVisitor(ATTRIBUTE_VALUE_TYPES, true);
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

	/**
	 * Parses a literal or an explicit sequence in a list position and retains its argument shape.
	 *
	 * @param ctx the explicit value tokens
	 * @return the parsed arguments
	 */
	@Nonnull
	@Override
	public Value visitExplicitVariadicValueTokens(@Nonnull ExplicitVariadicValueTokensContext ctx) {
		return parse(ctx, () -> {
			if (ctx.valueTokens.size() == 1) {
				return Value.singleVariadicArgument(ctx.valueTokens.get(0).accept(this).getActualValue());
			}
			return new Value(ctx.valueTokens.stream().map(token -> token.accept(this).getActualValue()).toList());
		});
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

	/**
	 * Parses an unsafe enum literal as an enum wrapper or, in attribute value positions, as its untyped name.
	 *
	 * @param ctx the enum literal token
	 * @return the value represented by the token
	 */
	@Nonnull
	@Override
	public Value visitEnumValueToken(@Nonnull EnumValueTokenContext ctx) {
		assertLiteralIsAllowed(ctx);
		assertSubclassOfDataTypeIsAllowed(ctx, Enum.class);

		final EnumWrapper literal = EnumWrapper.fromString(ctx.getText());
		return new Value(this.attributeValues ? literal.getValue() : literal);
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
	 * Checks whether the supplied type may reach this value position. The attribute factories decide whether
	 * an array binding is a list or an invalid single value.
	 *
	 * @param ctx the value or argument token being parsed
	 * @param dataType the type to check
	 * @throws EvitaSyntaxException when the type is forbidden in this position
	 */
	protected void assertDataTypeIsAllowed(@Nonnull ParserRuleContext ctx, @Nonnull Class<?> dataType) {
		if (this.attributeValues && dataType.isArray()) {
			return;
		}
		if (this.allowedDataTypes != null
			&& this.allowedDataTypes.stream().anyMatch(type -> type.isAssignableFrom(dataType))) {
			return;
		}

		throw new EvitaSyntaxException(
			ctx,
			"Data type `" + dataType.getTypeName() + "` is not allowed in `" + ctx.getText() + "`."
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
	 * Validates a bound array or iterable as a list, or wraps one scalar or null as a single variadic argument.
	 * Null items reach the constraint factory, which ignores or refuses them according to its argument rules.
	 *
	 * @param ctx argument syntax, used for diagnostics
	 * @param argument bound list or single value
	 * @return the parsed argument with its shape retained
	 * @throws EvitaSyntaxException when a defined item has a type that is forbidden in this position
	 */
	@Nonnull
	protected Value parseVariadicArguments(@Nonnull ParserRuleContext ctx, @Nullable Object argument) {
		if (argument instanceof Iterable<?> iterableArgument) {
			for (final Object item : iterableArgument) {
				assertVariadicItemType(ctx, item);
			}
			return new Value(argument);
		} else if (argument != null && argument.getClass().isArray()) {
			assertVariadicArrayTypes(ctx, argument);
			return new Value(argument);
		} else {
			if (argument != null) {
				assertDataTypeIsAllowed(ctx, argument.getClass());
			}
			return Value.singleVariadicArgument(argument);
		}
	}

	/**
	 * Checks the declared component and the items of a bound array after binding has limited the nesting depth.
	 *
	 * @param ctx the argument syntax
	 * @param array the bound array
	 * @throws EvitaSyntaxException when its component or an item has a forbidden type
	 */
	private void assertVariadicArrayTypes(@Nonnull ParserRuleContext ctx, @Nonnull Object array) {
		final Class<?> component = array.getClass().getComponentType();
		if (component != Object.class && component != Serializable.class) {
			assertDataTypeIsAllowed(ctx, component);
		}
		final int length = Array.getLength(array);
		for (int i = 0; i < length; i++) {
			assertVariadicItemType(ctx, Array.get(array, i));
		}
	}

	/**
	 * Checks a bound list item and the types inside an array item before the attribute factory applies its shape
	 * rules.
	 *
	 * @param ctx the argument syntax
	 * @param item the bound item, or null for an undefined value
	 * @throws EvitaSyntaxException when the item or one of its components has a forbidden type
	 */
	private void assertVariadicItemType(@Nonnull ParserRuleContext ctx, @Nullable Object item) {
		if (item != null) {
			assertDataTypeIsAllowed(ctx, item.getClass());
			if (this.attributeValues && item.getClass().isArray()) {
				assertVariadicArrayTypes(ctx, item);
			}
		}
	}
}
