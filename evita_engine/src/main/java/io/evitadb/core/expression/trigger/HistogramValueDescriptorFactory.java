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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition.AttributePathClassification;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NumberUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.api.query.expression.visitor.AccessedDataFinder.findAccessedPaths;

/**
 * Stateless factory that builds {@link HistogramValueDescriptor} metadata from a histogram value expression.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HistogramValueDescriptorFactory {

	/**
	 * Builds {@link HistogramValueDescriptor} from the given value expression.
	 *
	 * @param valueExpression the parsed value expression AST
	 * @param referenceName   the reference name (for error messages)
	 * @param histogramName   the histogram name (for error messages)
	 * @param scope           the scope in which the histogram is defined
	 * @param referenceSchema the reference schema
	 * @param schemaResolver  function resolving entity type name to entity schema
	 * @return immutable value resolution metadata
	 */
	@Nonnull
	public static HistogramValueDescriptor build(
		@Nonnull Expression valueExpression,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver
	) {
		final List<List<PathItem>> paths = findAccessedPaths(valueExpression);

		String attributeName = null;
		HistogramValueSource source = null;
		boolean localized = false;
		for (final List<PathItem> path : paths) {
			final AttributePathResult result = classifyAttributePath(path, referenceName, histogramName);
			if (result != null) {
				if (attributeName != null && !attributeName.equals(result.attributeName)) {
					throw new InvalidSchemaMutationException(
						"Histogram value expression for reference '" + referenceName +
							"', histogram '" + histogramName + "' references multiple attributes (" +
							attributeName + ", " + result.attributeName +
							"). Only single-attribute expressions are supported."
					);
				}
				attributeName = result.attributeName;
				source = result.source;
				localized = result.localized;
			}
		}

		if (attributeName == null || source == null) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName +
					"' uses unsupported expression form. Only " +
					"$reference.referencedEntity?.attributes['x'] and " +
					"$reference.attributes['x'] (with optional ?? default) are supported."
			);
		}

		final String sourceEntityType;
		final AttributeSchemaContract attributeSchema;
		if (source == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
			sourceEntityType = referenceSchema.getReferencedEntityType();
			final EntitySchemaContract referencedEntitySchema = schemaResolver.apply(sourceEntityType);
			if (referencedEntitySchema == null) {
				throw new InvalidSchemaMutationException(
					"Histogram value expression for reference '" + referenceName +
						"', histogram '" + histogramName + "' references entity type '" +
						sourceEntityType + "' which does not exist."
				);
			}
			final Optional<? extends AttributeSchemaContract> attrOpt =
				referencedEntitySchema.getAttribute(attributeName);
			if (attrOpt.isEmpty()) {
				throw new InvalidSchemaMutationException(
					"Histogram value expression for reference '" + referenceName +
						"', histogram '" + histogramName + "' references attribute '" +
						attributeName + "' which does not exist on entity type '" + sourceEntityType + "'."
				);
			}
			attributeSchema = attrOpt.get();
		} else {
			sourceEntityType = null;
			final Optional<? extends AttributeSchemaContract> attrOpt = referenceSchema.getAttribute(attributeName);
			if (attrOpt.isEmpty()) {
				throw new InvalidSchemaMutationException(
					"Histogram value expression for reference '" + referenceName +
						"', histogram '" + histogramName + "' references attribute '" +
						attributeName + "' which does not exist on reference '" + referenceName + "'."
				);
			}
			attributeSchema = attrOpt.get();
		}

		if (localized && !attributeSchema.isLocalized()) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName + "' uses localizedAttributes accessor " +
					"for attribute '" + attributeName + "' which is not localized."
			);
		}
		if (!localized && attributeSchema.isLocalized()) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName + "' uses attributes accessor " +
					"for attribute '" + attributeName + "' which is localized. " +
					"Use localizedAttributes accessor instead."
			);
		}

		if (!attributeSchema.isFilterableInScope(scope)) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName + "' in scope " + scope.name() +
					" references attribute '" + attributeName +
					"' which is not filterable in that scope. Source attributes must be filterable."
			);
		}

		final Class<? extends Serializable> plainType = attributeSchema.getPlainType();
		final boolean arrayType = attributeSchema.getType().isArray();

		// Range-typed source attribute: derive the inner numeric type and skip the numeric-type
		// validation. Defaults are not supported for Range histograms — reject `??` at the root
		// of the value expression rather than silently dropping the user's specified default.
		if (Range.class.isAssignableFrom(plainType)) {
			if (valueExpression.getOperand() instanceof NullCoalesceOperator) {
				throw new InvalidSchemaMutationException(
					"Histogram value expression for reference '" + referenceName +
						"', histogram '" + histogramName + "' uses a `??` default value with " +
						"Range-typed attribute '" + attributeName + "' (type: " +
						plainType.getSimpleName() + "). Default values are not supported for " +
						"Range histograms — remove the default operand."
				);
			}
			final Class<? extends Number> innerNumericType = EvitaDataTypes.resolveRangeInnerNumericType(plainType);
			if (innerNumericType == null) {
				throw new GenericEvitaInternalError(
					"Unexpected Range subtype: " + plainType.getName()
				);
			}
			return new HistogramValueDescriptor(
				source, sourceEntityType, attributeName, plainType, arrayType, localized,
				null, innerNumericType
			);
		}

		if (!EvitaDataTypes.isNumericType(plainType)) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName + "' references non-numeric attribute '" +
					attributeName + "' (type: " + plainType.getSimpleName() +
					"). Only numeric types (Byte, Short, Integer, Long, BigDecimal) and Range types " +
					"(ByteNumberRange, ShortNumberRange, IntegerNumberRange, LongNumberRange, " +
					"BigDecimalNumberRange) are supported."
			);
		}

		final Number defaultValue = extractDefaultValue(
			valueExpression, referenceName, histogramName, plainType
		);

		return new HistogramValueDescriptor(
			source, sourceEntityType, attributeName, plainType, arrayType, localized,
			defaultValue, null
		);
	}

	/**
	 * Classifies a single accessed-data path to determine whether it represents a reference attribute
	 * access or a referenced entity attribute access. Delegates core pattern matching to
	 * {@link HistogramIndexDefinition#classifyAttributePath(List)} and adds validation that rejects
	 * unsupported forms (entity-level, parent, and group entity attributes).
	 *
	 * @param path          the path items extracted from the expression AST by
	 *                      {@link io.evitadb.api.query.expression.visitor.AccessedDataFinder}
	 * @param referenceName the reference name (for error messages)
	 * @param histogramName the histogram name (for error messages)
	 * @return classification result with attribute name, source, and localized flag; or null if irrelevant
	 * @throws InvalidSchemaMutationException if the path uses an explicitly unsupported form
	 */
	@Nullable
	private static AttributePathResult classifyAttributePath(
		@Nonnull List<PathItem> path,
		@Nonnull String referenceName,
		@Nonnull String histogramName
	) {
		if (path.size() < 2) {
			return null;
		}
		final PathItem first = path.get(0);
		final PathItem second = path.get(1);
		if (!(first instanceof VariablePathItem variable) || !(second instanceof IdentifierPathItem identifier)) {
			return null;
		}

		// reject entity-level attribute access (only reference-scoped attributes are allowed)
		if (EntityContractAccessor.ENTITY_VARIABLE_NAME.equals(variable.value())) {
			if (EntityContractAccessor.PARENT_ENTITY_PROPERTY.equals(identifier.value())) {
				throw new InvalidSchemaMutationException(
					"Histogram expressions must not reference parent entity attributes. " +
						"Reference '" + referenceName + "', histogram '" + histogramName + "'."
				);
			}
			if (EntityContractAccessor.isAttributesProperty(identifier.value())) {
				throw new InvalidSchemaMutationException(
					"Histogram value expression for reference '" + referenceName +
						"', histogram '" + histogramName + "' uses unsupported expression form."
				);
			}
			return null;
		}

		// reject group entity attribute access — not supported in histogram value expressions
		if (ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variable.value())
			&& ReferenceContractAccessor.GROUP_ENTITY_PROPERTY.equals(identifier.value())) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName + "' uses unsupported expression form."
			);
		}

		// delegate core matching to the shared classifier
		final AttributePathClassification classification = HistogramIndexDefinition.classifyAttributePath(path);
		if (classification == null) {
			return null;
		}
		final HistogramValueSource source = switch (classification.source()) {
			case REFERENCE_ATTRIBUTE -> HistogramValueSource.REFERENCE_ATTRIBUTE;
			case REFERENCED_ENTITY_ATTRIBUTE -> HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE;
		};
		return new AttributePathResult(source, classification.attributeName(), classification.localized());
	}

	/**
	 * Extracts the default value from a null-coalesce (`??`) operator at the root of the expression.
	 * If the expression root is `someAccess ?? constant`, the constant is converted to the target
	 * `plainType` and returned. Returns null when there is no coalesce operator, when the default
	 * operand is not a constant, or when the constant value is null.
	 *
	 * @param expression    the parsed value expression AST
	 * @param referenceName the reference name (for error messages)
	 * @param histogramName the histogram name (for error messages)
	 * @param plainType     the target numeric type to convert the constant to
	 * @return the default value converted to `plainType`, or null if no default is specified
	 * @throws InvalidSchemaMutationException if the default value is non-numeric
	 */
	@Nullable
	private static Number extractDefaultValue(
		@Nonnull Expression expression,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		final ExpressionNode rootNode = expression.getOperand();
		if (!(rootNode instanceof NullCoalesceOperator coalesceOp)) {
			return null;
		}
		final ExpressionNode defaultNode = coalesceOp.getRightOperand();
		if (!(defaultNode instanceof ConstantOperand constantOperand)) {
			return null;
		}
		final Serializable value = constantOperand.getValue();
		if (value == null) {
			return null;
		}
		if (!(value instanceof Number numberValue)) {
			throw new InvalidSchemaMutationException(
				"Histogram value expression for reference '" + referenceName +
					"', histogram '" + histogramName + "' has a non-numeric default value."
			);
		}
		return NumberUtils.convertToNumericType(numberValue, plainType);
	}

	/**
	 * Internal result of classifying a single expression path. Carries the determined
	 * {@link HistogramValueSource}, the attribute name extracted from the path's
	 * {@link io.evitadb.api.query.expression.visitor.ElementPathItem}, and whether the path accesses
	 * localized attributes.
	 *
	 * @param source        whether the attribute lives on the reference or the referenced entity
	 * @param attributeName the attribute name from the expression path
	 * @param localized     true if the path uses `localizedAttributes` accessor
	 */
	private record AttributePathResult(
		@Nonnull HistogramValueSource source,
		@Nonnull String attributeName,
		boolean localized
	) {
	}

}
