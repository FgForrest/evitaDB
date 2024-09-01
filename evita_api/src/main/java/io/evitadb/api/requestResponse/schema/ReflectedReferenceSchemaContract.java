/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse.schema;


import javax.annotation.Nonnull;

/**
 * Schema allows to set up bi-directional relation between two entities. By default, all properties and reference
 * attributes of the reflected reference are inherited from the target reference. This can be changed by setting
 * appropriate properties on the reflected reference schema definition, however. You'd probably want to alter
 * the original {@link ReferenceSchemaContract#getCardinality()} as it may not be the same for the reflected reference.
 *
 * Reflected reference behaves the same as normal reference - it can be created, updated or deleted both from the source
 * and target entity. It always modifies data in the source entity (entity that maintains the primary reference), but
 * updates all the involved indexes so that the data remains consistent from both sides.
 *
 * Note: the original reference {@link ReferenceSchemaContract#getReferencedEntityType()} must target the entity where
 * the reflected reference is defined, also the {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()} must be
 * set to true.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ReflectedReferenceSchemaContract extends ReferenceSchemaContract {

	/**
	 * Returns name of the reflected reference of the target {@link #getReferencedEntityType()}. The referenced entity
	 * must contain reference of such name and this reference must target the entity where the reflected reference is
	 * defined, and the target entity must be managed on both sides of the relation.
	 *
	 * @return the reflected reference as a String
	 */
	@Nonnull
	String getReflectedReferenceName();

	/**
	 * Returns true if the description of the reflected reference is inherited from the target reference.
	 *
	 * @return true if the description is inherited, false otherwise
	 */
	boolean isDescriptionInherited();

	/**
	 * Returns true if the deprecated flag of the reflected reference is inherited from the target reference.
	 *
	 * @return true if the deprecated flag is inherited, false otherwise
	 */
	boolean isDeprecatedInherited();

	/**
	 * Returns true if the cardinality of the reflected reference is inherited from the target reference.
	 *
	 * @return true if the cardinality is inherited, false otherwise
	 */
	boolean isCardinalityInherited();

	/**
	 * Returns true if the indexed property settings of the reflected reference is inherited from the target reference.
	 *
	 * @return true if the indexed property settings is inherited, false otherwise
	 */
	boolean isIndexedInherited();

	/**
	 * Returns true if the faceted property settings of the reflected reference is inherited from the target reference.
	 *
	 * @return true if the faceted property settings is inherited, false otherwise
	 */
	boolean isFacetedInherited();

	/**
	 * Returns the inheritance behavior for attributes in the reflected schema.
	 *
	 * This method returns an instance of the {@link AttributeInheritanceBehavior} enum that specifies how attribute
	 * inheritance should be handled in the reference schema. There are two options:
	 *
	 * - {@link AttributeInheritanceBehavior#INHERIT_ALL_EXCEPT}: All attributes are inherited by default,
	 *   except those listed in the {@link #getAttributeInheritanceFilter()}.
	 *
	 * - {@link AttributeInheritanceBehavior#INHERIT_ONLY_SPECIFIED}: No attributes are inherited by default,
	 *   only those explicitly listed in the {@link #getAttributeInheritanceFilter()}.
	 *
	 * @return The inheritance behavior for attributes in the schema.
	 */
	@Nonnull
	AttributeInheritanceBehavior getAttributesInheritanceBehavior();

	/**
	 * Returns the array of attribute names that filtered in the way driven by the {@link #getAttributesInheritanceBehavior()}
	 * property:
	 *
	 * - {@link AttributeInheritanceBehavior#INHERIT_ALL_EXCEPT}: inherits all attributes defined on original reference
	 *   except those listed in this filter
	 * - {@link AttributeInheritanceBehavior#INHERIT_ONLY_SPECIFIED}: inherits only attributes that are listed in
	 *   this filter
	 *
	 * @return array of attribute names
	 */
	@Nonnull
	String[] getAttributeInheritanceFilter();

	/**
	 * Returns true if the reflected reference is available. Reflected reference might not be available, when it really
	 * doesn't yet exists in the target entity schema (but it may be created in the future).
	 *
	 * @return true if the reflected reference is available
	 */
	boolean isReflectedReferenceAvailable();

	/**
	 * Enum specifies different modes for reference attributes inheritance in reflected schema.
	 */
	enum AttributeInheritanceBehavior {
		/**
		 * Inherit all attributes by default except those listed in the {@link #getAttributeInheritanceFilter()} array.
		 */
		INHERIT_ALL_EXCEPT,

		/**
		 * Do not inherit any attributes by default except those listed in the {@link #getAttributeInheritanceFilter()} array.
		 */
		INHERIT_ONLY_SPECIFIED
	}

}
