/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.expression.query;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.bool.*;
import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.coalesce.SpreadNullCoalesceOperator;
import io.evitadb.api.query.expression.function.FunctionOperator;
import io.evitadb.api.query.expression.numeric.AdditionOperator;
import io.evitadb.api.query.expression.numeric.DivisionOperator;
import io.evitadb.api.query.expression.numeric.ModuloOperator;
import io.evitadb.api.query.expression.numeric.MultiplicationOperator;
import io.evitadb.api.query.expression.numeric.NegativeOperator;
import io.evitadb.api.query.expression.numeric.PositiveOperator;
import io.evitadb.api.query.expression.numeric.SubtractionOperator;
import io.evitadb.api.query.expression.object.ElementAccessStep;
import io.evitadb.api.query.expression.object.NullSafeAccessStep;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.ObjectAccessStep;
import io.evitadb.api.query.expression.object.PropertyAccessStep;
import io.evitadb.api.query.expression.object.SpreadAccessStep;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.api.query.expression.visitor.AccessedDataFinder;
import io.evitadb.api.query.expression.visitor.BooleanExpressionChecker;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;

/**
 * Translates a `facetedPartially` expression (parsed expression tree) into an evitaDB `FilterBy`
 * constraint tree at schema load time. The translator produces a reusable `FilterBy` template stored
 * in the `ExpressionIndexTrigger`, enabling index-based evaluation of cross-entity triggers without
 * per-entity expression interpretation.
 *
 * The translator is invoked **once per expression at schema load time** (not on the hot path).
 * Its output — a `FilterBy` constraint tree — is cached in the trigger and reused for every
 * cross-entity trigger evaluation.
 *
 * ## Supported translations
 *
 * - Entity attribute comparisons: `$entity.attributes['x'] op v` to `attributeEquals`, etc.
 * - Reference attribute comparisons: wrapped in `referenceHaving("refName", ...)`
 * - Group entity attribute comparisons: wrapped in `referenceHaving("refName", groupHaving(...))`
 * - Referenced entity attribute comparisons: wrapped in `referenceHaving("refName", entityHaving(...))`
 * - Boolean operators `&&`, `||`, `!` to `and()`, `or()`, `not()` with flattening
 * - Parent existence checks to `hierarchyWithinRootSelf()`
 * - Attribute null checks to `attributeIsNull()` / `attributeIsNotNull()`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see NonTranslatableExpressionException for rejection of non-translatable expressions
 */
public class ExpressionToQueryTranslator implements ExpressionNodeVisitor {

	/**
	 * The name of the reference carrying the expression — used for `referenceHaving` wrapping.
	 */
	@Nonnull private final String referenceName;
	/**
	 * The result of translating the current node — set by `visit()` for the parent to read.
	 */
	@Nullable private FilterConstraint result;

	/**
	 * Translates a `facetedPartially` expression into an evitaDB `FilterBy` constraint tree.
	 * Called at schema load time.
	 *
	 * @param expression    the parsed expression AST
	 * @param referenceName the name of the reference carrying the expression
	 * @return the `FilterBy` constraint tree (template — not yet parameterized)
	 * @throws NonTranslatableExpressionException if the expression contains constructs
	 *                                            that cannot be mapped to `FilterBy` constraints
	 */
	@Nonnull
	public static FilterBy translate(@Nonnull Expression expression, @Nonnull String referenceName) {
		if (!BooleanExpressionChecker.isBooleanExpression(expression)) {
			throw new NonTranslatableExpressionException(
				"The expression is not a boolean expression. Only boolean expressions " +
					"(comparisons, logical operators) can be translated to FilterBy constraints."
			);
		}
		preValidatePaths(expression);
		final ExpressionToQueryTranslator translator = new ExpressionToQueryTranslator(referenceName);
		expression.accept(translator);
		return filterBy(translator.result);
	}

	/**
	 * Pre-validates the expression by analyzing all accessed data paths via {@link AccessedDataFinder}.
	 * If any path contains a {@link VariablePathItem} at a position that represents an attribute name
	 * (i.e., immediately after an "attributes" identifier), the expression is rejected early with a
	 * clear error — before the AST traversal begins.
	 *
	 * @param expression the parsed expression AST to validate
	 * @throws NonTranslatableExpressionException if a dynamic attribute path is detected
	 */
	private static void preValidatePaths(@Nonnull Expression expression) {
		final List<List<PathItem>> paths = AccessedDataFinder.findAccessedPaths(expression);
		for (final List<PathItem> path : paths) {
			for (int i = 0; i < path.size() - 1; i++) {
				final PathItem current = path.get(i);
				final PathItem next = path.get(i + 1);
				// check if "attributes" or "localizedAttributes" identifier is followed by a
				// VariablePathItem (dynamic key)
				if (
					current instanceof IdentifierPathItem identifier
						&& (EntityContractAccessor.ATTRIBUTES_PROPERTY.equals(identifier.value())
						|| EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY.equals(identifier.value()))
						&& next instanceof VariablePathItem variable
				) {
					throw new NonTranslatableExpressionException(
						"Dynamic attribute path detected: attribute name is resolved from " +
							"variable `$" + variable.value() + "` instead of a string literal. " +
							"Only static attribute paths (e.g., `attributes['myAttribute']`) can " +
							"be translated to FilterBy constraints."
					);
				}
			}
		}
	}

	/**
	 * Unwraps {@link Expression} and {@link NestedOperator} wrappers, returning the inner
	 * expression node. Used both during direct visitation and during boolean operator flattening
	 * to see through parenthesized sub-expressions.
	 */
	@Nonnull
	private static ExpressionNode unwrap(@Nonnull ExpressionNode node) {
		ExpressionNode current = node;
		while (current instanceof Expression || current instanceof NestedOperator) {
			final ExpressionNode[] children = current.getChildren();
			Assert.isPremiseValid(
				children != null && children.length == 1,
				"Unsupported children for node `" + current.getClass().getSimpleName() + "`."
			);
			current = children[0];
		}
		return current;
	}

	/**
	 * Creates the appropriate attribute comparison constraint for the given comparison node type.
	 *
	 * When operands are reversed (constant on left, path on right), comparison direction is flipped:
	 * `100 < $entity.attributes['price']` becomes `attributeGreaterThan("price", 100)`.
	 */
	@Nonnull
	private static FilterConstraint createAttributeConstraint(
		@Nonnull ExpressionNode comparisonNode,
		@Nonnull String attributeName,
		@Nonnull Serializable value,
		boolean reversed
	) {
		if (comparisonNode instanceof EqualsOperator) {
			return attributeEquals(attributeName, value);
		} else if (comparisonNode instanceof NotEqualsOperator) {
			return not(attributeEquals(attributeName, value));
		} else if (comparisonNode instanceof GreaterThanOperator) {
			return reversed
				? attributeLessThan(attributeName, value)
				: attributeGreaterThan(attributeName, value);
		} else if (comparisonNode instanceof GreaterThanEqualsOperator) {
			return reversed
				? attributeLessThanEquals(attributeName, value)
				: attributeGreaterThanEquals(attributeName, value);
		} else if (comparisonNode instanceof LesserThanOperator) {
			return reversed
				? attributeGreaterThan(attributeName, value)
				: attributeLessThan(attributeName, value);
		} else if (comparisonNode instanceof LesserThanEqualsOperator) {
			return reversed
				? attributeGreaterThanEquals(attributeName, value)
				: attributeLessThanEquals(attributeName, value);
		} else {
			throw new NonTranslatableExpressionException(
				"Unsupported comparison operator `" + comparisonNode.getClass().getSimpleName() +
					"` cannot be translated to a FilterBy attribute constraint."
			);
		}
	}

	/**
	 * Creates the appropriate null-check constraint for a comparison with a `null` literal.
	 * Only equality (`==`) and inequality (`!=`) comparisons are supported with null — ordered
	 * comparisons (`>`, `>=`, `<`, `<=`) are rejected because they have no meaningful semantics
	 * with null values.
	 *
	 * @param comparisonNode the comparison operator node
	 * @param attributeName  the attribute name extracted from the data path
	 * @return `attributeIsNull` for `==`, `attributeIsNotNull` for `!=`
	 * @throws NonTranslatableExpressionException for ordered comparisons with null
	 */
	@Nonnull
	private static FilterConstraint createNullAttributeConstraint(
		@Nonnull ExpressionNode comparisonNode,
		@Nonnull String attributeName
	) {
		if (comparisonNode instanceof EqualsOperator) {
			return attributeIsNull(attributeName);
		} else if (comparisonNode instanceof NotEqualsOperator) {
			return attributeIsNotNull(attributeName);
		} else {
			throw new NonTranslatableExpressionException(
				"Ordered comparison (`" + comparisonNode.getClass().getSimpleName() +
					"`) with null literal is not supported. Only equality (`==`) and " +
					"inequality (`!=`) comparisons can be used with null values."
			);
		}
	}

	/**
	 * Classifies an {@link ObjectAccessOperator} by walking its access chain to determine the data path
	 * type and extract the attribute name.
	 *
	 * Supported path patterns:
	 * - `$entity.attributes['x']` / `$entity.localizedAttributes['x']` -> `ENTITY_ATTRIBUTE`
	 *
	 * @param accessOperator the object access operator to classify
	 * @return the classified data path
	 * @throws NonTranslatableExpressionException if the path pattern is not recognized
	 */
	@Nonnull
	private static DataPath classifyPath(@Nonnull ObjectAccessOperator accessOperator) {
		final ObjectAccessStep step = accessOperator.getAccessChain();
		if (!(step instanceof PropertyAccessStep firstProperty)) {
			throw new NonTranslatableExpressionException(
				"Expected a property access step as the first step in the data path, but found `" +
					step.getClass().getSimpleName() + "`."
			);
		}

		final String variableName = extractVariableName(accessOperator);
		final String firstPropertyName = firstProperty.getPropertyIdentifier();

		if (EntityContractAccessor.ENTITY_VARIABLE_NAME.equals(variableName)) {
			// $entity context — properties from EntityContractAccessor
			if (
				EntityContractAccessor.ATTRIBUTES_PROPERTY.equals(firstPropertyName)
					|| EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY.equals(firstPropertyName)
			) {
				final String attributeName = extractAttributeName(firstProperty.getNext());
				return new DataPath(PathType.ENTITY_ATTRIBUTE, attributeName, null);
			} else if (
				EntityContractAccessor.ASSOCIATED_DATA_PROPERTY.equals(firstPropertyName)
					|| EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY.equals(firstPropertyName)
			) {
				throw new NonTranslatableExpressionException(
					"Associated data path `$" + EntityContractAccessor.ENTITY_VARIABLE_NAME + "." +
						firstPropertyName +
						"[...]` cannot be translated to a FilterBy constraint. " +
						"There is no FilterBy equivalent for associated data access."
				);
			}
		} else if (ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variableName)) {
			// $reference context — properties from ReferenceContractAccessor
			switch (firstPropertyName) {
				case ReferenceContractAccessor.ATTRIBUTES_PROPERTY,
				     ReferenceContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY -> {
					final String attributeName = extractAttributeName(firstProperty.getNext());
					return new DataPath(PathType.REFERENCE_ATTRIBUTE, attributeName, null);
				}
				case ReferenceContractAccessor.GROUP_ENTITY_PROPERTY -> {
					return extractEntityAttributePath(firstProperty, PathType.GROUP_ENTITY_ATTRIBUTE);
				}
				case ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY -> {
					return extractEntityAttributePath(firstProperty, PathType.REFERENCED_ENTITY_ATTRIBUTE);
				}
				case ReferenceContractAccessor.REFERENCED_PRIMARY_KEY_PROPERTY -> {
					throw new NonTranslatableExpressionException(
						"Reference primary key comparison `$" +
							ReferenceContractAccessor.REFERENCE_VARIABLE_NAME + "." +
							ReferenceContractAccessor.REFERENCED_PRIMARY_KEY_PROPERTY +
							"` cannot be translated to a FilterBy constraint. Reference PK scoping " +
							"is handled at trigger time by the executor, not via FilterBy translation."
					);
				}
			}
		} else {
			throw new NonTranslatableExpressionException(
				"Unsupported variable `$" + variableName + "` in data access path. " +
					"Expected `$" + EntityContractAccessor.ENTITY_VARIABLE_NAME +
					"` or `$" + ReferenceContractAccessor.REFERENCE_VARIABLE_NAME + "`."
			);
		}

		throw new NonTranslatableExpressionException(
			"Unsupported data path starting with property `" + firstPropertyName + "`. " +
				"For `$" + EntityContractAccessor.ENTITY_VARIABLE_NAME + "`, expected `" +
				EntityContractAccessor.ATTRIBUTES_PROPERTY + "`, `" +
				EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY + "`, or `" +
				EntityContractAccessor.ASSOCIATED_DATA_PROPERTY + "`. " +
				"For `$" + ReferenceContractAccessor.REFERENCE_VARIABLE_NAME + "`, expected `" +
				ReferenceContractAccessor.ATTRIBUTES_PROPERTY + "`, `" +
				ReferenceContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY + "`, `" +
				ReferenceContractAccessor.GROUP_ENTITY_PROPERTY + "`, `" +
				ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY + "`, or `" +
				ReferenceContractAccessor.REFERENCED_PRIMARY_KEY_PROPERTY + "`."
		);
	}

	/**
	 * Extracts the attribute name from a sub-entity path (e.g., `.groupEntity?.attributes['x']`
	 * or `.referencedEntity.localizedAttributes['x']`). After the first property, skips an optional
	 * null-safe step, then checks the next property:
	 *
	 * - `attributes`/`localizedAttributes` -> entity-attribute dependency (existing behavior)
	 * - `references` -> reference-attribute dependency: extracts reference name and attribute name
	 *   from either a non-spread chain or a spread mapping expression
	 *
	 * @param firstProperty the property access step preceding the entity navigation
	 * @param pathType      the base path type (`REFERENCED_ENTITY_ATTRIBUTE` or `GROUP_ENTITY_ATTRIBUTE`)
	 * @return the classified data path with the extracted attribute name and optional reference name
	 * @throws NonTranslatableExpressionException if the path pattern is not recognized
	 */
	@Nonnull
	private static DataPath extractEntityAttributePath(
		@Nonnull PropertyAccessStep firstProperty,
		@Nonnull PathType pathType
	) {
		final ObjectAccessStep afterNavigation = skipNullSafe(firstProperty.getNext());
		if (!(afterNavigation instanceof PropertyAccessStep propertyStep)) {
			throw new NonTranslatableExpressionException(
				"Expected `." + EntityContractAccessor.ATTRIBUTES_PROPERTY + "` or `." +
					EntityContractAccessor.REFERENCES_PROPERTY + "` after `." +
					firstProperty.getPropertyIdentifier() + "`, but found `" +
					(afterNavigation != null ? afterNavigation.getClass().getSimpleName() : "end of chain")
					+ "`."
			);
		}
		final String propertyName = propertyStep.getPropertyIdentifier();
		if (isAttributesProperty(propertyName)) {
			// entity-attribute dependency: .referencedEntity.attributes['x']
			final String attributeName = extractAttributeName(propertyStep.getNext());
			return new DataPath(pathType, attributeName, null);
		} else if (EntityContractAccessor.REFERENCES_PROPERTY.equals(propertyName)) {
			// reference-attribute dependency: .referencedEntity.references['r'].attributes['x']
			return extractReferenceAttributePath(propertyStep, pathType);
		} else {
			throw new NonTranslatableExpressionException(
				"Expected `." + EntityContractAccessor.ATTRIBUTES_PROPERTY + "`, `." +
					EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY + "`, or `." +
					EntityContractAccessor.REFERENCES_PROPERTY + "` after `." +
					firstProperty.getPropertyIdentifier() + "`, but found `." + propertyName + "`."
			);
		}
	}

	/**
	 * Extracts the reference name and attribute name from a `.references['r']` chain on a target entity.
	 * Supports both non-spread variant (`.references['r'].attributes['A']`) and spread variant
	 * (`.references['r']*.attributes['A']`).
	 *
	 * @param referencesStep the `PropertyAccessStep` for `references`
	 * @param basePathType   the base path type (`REFERENCED_ENTITY_ATTRIBUTE` or `GROUP_ENTITY_ATTRIBUTE`)
	 * @return the classified data path with reference-attribute path type
	 */
	@Nonnull
	private static DataPath extractReferenceAttributePath(
		@Nonnull PropertyAccessStep referencesStep,
		@Nonnull PathType basePathType
	) {
		// extract reference name from ElementAccessStep: references['r']
		final ObjectAccessStep elementStep = referencesStep.getNext();
		if (!(elementStep instanceof ElementAccessStep refElement)) {
			throw new NonTranslatableExpressionException(
				"Expected element access (e.g., ['referenceName']) after `." +
					EntityContractAccessor.REFERENCES_PROPERTY + "`, but found `" +
					(elementStep != null ? elementStep.getClass().getSimpleName() : "end of chain") + "`."
			);
		}
		if (!(refElement.getElementIdentifierOperand() instanceof ConstantOperand refNameConst)) {
			throw new NonTranslatableExpressionException(
				"Dynamic reference name in `." + EntityContractAccessor.REFERENCES_PROPERTY +
					"[...]` cannot be translated to a FilterBy constraint. " +
					"The reference name must be a string literal."
			);
		}
		final Serializable refNameValue = refNameConst.getValue();
		if (refNameValue == null) {
			throw new NonTranslatableExpressionException(
				"Null reference name in `." + EntityContractAccessor.REFERENCES_PROPERTY +
					"[null]` cannot be translated to a FilterBy constraint. " +
					"The reference name must be a non-null string literal."
			);
		}
		final String referenceName = refNameValue.toString();

		// after the element access, check for spread or direct property chain
		final ObjectAccessStep afterElement = refElement.getNext();
		final String attributeName;

		if (afterElement instanceof SpreadAccessStep spreadStep) {
			// spread variant: references['r']*.attributes['A']
			attributeName = extractAttributeFromSpreadMapping(spreadStep);
		} else {
			// non-spread variant: references['r'].attributes['A'] (with optional null-safe)
			final ObjectAccessStep afterNullSafe = skipNullSafe(afterElement);
			if (!(afterNullSafe instanceof PropertyAccessStep attrStep)) {
				throw new NonTranslatableExpressionException(
					"Expected `." + EntityContractAccessor.ATTRIBUTES_PROPERTY + "` after `." +
						EntityContractAccessor.REFERENCES_PROPERTY + "['" + referenceName +
						"']`, but found `" +
						(afterNullSafe != null ? afterNullSafe.getClass().getSimpleName() : "end of chain")
						+ "`."
				);
			}
			if (!isAttributesProperty(attrStep.getPropertyIdentifier())) {
				throw new NonTranslatableExpressionException(
					"Expected `." + EntityContractAccessor.ATTRIBUTES_PROPERTY + "` or `." +
						EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY + "` after `." +
						EntityContractAccessor.REFERENCES_PROPERTY + "['" + referenceName +
						"']`, but found `." + attrStep.getPropertyIdentifier() + "`."
				);
			}
			attributeName = extractAttributeName(attrStep.getNext());
		}

		// map base path type to reference-attribute variant
		final PathType mappedPathType = switch (basePathType) {
			case REFERENCED_ENTITY_ATTRIBUTE -> PathType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE;
			case GROUP_ENTITY_ATTRIBUTE -> PathType.GROUP_ENTITY_REFERENCE_ATTRIBUTE;
			default -> throw new NonTranslatableExpressionException(
				"Unexpected base path type `" + basePathType + "` for reference-attribute path."
			);
		};

		return new DataPath(mappedPathType, attributeName, referenceName);
	}

	/**
	 * Extracts the attribute name from a {@link SpreadAccessStep}'s mapping expression.
	 * The mapping expression must be an {@link ObjectAccessOperator} whose operand is
	 * `VariableOperand(this)` (`$`), with an access chain of
	 * `PropertyAccessStep("attributes"/"localizedAttributes") -> ElementAccessStep('A')`.
	 *
	 * @param spreadStep the spread access step
	 * @return the extracted attribute name
	 * @throws NonTranslatableExpressionException if the mapping expression is not a simple attribute access
	 */
	@Nonnull
	private static String extractAttributeFromSpreadMapping(@Nonnull SpreadAccessStep spreadStep) {
		final ExpressionNode mappingExpr = spreadStep.getMappingExpression();
		final ExpressionNode unwrapped = unwrap(mappingExpr);
		if (!(unwrapped instanceof ObjectAccessOperator mappingAccess)) {
			throw new NonTranslatableExpressionException(
				"Spread mapping expression must be a simple attribute access (e.g., " +
					"`$.attributes['name']`), but found `" + unwrapped.getClass().getSimpleName() + "`."
			);
		}

		// verify the operand is $ (this)
		final ExpressionNode[] mappingChildren = mappingAccess.getChildren();
		Assert.isPremiseValid(
			mappingChildren != null && mappingChildren.length == 1,
			"ObjectAccessOperator in spread mapping must have 1 child."
		);
		if (
			!(mappingChildren[0] instanceof VariableOperand thisVar) || !thisVar.isThis()
		) {
			throw new NonTranslatableExpressionException(
				"Spread mapping expression operand must be `$` (this), but found `" +
					mappingChildren[0].getClass().getSimpleName() + "`."
			);
		}

		// walk the access chain: PropertyAccessStep("attributes") -> ElementAccessStep('A')
		final ObjectAccessStep chainStep = mappingAccess.getAccessChain();
		if (!(chainStep instanceof PropertyAccessStep attrProp)) {
			throw new NonTranslatableExpressionException(
				"Spread mapping access chain must start with `." +
					EntityContractAccessor.ATTRIBUTES_PROPERTY + "`, but found `" +
					chainStep.getClass().getSimpleName() + "`."
			);
		}
		if (!isAttributesProperty(attrProp.getPropertyIdentifier())) {
			throw new NonTranslatableExpressionException(
				"Spread mapping access chain must start with `." +
					EntityContractAccessor.ATTRIBUTES_PROPERTY + "` or `." +
					EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY + "`, but found `." +
					attrProp.getPropertyIdentifier() + "`."
			);
		}
		return extractAttributeName(attrProp.getNext());
	}

	/**
	 * Checks whether the given property name refers to an attributes accessor.
	 *
	 * @param propertyName the property name to check
	 * @return `true` if the name matches `attributes` or `localizedAttributes`
	 */
	private static boolean isAttributesProperty(@Nonnull String propertyName) {
		return EntityContractAccessor.ATTRIBUTES_PROPERTY.equals(propertyName)
			|| EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY.equals(propertyName);
	}

	/**
	 * Extracts the attribute name from an {@link ElementAccessStep} (the `['x']` part). The element
	 * identifier must be a {@link ConstantOperand} with a `String` value.
	 *
	 * @param step the access step to extract the attribute name from (may be null if chain ended)
	 * @return the attribute name as a string
	 * @throws NonTranslatableExpressionException if the identifier is dynamic (variable)
	 */
	@Nonnull
	private static String extractAttributeName(@Nullable ObjectAccessStep step) {
		if (extractElementIdentifier(step) instanceof ConstantOperand constantOperand) {
			final Serializable value = constantOperand.getValue();
			if (value == null) {
				throw new NonTranslatableExpressionException(
					"Null attribute name cannot be translated to a FilterBy constraint. " +
						"The element access key must be a non-null string literal " +
						"(e.g., `['myAttribute']`)."
				);
			}
			return value.toString();
		} else {
			throw new NonTranslatableExpressionException(
				"Dynamic attribute path cannot be translated to a FilterBy constraint because the " +
					"attribute name is not a compile-time constant. The element access key must be a " +
					"string literal (e.g., `['myAttribute']`), not a variable or expression."
			);
		}
	}

	/**
	 * Extracts the element identifier operand from a given {@link ObjectAccessStep}, which must be
	 * an {@link ElementAccessStep}. Throws if the step is null or not the expected type.
	 *
	 * @param step an {@link ObjectAccessStep} representing a single step in an object access chain
	 *             (may be null if the chain ended prematurely)
	 * @return the extracted {@link ExpressionNode} that represents the element identifier operand
	 * @throws NonTranslatableExpressionException if the step is null or not an {@link ElementAccessStep}
	 */
	@Nonnull
	private static ExpressionNode extractElementIdentifier(@Nullable ObjectAccessStep step) {
		if (step == null) {
			throw new NonTranslatableExpressionException(
				"Expected an element access step (e.g., ['attributeName']) after `." +
					EntityContractAccessor.ATTRIBUTES_PROPERTY + "`, but the access chain ended."
			);
		}
		if (!(step instanceof ElementAccessStep elementAccess)) {
			throw new NonTranslatableExpressionException(
				"Expected an element access step (e.g., ['attributeName']) after `." +
					EntityContractAccessor.ATTRIBUTES_PROPERTY + "`, but found `" +
					step.getClass().getSimpleName() + "`."
			);
		}

		return elementAccess.getElementIdentifierOperand();
	}

	/**
	 * Extracts the variable name from the operand of an {@link ObjectAccessOperator}. The operand
	 * must be a {@link VariableOperand} with a non-null name (e.g., `$entity`, `$reference`).
	 */
	@Nonnull
	private static String extractVariableName(@Nonnull ObjectAccessOperator accessOperator) {
		final ExpressionNode[] children = accessOperator.getChildren();
		Assert.isPremiseValid(children != null && children.length == 1, "ObjectAccessOperator must have 1 child.");
		if (children[0] instanceof VariableOperand variable) {
			final String name = variable.getVariableName();
			if (name == null) {
				throw new NonTranslatableExpressionException(
					"Anonymous `this` variable cannot be used in FilterBy translation."
				);
			}
			return name;
		}
		throw new NonTranslatableExpressionException(
			"Expected a variable operand (e.g., $entity, $reference) but found `"
				+ children[0].getClass().getSimpleName() + "`."
		);
	}

	/**
	 * Skips a {@link NullSafeAccessStep} wrapper if present, returning the underlying step.
	 * This allows the chain walker to handle both `?.` and `.` syntax uniformly.
	 */
	@Nullable
	private static ObjectAccessStep skipNullSafe(@Nullable ObjectAccessStep step) {
		return step instanceof NullSafeAccessStep nullSafe ? nullSafe.getNext() : step;
	}

	/**
	 * Throws a {@link NonTranslatableExpressionException} for an arithmetic operator that cannot
	 * be translated to a FilterBy constraint.
	 *
	 * @param operatorName the human-readable name of the operator (e.g., "Addition")
	 * @param symbol       the operator symbol (e.g., "+")
	 */
	private static void rejectArithmeticOperator(
		@Nonnull String operatorName,
		@Nonnull String symbol
	) {
		throw new NonTranslatableExpressionException(
			operatorName + " operator (`" + symbol + "`) cannot be translated to a FilterBy constraint. " +
				"Arithmetic operations are not supported in FilterBy expressions."
		);
	}

	/**
	 * Post-processes a flat list of AND operands by merging multiple direct {@link ReferenceHaving}
	 * constraints with the same reference name into a single one. Non-{@link ReferenceHaving} operands
	 * and wrapped instances (e.g., inside `not()`) pass through unchanged.
	 *
	 * Merging is safe in AND context because `and(ref(a), ref(b))` is semantically equivalent
	 * to `ref(and(a, b))`. This does NOT apply to OR context.
	 */
	@Nonnull
	private static List<FilterConstraint> mergeReferenceHaving(@Nonnull List<FilterConstraint> operands) {
		// fast path: no merging needed if fewer than 2 ReferenceHaving
		int refHavingCount = 0;
		for (final FilterConstraint operand : operands) {
			if (operand instanceof ReferenceHaving) {
				refHavingCount++;
			}
		}
		if (refHavingCount <= 1) {
			return operands;
		}

		// group ReferenceHaving inner constraints by reference name, preserve insertion order
		final LinkedHashMap<String, List<FilterConstraint>> refGroups = createLinkedHashMap(4);
		final List<FilterConstraint> merged = new ArrayList<>(operands.size());

		for (final FilterConstraint operand : operands) {
			if (operand instanceof ReferenceHaving refHaving) {
				refGroups.computeIfAbsent(refHaving.getReferenceName(), k -> new ArrayList<>(4))
					.addAll(Arrays.asList(refHaving.getChildren()));
			} else {
				merged.add(operand);
			}
		}

		for (final Map.Entry<String, List<FilterConstraint>> entry : refGroups.entrySet()) {
			final List<FilterConstraint> innerConstraints = entry.getValue();
			if (innerConstraints.size() == 1) {
				merged.add(referenceHaving(entry.getKey(), innerConstraints.get(0)));
			} else {
				merged.add(referenceHaving(
					entry.getKey(),
					and(innerConstraints.toArray(FilterConstraint[]::new))
				));
			}
		}

		return merged;
	}

	/**
	 * Creates a new translator instance for the given reference name.
	 *
	 * @param referenceName the name of the reference carrying the expression
	 */
	private ExpressionToQueryTranslator(@Nonnull String referenceName) {
		this.referenceName = referenceName;
	}

	@Override
	public void visit(@Nonnull ExpressionNode node) {
		if (node instanceof Expression || node instanceof NestedOperator) {
			unwrap(node).accept(this);
		} else if (node instanceof ConjunctionOperator) {
			translateConjunction(node);
		} else if (node instanceof DisjunctionOperator) {
			translateDisjunction(node);
		} else if (node instanceof InverseOperator) {
			translateInverse(node);
		} else if (
			node instanceof EqualsOperator
				|| node instanceof NotEqualsOperator
				|| node instanceof GreaterThanOperator
				|| node instanceof GreaterThanEqualsOperator
				|| node instanceof LesserThanOperator
				|| node instanceof LesserThanEqualsOperator
		) {
			translateComparison(node);
		} else if (node instanceof XorOperator) {
			throw new NonTranslatableExpressionException(
				"XOR operator (`^`) has no FilterBy equivalent. " +
					"Consider rewriting as `(a || b) && !(a && b)`."
			);
		} else if (node instanceof AdditionOperator) {
			rejectArithmeticOperator("Addition", "+");
		} else if (node instanceof SubtractionOperator) {
			rejectArithmeticOperator("Subtraction", "-");
		} else if (node instanceof MultiplicationOperator) {
			rejectArithmeticOperator("Multiplication", "*");
		} else if (node instanceof DivisionOperator) {
			rejectArithmeticOperator("Division", "/");
		} else if (node instanceof ModuloOperator) {
			rejectArithmeticOperator("Modulo", "%");
		} else if (node instanceof NegativeOperator) {
			rejectArithmeticOperator("Numeric negation", "-x");
		} else if (node instanceof PositiveOperator) {
			rejectArithmeticOperator("Numeric positive", "+x");
		} else if (node instanceof FunctionOperator) {
			throw new NonTranslatableExpressionException(
				"Function calls cannot be translated to a FilterBy constraint. " +
					"Only comparisons and boolean operators are supported."
			);
		} else if (node instanceof NullCoalesceOperator) {
			throw new NonTranslatableExpressionException(
				"Null coalesce operator (`??`) cannot be translated to a FilterBy constraint."
			);
		} else if (node instanceof SpreadNullCoalesceOperator) {
			throw new NonTranslatableExpressionException(
				"Spread null coalesce operator cannot be translated to a FilterBy constraint."
			);
		} else {
			throw new NonTranslatableExpressionException(
				"Unsupported expression node type `" + node.getClass().getSimpleName() +
					"` cannot be translated to a FilterBy constraint."
			);
		}
	}

	/**
	 * Wraps the given attribute constraint based on the path type. For `ENTITY_ATTRIBUTE`, the constraint
	 * is returned as-is (no wrapper). Other path types add `referenceHaving`, `groupHaving`, etc.
	 */
	@Nonnull
	private FilterConstraint wrapForPathType(
		@Nonnull DataPath dataPath,
		@Nonnull FilterConstraint attributeConstraint
	) {
		return switch (dataPath.pathType()) {
			case ENTITY_ATTRIBUTE -> attributeConstraint;
			case REFERENCE_ATTRIBUTE -> referenceHaving(this.referenceName, attributeConstraint);
			case GROUP_ENTITY_ATTRIBUTE -> referenceHaving(this.referenceName, groupHaving(attributeConstraint));
			case REFERENCED_ENTITY_ATTRIBUTE ->
				referenceHaving(this.referenceName, entityHaving(attributeConstraint));
			case REFERENCED_ENTITY_REFERENCE_ATTRIBUTE -> referenceHaving(
				this.referenceName,
				entityHaving(referenceHaving(dataPath.referenceName(), attributeConstraint))
			);
			case GROUP_ENTITY_REFERENCE_ATTRIBUTE -> referenceHaving(
				this.referenceName,
				groupHaving(referenceHaving(dataPath.referenceName(), attributeConstraint))
			);
		};
	}

	/**
	 * Translates a unary inverse (`!`) operator into a `not(...)` filter constraint.
	 * The single child is translated recursively and then wrapped in {@code not()}.
	 */
	private void translateInverse(@Nonnull ExpressionNode node) {
		final ExpressionNode[] children = node.getChildren();
		Assert.isPremiseValid(children != null && children.length == 1, "Inverse must have 1 child.");
		children[0].accept(this);
		this.result = not(this.result);
	}

	/**
	 * Translates a conjunction (`&&`) into a flat `and(...)` constraint. Recursively collects all
	 * same-type operands to avoid nested `And` wrappers (e.g., `a && b && c` produces
	 * `and(a, b, c)` instead of `and(and(a, b), c)`). After flattening, merges multiple
	 * {@link ReferenceHaving} constraints with the same reference name into a single one.
	 */
	private void translateConjunction(@Nonnull ExpressionNode node) {
		final List<FilterConstraint> operands = new ArrayList<>(4);
		collectBinaryBooleanOperands(node, ConjunctionOperator.class, operands);
		final List<FilterConstraint> merged = mergeReferenceHaving(operands);
		this.result = merged.size() == 1
			? merged.get(0)
			: and(merged.toArray(FilterConstraint[]::new));
	}

	/**
	 * Translates a disjunction (`||`) into a flat `or(...)` constraint. Recursively collects all
	 * same-type operands to avoid nested `Or` wrappers (e.g., `a || b || c` produces
	 * `or(a, b, c)` instead of `or(or(a, b), c)`).
	 */
	private void translateDisjunction(@Nonnull ExpressionNode node) {
		final List<FilterConstraint> operands = new ArrayList<>(4);
		collectBinaryBooleanOperands(node, DisjunctionOperator.class, operands);
		this.result = or(operands.toArray(FilterConstraint[]::new));
	}

	/**
	 * Recursively collects operands of a binary boolean operator (conjunction or disjunction),
	 * unwrapping {@link Expression}/{@link NestedOperator} wrappers and flattening consecutive
	 * same-type operators into a single list. Non-matching nodes are translated via the visitor
	 * and added as leaf constraints.
	 */
	private void collectBinaryBooleanOperands(
		@Nonnull ExpressionNode node,
		@Nonnull Class<? extends ExpressionNode> operatorType,
		@Nonnull List<FilterConstraint> collector
	) {
		final ExpressionNode unwrapped = unwrap(node);
		if (operatorType.isInstance(unwrapped)) {
			final ExpressionNode[] children = unwrapped.getChildren();
			Assert.isPremiseValid(
				children != null && children.length == 2,
				operatorType.getSimpleName() + " must have 2 children."
			);
			collectBinaryBooleanOperands(children[0], operatorType, collector);
			collectBinaryBooleanOperands(children[1], operatorType, collector);
		} else {
			unwrapped.accept(this);
			collector.add(this.result);
		}
	}

	/**
	 * Translates a binary comparison operator node into a {@link FilterConstraint}. Extracts the data
	 * path ({@link ObjectAccessOperator}) and the literal value ({@link ConstantOperand}) from the two
	 * operands, determines the path type, and produces the corresponding constraint.
	 *
	 * Operands are first unwrapped from any {@link Expression}/{@link NestedOperator} wrappers (e.g.,
	 * parenthesized sub-expressions) before type classification.
	 *
	 * Handles either operand order: path on left/constant on right, or constant on left/path on right.
	 * When operands are reversed, comparison semantics are flipped (e.g., `100 < price` becomes
	 * `price > 100`).
	 */
	private void translateComparison(@Nonnull ExpressionNode comparisonNode) {
		final ExpressionNode[] children = comparisonNode.getChildren();
		Assert.isPremiseValid(children != null && children.length == 2, "Comparison must have 2 children.");
		// unwrap parenthesized/nested wrappers so that (path) == value works the same as path == value
		final ExpressionNode left = unwrap(children[0]);
		final ExpressionNode right = unwrap(children[1]);

		// determine which operand is the path and which is the value
		final ObjectAccessOperator pathOperand;
		final ConstantOperand valueOperand;
		final boolean reversed;

		if (left instanceof ObjectAccessOperator leftPath && right instanceof ConstantOperand rightConst) {
			pathOperand = leftPath;
			valueOperand = rightConst;
			reversed = false;
		} else if (left instanceof ConstantOperand leftConst && right instanceof ObjectAccessOperator rightPath) {
			pathOperand = rightPath;
			valueOperand = leftConst;
			reversed = true;
		} else if (left instanceof ObjectAccessOperator && right instanceof ObjectAccessOperator) {
			throw new NonTranslatableExpressionException(
				"Cross-constraint value comparison between two data paths is not supported in FilterBy " +
					"translation. Both operands of the comparison are data access paths, but evitaDB " +
					"FilterBy cannot express 'attribute A equals attribute B'."
			);
		} else {
			throw new NonTranslatableExpressionException(
				"Unsupported comparison operand types: `" + left.getClass().getSimpleName() + "` and `" +
					right.getClass().getSimpleName() + "`. Expected one ObjectAccessOperator (data path) " +
					"and one ConstantOperand (literal value)."
			);
		}

		final DataPath dataPath = classifyPath(pathOperand);
		final Serializable value = valueOperand.getValue();

		final FilterConstraint attributeConstraint;
		if (value == null) {
			// null literal -> attributeIsNull / attributeIsNotNull (equality only)
			attributeConstraint = createNullAttributeConstraint(comparisonNode, dataPath.attributeName());
		} else {
			attributeConstraint =
				createAttributeConstraint(comparisonNode, dataPath.attributeName(), value, reversed);
		}

		this.result = wrapForPathType(dataPath, attributeConstraint);
	}

	/**
	 * Classifies the type of data path accessed by an {@link ObjectAccessOperator}.
	 */
	enum PathType {
		/**
		 * `$entity.attributes['x']` — local entity attribute, no wrapper needed.
		 */
		ENTITY_ATTRIBUTE,
		/**
		 * `$reference.attributes['x']` — reference attribute, wrapped in `referenceHaving`.
		 */
		REFERENCE_ATTRIBUTE,
		/**
		 * `$reference.groupEntity?.attributes['x']` — group entity attribute,
		 * wrapped in `referenceHaving(groupHaving(...))`.
		 */
		GROUP_ENTITY_ATTRIBUTE,
		/**
		 * `$reference.referencedEntity.attributes['x']` — referenced entity attribute,
		 * wrapped in `referenceHaving(entityHaving(...))`.
		 */
		REFERENCED_ENTITY_ATTRIBUTE,
		/**
		 * `$reference.referencedEntity.references['r'].attributes['x']` — reference attribute on
		 * the referenced entity, wrapped in `referenceHaving(entityHaving(referenceHaving('r', ...)))`.
		 */
		REFERENCED_ENTITY_REFERENCE_ATTRIBUTE,
		/**
		 * `$reference.groupEntity?.references['r'].attributes['x']` — reference attribute on
		 * the group entity, wrapped in `referenceHaving(groupHaving(referenceHaving('r', ...)))`.
		 */
		GROUP_ENTITY_REFERENCE_ATTRIBUTE
	}

	/**
	 * Holds the classified result of analyzing an {@link ObjectAccessOperator}'s access chain.
	 *
	 * @param pathType      the type of data path (determines wrapping)
	 * @param attributeName the attribute name extracted from the element access step
	 * @param referenceName the reference name on the target entity, or `null` for non-reference-attribute paths
	 */
	record DataPath(
		@Nonnull PathType pathType,
		@Nonnull String attributeName,
		@Nullable String referenceName
	) {
	}
}
