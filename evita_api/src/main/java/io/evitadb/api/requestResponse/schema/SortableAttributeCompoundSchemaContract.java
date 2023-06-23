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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

/**
 * Sortable attribute compounds are used to sort entities or references by multiple attributes at once. evitaDB
 * requires a pre-sorted index in order to be able to sort entities or references by particular attribute or
 * combination of attributes, so it can deliver the results as fast as possible. Sortable attribute compounds
 * are filtered the same way as {@link AttributeSchemaContract} attributes - using {@link AttributeNatural} ordering
 * constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SortableAttributeCompoundSchemaContract
	extends Serializable, NamedSchemaWithDeprecationContract {

	/**
	 * Method returns a collection of attribute elements that define the sortable compound. The order of the elements
	 * is important, as it defines the order of the sorting.
	 */
	@Nonnull
	List<AttributeElement> getAttributeElements();

	/**
	 * Attribute element is a part of the sortable compound. It defines the attribute name, the direction of the
	 * sorting and the behaviour of the null values. The attribute name refers to the existing attribute defined in the
	 * {@link AttributeSchemaProvider}.
	 *
	 * @param attributeName name of the existing attribute in the same {@link AttributeSchemaProvider}
	 * @param direction     direction of the sorting
	 * @param behaviour     behaviour of the null values
	 */
	record AttributeElement(
		@Nonnull String attributeName,
		@Nonnull OrderDirection direction,
		@Nonnull OrderBehaviour behaviour
	) implements Serializable {

		/**
		 * Helper method to create an attribute element. The direction is set to {@link OrderDirection#ASC} and the
		 * behaviour is set to {@link OrderBehaviour#NULLS_LAST}.
		 */
		@Nonnull
		public static AttributeElement attributeElement(@Nonnull String attributeName) {
			return new AttributeElement(attributeName, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
		}

		/**
		 * Helper method to create an attribute element with the given direction. The behaviour is set to
		 * {@link OrderBehaviour#NULLS_LAST}.
		 */
		@Nonnull
		public static AttributeElement attributeElement(@Nonnull String attributeName, @Nonnull OrderDirection direction) {
			return new AttributeElement(attributeName, direction, OrderBehaviour.NULLS_LAST);
		}

		/**
		 * Helper method to create an attribute element with the given behaviour. The direction is set to
		 * {@link OrderDirection#ASC}.
		 */
		@Nonnull
		public static AttributeElement attributeElement(@Nonnull String attributeName, @Nonnull OrderBehaviour behaviour) {
			return new AttributeElement(attributeName, OrderDirection.ASC, behaviour);
		}

		/**
		 * Helper method to create an attribute element with the given direction and behaviour.
		 */
		@Nonnull
		public static AttributeElement attributeElement(@Nonnull String attributeName, @Nonnull OrderDirection direction, @Nonnull OrderBehaviour behaviour) {
			return new AttributeElement(attributeName, direction, behaviour);
		}

		@Override
		public String toString() {
			return '\'' + attributeName + '\'' + " " + direction + " " + behaviour;

		}
	}

}
