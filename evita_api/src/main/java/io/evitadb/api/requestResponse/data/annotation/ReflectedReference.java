/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.requestResponse.data.annotation;


import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation sets up bi-directional relation between two entities. It needs to be accompanied by {@link Reference}
 * annotation that defines the reference name and the target (managed) entity. Reflected reference behaves the same
 * as normal reference - it can be created, updated or deleted both from the source and target entity. It always modifies
 * data in the source entity (entity that maintains the primary reference), but updates all the involved indexes so that
 * the data remains consistent from both sides.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ReflectedReference {

	/**
	 * Specific enum that allows tri-state logic: true / false and inherited value.
	 */
	enum InheritableBoolean {
		INHERITED, TRUE, FALSE
	}

	/**
	 * Name of the reference. Use camel-case naming style.
	 * Propagates to {@link ReferenceSchemaContract#getName()}
	 * If left empty the name is taken from the name of the field / property / component.
	 */
	String name() default "";

	/**
	 * Description of the reference. Use Markdown format.
	 * Propagates to {@link ReferenceSchemaContract#getDescription()}.
	 */
	String description() default "";

	/**
	 * Marks reference as deprecated and allows to specify the reason for it. Use Markdown format.
	 * Propagates to {@link ReferenceSchemaContract#getDeprecationNotice()}.
	 */
	String deprecated() default "";

	/**
	 * Specifies the name of the target entity for the reference. If the field, method getter or record
	 * component returns a complex class and this class is annotated with {@link Entity} annotation or contains a field,
	 * method getter or record component marked with {@link ReferencedEntity} that relates to a complex class annotated
	 * with {@link Entity}, the value of this attribute can be derived from it.
	 */
	String ofEntity() default "";

	/**
	 * Name of the reflected reference (source of data). Use camel-case naming style.
	 * The referenced entity in {@link #ofEntity()} must contain reference of such name and this reference must target
	 * the entity where the reflected reference is defined, and the target entity must be managed on both sides of
	 * the relation.
	 * Propagates to {@link ReflectedReferenceSchemaContract#getReflectedReferenceName()}
	 */
	String ofName();

	/**
	 * If set to true (default) the entity may have the reference empty (i.e. without relation). If set to false, at
	 * least one reference of this type is required in order to commit transaction.
	 *
	 * This annotation along with the annotated field, method getter or record component type (whether it's array,
	 * collection or plain type) affects the {@link ReflectedReferenceSchemaContract#getCardinality()}.
	 */
	InheritableBoolean allowEmpty() default InheritableBoolean.INHERITED;

	/**
	 * Enables filtering / sorting by attributes of reference of this name.
	 * Propagates to {@link ReferenceSchemaContract#getReferenceIndexType(Scope)}.
	 * Doesn't accept the {@link ReferenceIndexType#NONE} value, because otherwise it wouldn't be able to maintain
	 * the bi-directional relation.
	 */
	ReferenceIndexType indexed() default ReferenceIndexType.FOR_FILTERING;

	/**
	 * Enables facet computation for reference of this name.
	 * Propagates to {@link ReflectedReferenceSchemaContract#isFaceted()}.
	 */
	InheritableBoolean faceted() default InheritableBoolean.FALSE;

	/**
	 * Returns the inheritance behavior for attributes in the reflected schema.
	 *
	 * This method returns an instance of the {@link AttributeInheritanceBehavior} enum that specifies how attribute
	 * inheritance should be handled in the reference schema. There are two options:
	 *
	 * - {@link AttributeInheritanceBehavior#INHERIT_ALL_EXCEPT}: All attributes are inherited by default,
	 *   except those listed in the {@link #attributeInheritanceFilter()} ()}.
	 *
	 * - {@link AttributeInheritanceBehavior#INHERIT_ONLY_SPECIFIED}: No attributes are inherited by default,
	 *   only those explicitly listed in the {@link #attributeInheritanceFilter()} ()}.
	 */
	AttributeInheritanceBehavior attributesInheritanceBehavior() default AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED;

	/**
	 * Returns the array of attribute names that filtered in the way driven by the {@link #attributesInheritanceBehavior()}
	 * property:
	 *
	 * - {@link AttributeInheritanceBehavior#INHERIT_ALL_EXCEPT}: inherits all attributes defined on original reference
	 *   except those listed in this filter
	 * - {@link AttributeInheritanceBehavior#INHERIT_ONLY_SPECIFIED}: inherits only attributes that are listed in
	 *   this filter
	 */
	String[] attributeInheritanceFilter() default {};

	/**
	 * Allows to define different settings for different scopes. If not specified, the general settings apply only to
	 * the {@link Scope#LIVE} and in the {@link Scope#ARCHIVED} the reference and its attributes are not indexed
	 * whatsoever (not filterable, not sortable, not unique, not faceted). If scope settings are specified for
	 * {@link Scope#LIVE}, the general settings are ignored completely.
	 *
	 * The entities in different scopes are never not connected by the reflected reference. The reflected references
	 * connect only entities in the same scope.
	 */
	ScopeReferenceSettings[] scope() default {};

}
