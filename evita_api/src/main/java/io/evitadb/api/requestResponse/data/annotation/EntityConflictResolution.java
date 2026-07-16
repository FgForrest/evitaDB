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

package io.evitadb.api.requestResponse.data.annotation;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the entity-level conflict resolution for the entity as a whole. It is used only as a nested member
 * of {@link Entity#conflictResolution()}, never standalone (hence the empty {@link Target}), and mirrors the
 * programmatic entity-schema conflict resolution set via
 * `EntitySchemaEditor#withConflictResolution(ConflictResolution)`.
 *
 * Because a Java annotation element can be neither `null` nor a record, the "inherit from catalog / engine"
 * state that a `null` conflict resolution represents on the schema is expressed here by the {@link #inherited()}
 * flag: while `inherited()` is `true` (the default) the {@link #policy()} and {@link #granularity()} members
 * are ignored and no entity-level resolution is set — the entity follows the resolution resolved from the
 * catalog schema and engine configuration.
 *
 * When `inherited()` is `false`, {@link #policy()} and {@link #granularity()} are combined into a single
 * `ConflictResolution`. Granular refinements are only valid under the {@link ConflictPolicy#ENTITY} coarse
 * scope; declaring them under a coarser scope fails with the same validation error as the programmatic
 * builder path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface EntityConflictResolution {

	/**
	 * When `true` (the default), the entity inherits the conflict resolution resolved from the catalog schema
	 * and engine configuration — nothing is set on the entity schema and {@link #policy()} / {@link #granularity()}
	 * are ignored. Set to `false` to pin the entity to the explicit resolution described by the other members.
	 */
	boolean inherited() default true;

	/**
	 * The coarse conflict scope for the entity. Ignored while {@link #inherited()} is `true`.
	 */
	ConflictPolicy policy() default ConflictPolicy.ENTITY;

	/**
	 * The sub-entity granular refinements of the {@link ConflictPolicy#ENTITY} scope. Ignored while
	 * {@link #inherited()} is `true`; must be empty unless {@link #policy()} is {@link ConflictPolicy#ENTITY}.
	 */
	GranularConflictPolicy[] granularity() default {};

}
