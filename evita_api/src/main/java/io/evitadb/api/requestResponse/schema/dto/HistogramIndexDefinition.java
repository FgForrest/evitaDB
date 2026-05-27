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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.visitor.ElementPathItem;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.api.requestResponse.schema.NamedContract;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable definition of a bucketed histogram configuration for a reference schema.
 * Each scope where a reference is "bucketed" carries one instance of this record,
 * specifying the histogram index name and the optional value expression used to
 * compute the bucket value for each referenced entity.
 *
 * The record also exposes the index name in all supported {@link NamingConvention naming
 * conventions} via the {@link NamedContract} super-interface. Name variants are always
 * server-generated from the index name by {@link NamingConvention#generate(String)} —
 * callers never supply them.
 *
 * @param nameOfTheIndex   the name identifying the histogram index, must not be null
 * @param nameVariants     pre-computed variants of {@code nameOfTheIndex} in all naming conventions
 * @param valueExpression  the expression computing the histogram bucket value, or null if not specified
 * @param assignedWhen     the partition selector for this histogram; null means no per-histogram
 *                         restriction beyond the reference- or scope-level eligibility gate
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record HistogramIndexDefinition(
	@Nonnull String nameOfTheIndex,
	@Nonnull Map<NamingConvention, String> nameVariants,
	@Nullable Expression valueExpression,
	@Nullable Expression assignedWhen
) implements NamedContract {

	/**
	 * Explicit serialVersionUID — records still need a stable identifier for binary
	 * serialization compatibility across schema evolution.
	 */
	@Serial
	private static final long serialVersionUID = -7846150281245619827L;

	/**
	 * Compact constructor that validates the name is not null and not blank, and wraps the
	 * name-variants map in an unmodifiable view.
	 */
	public HistogramIndexDefinition {
		Assert.notNull(nameOfTheIndex, "Name of the index must not be null!");
		Assert.isTrue(!nameOfTheIndex.isBlank(), "Name of the index must not be blank!");
		Assert.notNull(nameVariants, "Name variants must not be null!");
		nameVariants = Collections.unmodifiableMap(nameVariants);
	}

	/**
	 * Convenience factory that auto-generates the name variants from {@code nameOfTheIndex}
	 * using {@link NamingConvention#generate(String)} and leaves the per-histogram condition
	 * unset. Equivalent to calling {@link #of(String, Expression, Expression)} with a null
	 * third argument.
	 *
	 * @param nameOfTheIndex  the name identifying the histogram index
	 * @param valueExpression the expression computing the histogram bucket value, or null
	 * @return a new {@link HistogramIndexDefinition} with auto-generated name variants
	 */
	@Nonnull
	public static HistogramIndexDefinition of(
		@Nonnull String nameOfTheIndex,
		@Nullable Expression valueExpression
	) {
		return of(nameOfTheIndex, valueExpression, null);
	}

	/**
	 * Convenience factory that auto-generates the name variants from {@code nameOfTheIndex}
	 * using {@link NamingConvention#generate(String)} and accepts the optional per-histogram
	 * partition selector that decides — among the references already eligible per the
	 * reference- or scope-level gate — which entities are assigned to this specific histogram.
	 *
	 * @param nameOfTheIndex  the name identifying the histogram index
	 * @param valueExpression the expression computing the histogram bucket value, or null
	 * @param assignedWhen    the optional per-histogram partition selector, or null
	 * @return a new {@link HistogramIndexDefinition} with auto-generated name variants
	 */
	@Nonnull
	public static HistogramIndexDefinition of(
		@Nonnull String nameOfTheIndex,
		@Nullable Expression valueExpression,
		@Nullable Expression assignedWhen
	) {
		return new HistogramIndexDefinition(
			nameOfTheIndex,
			NamingConvention.generate(nameOfTheIndex),
			valueExpression,
			assignedWhen
		);
	}

	@Nonnull
	@Override
	public String getName() {
		return this.nameOfTheIndex;
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> getNameVariants() {
		return this.nameVariants;
	}

	@Nonnull
	@Override
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	/**
	 * Classifies a single accessed-data path to determine whether it represents a reference attribute
	 * access (`$reference.attributes['x']` or `$reference.localizedAttributes['x']`) or a referenced
	 * entity attribute access (`$reference.referencedEntity?.attributes['x']` or
	 * `$reference.referencedEntity?.localizedAttributes['x']`).
	 *
	 * Returns null for paths that do not match either pattern (e.g. entity-level, group, parent,
	 * or completely unrelated paths). Callers are responsible for additional validation if needed.
	 *
	 * @param path the path items extracted from the expression AST by
	 *             {@link io.evitadb.api.query.expression.visitor.AccessedDataFinder}
	 * @return classification result with attribute name, source, and localized flag; or null if
	 *         the path does not represent a reference or referenced-entity attribute access
	 */
	@Nullable
	public static AttributePathClassification classifyAttributePath(@Nonnull List<PathItem> path) {
		if (path.size() < 3) {
			return null;
		}
		final PathItem first = path.get(0);
		final PathItem second = path.get(1);
		if (!(first instanceof VariablePathItem variable) || !(second instanceof IdentifierPathItem identifier)) {
			return null;
		}

		// only $reference paths are relevant
		if (!ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variable.value())) {
			return null;
		}

		// $reference.attributes['x'] or $reference.localizedAttributes['x'] → reference-level attribute
		if (EntityContractAccessor.isAttributesProperty(identifier.value())
			&& path.get(2) instanceof ElementPathItem element) {
			return new AttributePathClassification(
				AttributeSource.REFERENCE_ATTRIBUTE,
				element.value(),
				EntityContractAccessor.isLocalizedAttributesProperty(identifier.value())
			);
		}

		// $reference.referencedEntity?.attributes['x'] or localizedAttributes['x'] → referenced entity attribute
		if (ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY.equals(identifier.value())
			&& path.size() > 3
			&& path.get(2) instanceof IdentifierPathItem thirdId
			&& EntityContractAccessor.isAttributesProperty(thirdId.value())
			&& path.get(3) instanceof ElementPathItem element) {
			return new AttributePathClassification(
				AttributeSource.REFERENCED_ENTITY_ATTRIBUTE,
				element.value(),
				EntityContractAccessor.isLocalizedAttributesProperty(thirdId.value())
			);
		}

		return null;
	}

	/**
	 * Distinguishes whether a histogram value expression accesses an attribute on the reference
	 * itself or on the referenced entity.
	 */
	public enum AttributeSource {
		/** Attribute lives directly on the reference (`$reference.attributes['x']`). */
		REFERENCE_ATTRIBUTE,
		/** Attribute lives on the referenced entity (`$reference.referencedEntity?.attributes['x']`). */
		REFERENCED_ENTITY_ATTRIBUTE
	}

	/**
	 * Result of classifying a single expression path. Carries the determined
	 * {@link AttributeSource}, the attribute name extracted from the path's
	 * {@link ElementPathItem}, and whether the path accesses localized attributes.
	 *
	 * @param source        whether the attribute lives on the reference or the referenced entity
	 * @param attributeName the attribute name from the expression path
	 * @param localized     true if the path uses `localizedAttributes` accessor
	 */
	public record AttributePathClassification(
		@Nonnull AttributeSource source,
		@Nonnull String attributeName,
		boolean localized
	) implements Serializable {
	}

}
