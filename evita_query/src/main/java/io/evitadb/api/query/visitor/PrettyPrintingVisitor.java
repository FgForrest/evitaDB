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

package io.evitadb.api.query.visitor;

import io.evitadb.api.query.BaseConstraint;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintContainerWithSuffix;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.Query;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static io.evitadb.api.query.Constraint.ARG_CLOSING;
import static io.evitadb.api.query.Constraint.ARG_OPENING;
import static java.util.Optional.ofNullable;

/**
 * This visitor can pretty print {@link io.evitadb.api.query.Query} constraints so that the output format is easily
 * readable to humans.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class PrettyPrintingVisitor implements ConstraintVisitor {
	/**
	 * Contains the printed form of the input constraint.
	 */
	private final StringBuilder result = new StringBuilder();
	/**
	 * Contains the parameters extracted from the original query in the correct order as they're used in {@link #result}.
	 * Non-null only when {@link #extractParameters} is TRUE.
	 */
	private final LinkedList<Serializable> parameters;
	/**
	 * Contains string used for new line indentation.
	 */
	private final String indent;
	/**
	 * When TRUE parameters are not printed directly to the {@link #result}, but are extracted to {@link #parameters}
	 * and the `?` sign is used as placeholder instead of them.
	 */
	private final boolean extractParameters;
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
	 * @return DTO that contains both formatted string of query and extracted list of parameters
	 */
	@Nonnull
	public static StringWithParameters toStringWithParameterExtraction(@Nonnull Query query) {
		return toStringWithParameterExtraction(query, null);
	}

	/**
	 * Converts `query` to formatted {@link String} and extracts all used parameters into separate list.
	 * The parameters in the query are replaced with placeholder `?` and the order of the placeholder is the same
	 * as position of the parameter in the result parameter list.
	 *
	 * @param query  input query
	 * @param indent string used for new line indentation
	 * @return DTO that contains both formatted string of query and extracted list of parameters
	 */
	@Nonnull
	public static StringWithParameters toStringWithParameterExtraction(@Nonnull Query query, @Nullable String indent) {
		final PrettyPrintingVisitor visitor = new PrettyPrintingVisitor(indent, true);
		visitor.traverse(query);
		return visitor.getResultWithExtractedParameters();
	}

	/**
	 * Converts `query` to formatted {@link String} and extracts all used parameters into separate list.
	 * The parameters in the query are replaced with placeholder `?` and the order of the placeholder is the same
	 * as position of the parameter in the result parameter list.
	 *
	 * @param indent     string used for new line indentation
	 * @param constraint input query
	 * @return DTO that contains both formatted string of query and extracted list of parameters
	 */
	@Nonnull
	public static StringWithParameters toStringWithParameterExtraction(@Nullable String indent, @Nonnull Constraint<?>... constraint) {
		final PrettyPrintingVisitor visitor = new PrettyPrintingVisitor(indent, true);
		for (Constraint<?> theConstraint : constraint) {
			theConstraint.accept(visitor);
		}
		return visitor.getResultWithExtractedParameters();
	}

	/**
	 * Converts `constraint` to formatted {@link String} and extracts all used parameters into separate list.
	 * The parameters in the query are replaced with placeholder `?` and the order of the placeholder is the same
	 * as position of the parameter in the result parameter list. Tabulator sign `\t` is used as indentation string.
	 *
	 * @param constraint input query
	 * @return DTO that contains both formatted string of query and extracted list of parameters
	 */
	public static StringWithParameters toStringWithParameterExtraction(@Nonnull Constraint<?>... constraint) {
		final PrettyPrintingVisitor visitor = new PrettyPrintingVisitor(null, true);
		for (Constraint<?> theConstraint : constraint) {
			visitor.nextConstraint();
			theConstraint.accept(visitor);
		}
		return visitor.getResultWithExtractedParameters();
	}

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
		final PrettyPrintingVisitor visitor = new PrettyPrintingVisitor(indent);
		visitor.traverse(query);
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
		final PrettyPrintingVisitor visitor = new PrettyPrintingVisitor(indent);
		constraint.accept(visitor);
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
		final PrettyPrintingVisitor visitor = new PrettyPrintingVisitor(null);
		constraint.accept(visitor);
		return visitor.getResult();
	}

	private PrettyPrintingVisitor(@Nullable String indent) {
		this.level = 0;
		this.indent = indent;
		this.extractParameters = false;
		this.parameters = null;
	}

	private PrettyPrintingVisitor(@Nullable String indent, boolean extractParameters) {
		this.level = 0;
		this.indent = indent;
		this.extractParameters = extractParameters;
		this.parameters = new LinkedList<>();
	}

	/**
	 * Traverses and prints the entire query.
	 */
	public void traverse(@Nonnull Query query) {
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

	/*
		PRIVATE METHODS
	 */

	public String getResult() {
		return result.toString();
	}

	public StringWithParameters getResultWithExtractedParameters() {
		return new StringWithParameters(
			result.toString(),
			parameters == null ? Collections.emptyList() : Collections.unmodifiableList(parameters)
		);
	}

	/**
	 * Prints new line, but only if indentation is defined.
	 */
	@Nonnull
	private String newLine() {
		return indent == null ? "" : "\n";
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
		if (constraint.getExplicitChildren().length == 0 && constraint.getExplicitAdditionalChildren().length == 0) {
			printLeaf(constraint);
			return;
		}

		level++;

		final Constraint<?>[] children = constraint.getChildren();
		final int childrenLength = children.length;

		final Constraint<?>[] additionalChildren = constraint.getAdditionalChildren();
		final int additionalChildrenLength = additionalChildren.length;

		final Serializable[] arguments = constraint.getArguments();
		final int argumentsLength = arguments.length;

		// print arguments
		for (int i = 0; i < argumentsLength; i++) {
			final Serializable argument = arguments[i];

			if (constraint instanceof ConstraintWithSuffix cws && cws.isArgumentImplicitForSuffix(argument)) {
				continue;
			}

			result.append(newLine());
			indent(indent, level);
			if (extractParameters) {
				result.append('?');
				ofNullable(parameters).ifPresent(it -> it.add(argument));
			} else {
				result.append(BaseConstraint.convertToString(argument));
			}
			if (i + 1 < childrenLength || additionalChildrenLength > 0 || childrenLength > 0) {
				nextArgument();
			}
		}

		// print additional children
		for (int i = 0; i < additionalChildren.length; i++) {
			final Constraint<?> additionalChild = additionalChildren[i];

			if (constraint instanceof ConstraintContainerWithSuffix ccws && ccws.isAdditionalChildImplicitForSuffix(additionalChild)) {
				continue;
			}

			additionalChild.accept(this);
			if (i + 1 < additionalChildren.length || childrenLength > 0) {
				nextConstraint();
			}
		}

		// print children
		for (int i = 0; i < childrenLength; i++) {
			final Constraint<?> child = children[i];

			if (constraint instanceof ConstraintContainerWithSuffix ccws && ccws.isChildImplicitForSuffix(child)) {
				continue;
			}

			child.accept(this);
			if (i + 1 < childrenLength) {
				nextConstraint();
			}
		}

		level--;
		result.append(newLine());
		indent(indent, level);
		result.append(ARG_CLOSING);
	}

	@Nonnull
	public StringBuilder nextArgument() {
		return result.append(",");
	}

	@Nonnull
	public StringBuilder nextConstraint() {
		return firstConstraint ? result : result.append(",");
	}

	private void printLeaf(Constraint<?> constraint) {
		final Serializable[] arguments = constraint.getArguments();
		for (int i = 0; i < arguments.length; i++) {
			final Serializable argument = arguments[i];

			if (constraint instanceof ConstraintWithSuffix cws && cws.isArgumentImplicitForSuffix(argument)) {
				continue;
			}

			if (extractParameters) {
				result.append('?');
				ofNullable(parameters).ifPresent(it -> it.add(argument));
			} else {
				result.append(BaseConstraint.convertToString(argument));
			}
			if (i + 1 < arguments.length) {
				result.append(", ");
			}
		}
		result.append(ARG_CLOSING);
	}

	/**
	 * DTO for passing result of the pretty printing visitor.
	 *
	 * @param query formatted query with constraint / query `?` instead of parameter values
	 * @param parameters parameters extracted from the original query in the correct order as they're used in string
	 */
	public record StringWithParameters(
		@Nonnull String query,
		@Nonnull List<Serializable> parameters
	) {
	}

}