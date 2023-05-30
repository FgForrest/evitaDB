/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.documentation;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.Query;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.api.query.Constraint.ARG_CLOSING;
import static io.evitadb.api.query.Constraint.ARG_OPENING;
import static java.util.Optional.ofNullable;

/**
 * This visitor can pretty print {@link Query} constraints and print it as Java code so that the output format is easily
 * readable to humans.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class JavaPrettyPrintingVisitor implements ConstraintVisitor {
	/**
	 * Contains the printed form of the input constraint.
	 */
	private final StringBuilder result = new StringBuilder(512);
	/**
	 * Contains string used for new line indentation.
	 */
	private final String fixedIndent;
	/**
	 * Contains string used for block scope indentation.
	 */
	private final String indent;
	/**
	 * Work variable that maintains current depth level of the traversed query.
	 */
	private int level;
	/**
	 * Work variable that gets switched to false when first query is processed.
	 */
	private boolean firstConstraint = true;

	/**
	 * Converts `query` to formatted {@link String} and extracts all used parameters into separate list.
	 * The parameters in the query are replaced with placeholder `?` and the order of the placeholder is the same
	 * as position of the parameter in the result parameter list.
	 *
	 * @param query input query
	 * @return string with formatted query
	 */
	@Nonnull
	public static String toString(@Nonnull Query query) {
		return toString(query, null);
	}

	/**
	 * Converts `query` to formatted {@link String} and extracts all used parameters into separate list.
	 * The parameters in the query are replaced with placeholder `?` and the order of the placeholder is the same
	 * as position of the parameter in the result parameter list.
	 *
	 * @param query  input query
	 * @param indent string used for new line indentation
	 * @return string with formatted query
	 */
	@Nonnull
	public static String toString(@Nonnull Query query, @Nullable String indent) {
		final JavaPrettyPrintingVisitor visitor = new JavaPrettyPrintingVisitor(indent);
		visitor.traverse(query);
		return visitor.getResult();
	}

	/**
	 * Converts `query` to formatted {@link String} and extracts all used parameters into separate list.
	 * The parameters in the query are replaced with placeholder `?` and the order of the placeholder is the same
	 * as position of the parameter in the result parameter list.
	 *
	 * @param query       input query
	 * @param fixedIndent string used for new line indentation
	 * @param indent      string used for new block scope indentation
	 * @return string with formatted query
	 */
	@Nonnull
	public static String toString(@Nonnull Query query, @Nullable String indent, @Nullable String fixedIndent) {
		final JavaPrettyPrintingVisitor visitor = new JavaPrettyPrintingVisitor(indent, fixedIndent);
		visitor.traverse(query);
		return visitor.getResult();
	}

	/**
	 * Converts `query` to formatted {@link String} including all parameters used in int. Tabulator sign `\t`
	 * is used as indentation string.
	 *
	 * @param constraint input query
	 * @return string with formatted query
	 */
	public static String toString(@Nonnull Constraint<?> constraint) {
		final JavaPrettyPrintingVisitor visitor = new JavaPrettyPrintingVisitor(null);
		constraint.accept(visitor);
		return visitor.getResult();
	}

	/**
	 * Converts `query` to formatted {@link String} including all parameters used in int.
	 *
	 * @param constraint input query
	 * @param indent     string used for new line indentation
	 * @return string with formatted query
	 */
	public static String toString(@Nonnull Constraint<?> constraint, @Nonnull String indent) {
		final JavaPrettyPrintingVisitor visitor = new JavaPrettyPrintingVisitor(indent);
		constraint.accept(visitor);
		return visitor.getResult();
	}

	/**
	 * Converts `query` to formatted {@link String} including all parameters used in int.
	 *
	 * @param constraint  input query
	 * @param fixedIndent string used for new line indentation
	 * @param indent      string used for new block scope indentation
	 * @return string with formatted query
	 */
	public static String toString(@Nonnull Constraint<?> constraint, @Nonnull String indent, @Nonnull String fixedIndent) {
		final JavaPrettyPrintingVisitor visitor = new JavaPrettyPrintingVisitor(indent, fixedIndent);
		constraint.accept(visitor);
		return visitor.getResult();
	}

	private JavaPrettyPrintingVisitor(@Nullable String indent) {
		this(indent, null);
	}

	private JavaPrettyPrintingVisitor(@Nullable String indent, @Nullable String fixedIndent) {
		this.level = 0;
		this.indent = indent;
		this.fixedIndent = fixedIndent;
	}

	/**
	 * Traverses and prints the entire query.
	 */
	public void traverse(@Nonnull Query query) {
		if (indent != null) {
			this.result.append(fixedIndent);
		}
		this.result.append("query" + ARG_OPENING).append(newLine());
		this.level = 1;
		ofNullable(query.getCollection()).ifPresent(it -> {
			it.accept(this);
			this.result.append(",");
		});
		ofNullable(query.getFilterBy()).ifPresent(it -> {
			it.accept(this);
			this.result.append(",");
		});
		ofNullable(query.getOrderBy()).ifPresent(it -> {
			it.accept(this);
			this.result.append(",");
		});
		ofNullable(query.getRequire()).ifPresent(it -> {
			it.accept(this);
			this.result.append(",");
		});
		this.result.setLength(this.result.length() - ",".length());
		this.result.append(newLine()).append(ARG_CLOSING);
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		if (firstConstraint) {
			firstConstraint = false;
		} else {
			result.append(newLine());
		}
		indent(indent, level);
		result.append(constraint.getName()).append(ARG_OPENING);
		if (constraint instanceof ConstraintContainer<?>) {
			printContainer((ConstraintContainer<?>) constraint);
		} else if (constraint instanceof ConstraintLeaf) {
			printLeaf(constraint);
		}
	}

	public String getResult() {
		return result.toString();
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	public StringBuilder nextArgument() {
		return result.append(",");
	}

	@Nonnull
	public StringBuilder nextConstraint() {
		return firstConstraint ? result : result.append(",");
	}

	/**
	 * Prints new line, but only if indentation is defined.
	 */
	@Nonnull
	private String newLine() {
		return indent == null ? "" : "\n" + fixedIndent;
	}

	/**
	 * Inserts indentation in to the string builder `repeatCount` times.
	 */
	private void indent(@Nullable String indent, int repeatCount) {
		if (indent != null) {
			result.append(indent.repeat(Math.max(0, repeatCount)));
		}
	}

	private void printContainer(ConstraintContainer<?> constraint) {
		if (constraint.getChildren().length == 0 && constraint.getAdditionalChildren().length == 0) {
			printLeaf(constraint);
			return;
		}

		level++;
		if (constraint.isApplicable()) {
			final Constraint<?>[] children = constraint.getChildren();
			final int childrenLength = children.length;

			final Constraint<?>[] additionalChildren = constraint.getAdditionalChildren();
			final int additionalChildrenLength = additionalChildren.length;

			final Serializable[] arguments = constraint.getArguments();
			final int argumentsLength = arguments.length;

			// print arguments
			for (int i = 0; i < argumentsLength; i++) {
				final Serializable argument = arguments[i];
				result.append(newLine());
				indent(indent, level);
				result.append(formatValue(argument));

				if (i + 1 < childrenLength || additionalChildrenLength > 0 || childrenLength > 0) {
					nextArgument();
				}
			}

			// print additional children
			for (int i = 0; i < additionalChildren.length; i++) {
				final Constraint<?> additionalChild = additionalChildren[i];
				additionalChild.accept(this);
				if (i + 1 < additionalChildren.length || childrenLength > 0) {
					nextConstraint();
				}
			}

			// print children
			for (int i = 0; i < childrenLength; i++) {
				final Constraint<?> child = children[i];
				child.accept(this);
				if (i + 1 < childrenLength) {
					nextConstraint();
				}
			}
		}
		level--;
		result.append(newLine());
		indent(indent, level);
		result.append(ARG_CLOSING);
	}

	private void printLeaf(Constraint<?> constraint) {
		final Serializable[] arguments = constraint.getArguments();
		final StringBuilder argumentString = new StringBuilder();
		for (int i = 0; i < arguments.length; i++) {
			final Serializable argument = arguments[i];
			argumentString.append(formatValue(argument));
			if (i + 1 < arguments.length) {
				argumentString.append(", ");
			}
		}
		if (indent == null || argumentString.length() < ofNullable(fixedIndent).map(String::length).orElse(0) + indent.length() * level + 60) {
			result.append(argumentString);
			result.append(ARG_CLOSING);
		} else {
			for (int i = 0; i < arguments.length; i++) {
				final Serializable argument = arguments[i];
				result.append(newLine());
				indent(indent, level + 1);
				result.append(formatValue(argument));
				if (i + 1 < arguments.length) {
					result.append(", ");
				}
			}
			result.append(newLine());
			indent(indent, level);
			result.append(ARG_CLOSING);
		}
	}

	private String formatValue(Object value) {
		if (value instanceof String string) {
			return "\"" + string + "\"";
		} else if (value instanceof Character character) {
			return "'" + character + "'";
		} else if (value instanceof BigDecimal bigDecimal) {
			return "new BigDecimal(\"" + bigDecimal.toPlainString() + "\")";
		} else if (value instanceof Number number) {
			return number.toString();
		} else if (value instanceof Boolean bool) {
			return bool.toString();
		} else if (value instanceof Enum theEnum) {
			return theEnum.name();
		} else if (value instanceof Range<?> range) {
			if (range.getPreciseFrom() != null && range.getPreciseTo() != null) {
				if (value instanceof ByteNumberRange) {
					return "ByteNumberRange.between(" + formatValue(range.getPreciseFrom()) + ", " + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof ShortNumberRange) {
					return "ShortNumberRange.between(" + formatValue(range.getPreciseFrom()) + ", " + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof IntegerNumberRange) {
					return "IntegerNumberRange.between(" + formatValue(range.getPreciseFrom()) + ", " + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof LongNumberRange) {
					return "LongNumberRange.between(" + formatValue(range.getPreciseFrom()) + ", " + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof BigDecimalNumberRange) {
					return "BigDecimalNumberRange.between(" + formatValue(range.getPreciseFrom()) + ", " + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof DateTimeRange) {
					return "DateTimeRange.between(" + formatValue(range.getPreciseFrom()) + ", " + formatValue(range.getPreciseFrom()) + ")";
				} else {
					throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName() + " (`" + value + "`)");
				}
			} else if (range.getPreciseFrom() == null) {
				if (value instanceof ByteNumberRange) {
					return "ByteNumberRange.to(" + formatValue(range.getPreciseTo()) + ")";
				} else if (value instanceof ShortNumberRange) {
					return "ShortNumberRange.to(" + formatValue(range.getPreciseTo()) + ")";
				} else if (value instanceof IntegerNumberRange) {
					return "IntegerNumberRange.to(" + formatValue(range.getPreciseTo()) + ")";
				} else if (value instanceof LongNumberRange) {
					return "LongNumberRange.to(" + formatValue(range.getPreciseTo()) + ")";
				} else if (value instanceof BigDecimalNumberRange) {
					return "BigDecimalNumberRange.to(" + formatValue(range.getPreciseTo()) + ")";
				} else if (value instanceof DateTimeRange) {
					return "DateTimeRange.until(" + formatValue(range.getPreciseTo()) + ")";
				} else {
					throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName() + " (`" + value + "`)");
				}
			} else {
				if (value instanceof ByteNumberRange) {
					return "ByteNumberRange.from(" + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof ShortNumberRange) {
					return "ShortNumberRange.from(" + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof IntegerNumberRange) {
					return "IntegerNumberRange.from(" + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof LongNumberRange) {
					return "LongNumberRange.from(" + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof BigDecimalNumberRange) {
					return "BigDecimalNumberRange.from(" + formatValue(range.getPreciseFrom()) + ")";
				} else if (value instanceof DateTimeRange) {
					return "DateTimeRange.since(" + formatValue(range.getPreciseFrom()) + ")";
				} else {
					throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName() + " (`" + value + "`)");
				}
			}
		} else if (value instanceof OffsetDateTime offsetDateTime) {
			return "OffsetDateTime.parse(\"" + offsetDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\", DateTimeFormatter.ISO_OFFSET_DATE_TIME)";
		} else if (value instanceof LocalDateTime localDateTime) {
			return "LocalDateTime.parse(\"" + localDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\", DateTimeFormatter.ISO_LOCAL_DATE_TIME)";
		} else if (value instanceof LocalDate localDate) {
			return "LocalDate.parse(\"" + localDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "\", DateTimeFormatter.ISO_LOCAL_DATE)";
		} else if (value instanceof LocalTime localTime) {
			return "LocalTime.parse(\"" + localTime.format(DateTimeFormatter.ISO_LOCAL_TIME) + "\", DateTimeFormatter.ISO_LOCAL_TIME)";
		} else if (value instanceof Locale locale) {
			return "Locale.forLanguageTag(\"" + locale.toLanguageTag() + "\")";
		} else if (value instanceof Currency currency) {
			return "Currency.getInstance(\"" + currency.getCurrencyCode() + "\")";
		} else if (value == null) {
			return "null";
		} else {
			throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName() + " (`" + value + "`)");
		}
	}

}