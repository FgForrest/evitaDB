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

package io.evitadb.api.requestResponse.schema.mutation;


import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Interface distinguishing mutations that directly modify reference schema properties (not reference attributes or
 * sortable compounds).
 *
 * **Purpose and Design Context**
 *
 * This interface marks mutations that alter the core structure of a {@link ReferenceSchemaContract} — properties like
 * cardinality, target entity type, indexing settings, faceting configuration, and reflected reference settings. It
 * explicitly excludes mutations that modify reference attributes (which implement
 * {@link io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation}) or reference
 * sortable compounds (which implement
 * {@link io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation}).
 *
 * **Key Behavioral Contract: The `mutate()` Method**
 *
 * This interface defines two `mutate()` overloads that accept a {@link ReferenceSchemaContract} and return a mutated
 * version:
 *
 * - **Create operations**: Accept `null` input and produce a non-`null` result (e.g.,
 *   {@link io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation})
 * - **Remove operations**: Accept non-`null` input and produce `null` result (e.g.,
 *   {@link io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation})
 * - **Modify operations**: Accept and return non-`null` values (e.g.,
 *   {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation})
 *
 * **Consistency Checks: Conditional Validation**
 *
 * The second `mutate()` overload accepts a `consistencyChecks` parameter that controls whether validation rules are
 * enforced. This is critical for reference schema mutations because certain operations (like setting an attribute as
 * sortable or filterable in a reference) require the reference itself to be indexed in the corresponding scope. During
 * schema builder operations, multiple mutations may be applied in sequence, and enforcing consistency checks on every
 * intermediate step can cause false validation failures.
 *
 * By passing `ConsistencyChecks.SKIP`, the builder can defer validation until all mutations are applied, ensuring
 * that the final schema state is consistent without rejecting valid intermediate states.
 *
 * **Common Implementations**
 *
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation}
 *
 * **Thread-Safety**
 *
 * All implementations are expected to be immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Immutable
@ThreadSafe
public interface ReferenceSchemaMutator {
	/**
	 * Applies the mutation to the reference schema and returns the modified version. This is a convenience overload
	 * that delegates to {@link #mutate(EntitySchemaContract, ReferenceSchemaContract, ConsistencyChecks)} with
	 * `ConsistencyChecks.APPLY`, enabling full validation.
	 *
	 * **Behavioral Contract:**
	 * - **Create mutations**: Accept `null` input, return non-`null` result
	 * - **Remove mutations**: Accept non-`null` input, return `null` result
	 * - **Modify mutations**: Accept and return non-`null` values
	 *
	 * @param entitySchema owner entity schema, used for validation and error messages (never `null`)
	 * @param referenceSchema current reference schema to mutate (`null` for create operations, non-`null` otherwise)
	 * @return mutated reference schema (`null` for remove operations, non-`null` otherwise)
	 */
	@Nullable
	default ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		return mutate(entitySchema, referenceSchema, ReferenceSchemaMutator.ConsistencyChecks.APPLY);
	}

	/**
	 * Applies the mutation to the reference schema and returns the modified version, with optional consistency check
	 * enforcement.
	 *
	 * **Behavioral Contract:**
	 * - **Create mutations**: Accept `null` input, return non-`null` result
	 * - **Remove mutations**: Accept non-`null` input, return `null` result
	 * - **Modify mutations**: Accept and return non-`null` values
	 *
	 * **Consistency Checks:**
	 *
	 * When `consistencyChecks` is `ConsistencyChecks.APPLY`, the mutation enforces validation rules (e.g., verifying
	 * that a reference is indexed before allowing sortable/filterable attributes). When `ConsistencyChecks.SKIP` is
	 * passed, validation is bypassed — this is used during schema builder operations where multiple mutations are
	 * applied sequentially and intermediate states may temporarily violate constraints.
	 *
	 * **Example:** Setting an attribute as sortable in a reference requires the reference to be indexed. If the
	 * builder applies "set reference indexed" followed by "set attribute sortable", the intermediate state (indexed
	 * reference but not-yet-sortable attribute) is valid. Skipping checks during intermediate steps and validating
	 * only the final result prevents false errors.
	 *
	 * @param entitySchema owner entity schema, used for validation and error messages (never `null`)
	 * @param referenceSchema current reference schema to mutate (`null` for create operations, non-`null` otherwise)
	 * @param consistencyChecks whether to enforce validation rules (APPLY) or skip them (SKIP)
	 * @return mutated reference schema (`null` for remove operations, non-`null` otherwise)
	 */
	@Nullable
	ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	);

	/**
	 * Controls whether consistency validation is enforced during reference schema mutations.
	 *
	 * **APPLY**: Enforce all validation rules. Use this for final mutation application or when mutations are applied
	 * individually outside a builder context.
	 *
	 * **SKIP**: Bypass validation checks. Use this during schema builder operations where multiple mutations are
	 * applied sequentially and intermediate states may temporarily violate constraints. Validation should be
	 * performed once at the end after all mutations are applied.
	 *
	 * **Common Use Cases for SKIP:**
	 * - {@link io.evitadb.api.requestResponse.schema.builder.ReferenceSchemaBuilder} applies multiple mutations in
	 *   sequence and needs to defer validation until the final schema is produced
	 * - Reflected reference schema builders use SKIP when the reflected reference is not yet available but will be
	 *   created in a later mutation
	 *
	 * **Example:**
	 * ```
	 * // Setting an attribute sortable requires the reference to be indexed
	 * reference.indexed(true);  // Apply with SKIP - not yet sortable
	 * reference.attribute("price").sortable(); // Apply with APPLY - now validate
	 * ```
	 */
	enum ConsistencyChecks {

		/**
		 * Enforce all validation rules during mutation. Use when applying mutations individually or when producing
		 * the final schema.
		 */
		APPLY,

		/**
		 * Skip validation checks during mutation. Use when applying multiple mutations sequentially in a builder
		 * where intermediate states may be invalid.
		 */
		SKIP

	}
}
