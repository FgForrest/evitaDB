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
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * Immutable definition of a bucketed histogram configuration for a reference schema.
 * Each scope where a reference is "bucketed" carries one instance of this record,
 * specifying the histogram index name and the optional value expression used to
 * compute the bucket value for each referenced entity.
 *
 * @param nameOfTheIndex  the name identifying the histogram index, must not be null
 * @param valueExpression the expression computing the histogram bucket value, or null if not specified
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record HistogramIndexDefinition(
	@Nonnull String nameOfTheIndex,
	@Nullable Expression valueExpression
) implements Serializable {

	/**
	 * Compact constructor that validates the name is not null and not blank.
	 */
	public HistogramIndexDefinition {
		Assert.notNull(nameOfTheIndex, "Name of the index must not be null!");
		Assert.isTrue(!nameOfTheIndex.isBlank(), "Name of the index must not be blank!");
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
