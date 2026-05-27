/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation is used to mark a field, getter method or record component as reference of the entity to another entity.
 * This information, annotation attribute values and target field/method/component signature allows to generate
 * {@link ReferenceSchemaContract}.
 *
 * If you set up bi-directional reference, you need to use {@link ReflectedReference} annotation as well.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface Reference {

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
	 * with {@link Entity}, the value of this attribute can be derived from it. In such case also attribute
	 * {@link #managed()} is derived along with it.
	 */
	String entity() default "";

	/**
	 * Specifies whether the {@link #entity()} targets an entity managed by the same evitaDB catalog (set to `true`)
	 * or an externally managed entity outside evitaDB catalog (set to `false`). The value of this attribute can be
	 * derived from class structure.
	 *
	 * @see #entity()
	 */
	boolean managed() default true;

	/**
	 * Specifies the name of the group of target entity for the reference. If the field, method getter or record
	 * component returns a complex class and this class is annotated with {@link Entity} annotation or contains a field,
	 * method getter or record component marked with {@link ReferencedEntityGroup} that relates to a complex class annotated
	 * with {@link Entity}, the value of this attribute can be derived from it. In such case also attribute
	 * {@link #groupEntityManaged()} is derived along with it.
	 */
	String groupEntity() default "";

	/**
	 * Specifies whether the {@link #groupEntity()} targets an entity managed by the same evitaDB catalog (set to `true`)
	 * or an externally managed entity outside evitaDB catalog (set to `false`). The value of this attribute can be
	 * derived from class structure.
	 *
	 * @see #groupEntity()
	 */
	boolean groupEntityManaged() default true;

	/**
	 * If set to true (default) the entity may have the reference empty (i.e. without relation). If set to false, at
	 * least one reference of this type is required in order to commit transaction.
	 *
	 * This annotation along with the annotated field, method getter or record component type (whether it's array,
	 * collection or plain type) affects the {@link ReferenceSchemaContract#getCardinality()}.
	 */
	boolean allowEmpty() default true;

	/**
	 * If set to true, multiple references of this type may target the same referenced entity. These duplicated
	 * references must be uniquely identifiable by their representative attributes. If set to false, the entity
	 * may have only one reference of this type to a particular target entity.
	 */
	boolean allowDuplicates() default false;

	/**
	 * Enables filtering / sorting by attributes of reference of this name.
	 * Propagates to {@link ReferenceSchemaContract#getReferenceIndexType(Scope)}.
	 */
	ReferenceIndexType indexed() default ReferenceIndexType.NONE;

	/**
	 * Configures which components of this reference are indexed (referenced entity, referenced
	 * group entity, or both). Effective only when {@link #indexed()} is not
	 * {@link ReferenceIndexType#NONE}.
	 * Propagates to {@link ReferenceSchemaContract#getIndexedComponents(Scope)}.
	 */
	ReferenceIndexedComponents[] indexedComponents() default { ReferenceIndexedComponents.REFERENCED_ENTITY };

	/**
	 * Enables facet computation for reference of this name.
	 * Propagates to {@link ReferenceSchemaContract#isFaceted()}.
	 */
	boolean faceted() default false;

	/**
	 * Defines a condition to further narrow down the facet scope.
	 * This is only evaluated if {@link #faceted()} is set to true.
	 */
	Expression facetedPartially() default @Expression;

	/**
	 * Enables histogram (bucketed) computation for reference of this name.
	 *
	 * Each {@link Histogram} entry declares a distinct named histogram index. Multiple
	 * histograms may be declared for a single reference, each with its own
	 * `nameOfTheIndex`, `value` expression and optional `assignedWhen` per-histogram
	 * partition selector. The default empty array means no histograms are computed for
	 * this reference.
	 *
	 * Within the array, every entry must declare a non-empty `nameOfTheIndex`; empty
	 * histogram entries inside a non-empty array are rejected by the analyzer. Names
	 * must be unique within a (reference, scope) pair.
	 */
	Histogram[] bucketed() default {};

	/**
	 * Eligibility gate. References (or referenced entities) for which this expression
	 * evaluates to `true` are eligible for the histograms declared in {@link #bucketed()}.
	 * References failing this predicate are excluded from every bucketed histogram on this
	 * reference. Pairs with each {@link Histogram#assignedWhen()} (which decides — among
	 * the eligible set — which histogram each entity is assigned to). Evaluated only when
	 * at least one {@link Histogram} is declared in {@link #bucketed()}.
	 */
	Expression bucketedPartially() default @Expression;

	/**
	 * Allows to define different settings for different scopes.
	 *
	 * If `scope = {}` (default, empty array), the general settings (`indexed`, `indexedComponents`, `faceted`,
	 * `facetedPartially`, `bucketed`, `bucketedPartially`) apply to {@link Scope#LIVE} only; in
	 * {@link Scope#ARCHIVED} the reference and its attributes are not indexed whatsoever (not filterable,
	 * not sortable, not unique, not faceted).
	 *
	 * If `scope = {…}` is non-empty, the per-scope settings **completely replace** what the general settings would
	 * otherwise produce — the analyzer requires the general `indexed`, `faceted`, `facetedPartially`, `bucketed`
	 * and `bucketedPartially` properties to be left at their defaults (otherwise an assertion fires). The general
	 * `indexedComponents` is silently ignored. Scopes not listed in the array remain non-indexed/non-faceted;
	 * the general settings are not used as a fallback.
	 */
	ScopeReferenceSettings[] scope() default {};

}
