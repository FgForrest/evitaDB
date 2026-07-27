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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation for configuring histogram computation on a reference. Histograms
 * allow computing distribution of referenced entities based on a value
 * expression, optionally filtered by a condition predicate.
 *
 * When used within {@link Reference} or {@link ScopeReferenceSettings}, it
 * defines a named histogram index with an optional condition and value
 * expression.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Histogram {

	/**
	 * Identifier of this histogram index within its enclosing (reference, scope) pair;
	 * must be unique within that pair. When this annotation appears inside a non-empty
	 * `bucketed = { ... }` array on {@link Reference} or {@link ScopeReferenceSettings},
	 * the analyzer rejects blank values.
	 */
	String nameOfTheIndex() default "";

	/**
	 * Expression yielding the numeric bucket value contributed by each referenced
	 * entity to this histogram. An empty expression (the default) means no custom
	 * expression is configured and evitaDB falls back to the default value extraction
	 * for the reference.
	 */
	Expression value() default @Expression;

	/**
	 * Partition selector. Among references already eligible (per
	 * {@link Reference#bucketedPartially()} / {@link ScopeReferenceSettings#bucketedPartially()}),
	 * this expression decides whether the referenced entity is assigned to this specific
	 * histogram. Multiple histograms on the same reference may declare overlapping or
	 * disjoint predicates; overlap is allowed but means a record participates in every
	 * histogram whose predicate evaluates to `true`. Mechanically the expression AND-combines
	 * with the eligibility gate at trigger-construction time, but conceptually the two roles
	 * are distinct: the gate decides participation, this predicate decides classification.
	 *
	 * An empty expression (the default) means no per-histogram restriction beyond the
	 * eligibility gate.
	 */
	Expression assignedWhen() default @Expression;

}
