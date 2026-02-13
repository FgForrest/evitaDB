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

package io.evitadb.api.query.expression.evaluate.object.accessor.entity;

import io.evitadb.api.query.expression.evaluate.object.accessor.ObjectPropertyAccessor;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Implementation of {@link ObjectPropertyAccessor} for {@link EntityContract} objects.
 * Enables dot-property access expressions in EvitaEL to navigate the entity structure,
 * e.g. `entity.attributes`, `entity.localizedAttributes`, `entity.associatedData`,
 * `entity.localizedAssociatedData`, or `entity.references`.
 *
 * Each supported property returns a scoped DTO wrapper that limits the entity contract to
 * a specific sub-domain (attributes, associated data, or references) so that downstream
 * {@link io.evitadb.api.query.expression.evaluate.object.accessor.ObjectElementAccessor}
 * implementations can correctly evaluate bracket-access expressions on the result. The DTO
 * also carries a localization flag where applicable, distinguishing between localized and
 * non-localized access paths.
 *
 * @see ObjectPropertyAccessor
 * @see AttributesContractAccessor
 * @see AssociatedDataContractAccessor
 * @see ReferencesContractAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class EntityContractAccessor implements ObjectPropertyAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { EntityContract.class };
	}

	@Nullable
	@Override
	public Serializable get(@Nonnull Serializable object, @Nonnull String propertyIdentifier) throws ExpressionEvaluationException {
		if (!(object instanceof EntityContract entity)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected EntityContract.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case "attributes" -> new EntityAttributesEvaluationDto(entity, false);
			case "localizedAttributes" -> new EntityAttributesEvaluationDto(entity, true);
			case "associatedData" -> new EntityAssociatedDataEvaluationDto(entity, false);
			case "localizedAssociatedData" -> new EntityAssociatedDataEvaluationDto(entity, true);
			case "references" -> new EntityReferencesEvaluationDto(entity);
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on EntityContract.",
					"Property `" + propertyIdentifier + "` does not exist on entity."
				);
		};
	}

	/**
	 * DTO to limit the scope of the source {@link EntityContract} only to its attributes for correct evaluation of the accessor for attributes.
	 */
	public record EntityAttributesEvaluationDto(@Delegate EntityContract delegate, boolean requestedLocalizedAttributes)
		implements AttributesContract<EntityAttributeSchemaContract> {}

	/**
	 * DTO to limit the scope of the source {@link EntityContract} only to its associated data for correct evaluation of the accessor for associated data.
	 */
	public record EntityAssociatedDataEvaluationDto(@Delegate EntityContract delegate, boolean requestedLocalizedAssociatedData)
		implements AssociatedDataContract {}

	/**
	 * DTO to limit the scope of the source {@link EntityContract} only to its references for correct evaluation of the accessor for references.
	 */
	public record EntityReferencesEvaluationDto(@Delegate EntityContract delegate)
		implements ReferencesContract {}
}
