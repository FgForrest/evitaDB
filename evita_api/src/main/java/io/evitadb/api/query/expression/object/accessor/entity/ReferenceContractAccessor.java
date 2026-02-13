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

package io.evitadb.api.query.expression.object.accessor.entity;

import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Implementation of {@link ObjectPropertyAccessor} for {@link ReferenceContract} objects.
 * Enables dot-property access expressions in EvitaEL to navigate the reference structure,
 * e.g. `reference.referencedPrimaryKey`, `reference.attributes`, or
 * `reference.localizedAttributes`.
 *
 * The `referencedPrimaryKey` property returns the primary key of the referenced entity
 * directly, while `attributes` and `localizedAttributes` return scoped DTO wrappers
 * ({@link ReferenceAttributesEvaluationDto}) that limit the reference contract to its
 * attributes for downstream
 * {@link AttributesContractAccessor} evaluation.
 * Access to `referencedEntity` and `groupEntity` is intentionally unsupported and throws
 * an {@link ExpressionEvaluationException}.
 *
 * @see ObjectPropertyAccessor
 * @see AttributesContractAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ReferenceContractAccessor implements ObjectPropertyAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { ReferenceContract.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String propertyIdentifier
	) throws ExpressionEvaluationException {
		if (!(object instanceof ReferenceContract referenceContract)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected ReferenceContract.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case "referencedPrimaryKey" -> referenceContract.getReferencedPrimaryKey();
			case "attributes" -> new ReferenceAttributesEvaluationDto(referenceContract, false);
			case "localizedAttributes" -> new ReferenceAttributesEvaluationDto(referenceContract, true);
			case "referencedEntity" -> throw new ExpressionEvaluationException(
				"Accessing referenced entity on ReferenceContract is not supported.",
				"Accessing referenced entity on reference is not supported."
			);
			case "groupEntity" -> throw new ExpressionEvaluationException(
				"Accessing group entity on ReferenceContract is not supported.",
				"Accessing group entity on reference is not supported."
			);
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on ReferenceContract.",
					"Property `" + propertyIdentifier + "` does not exist on reference."
				);
		};
	}

	/**
	 * DTO to limit the scope of the source {@link ReferenceContract} only to its attributes
	 * for correct evaluation of the accessor for attributes.
	 */
	public record ReferenceAttributesEvaluationDto(@Delegate ReferenceContract delegate, boolean requestedLocalizedAttributes)
		implements AttributesContract<AttributeSchemaContract> {
	}
}
