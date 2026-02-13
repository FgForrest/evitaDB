/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a client attempts to access or modify a single reference by its
 * {@link ReferenceKey}, but the reference schema allows duplicates. When a reference schema
 * permits duplicates, multiple reference instances with the same referenced entity can exist on
 * the same entity, making it ambiguous which one to operate on.
 *
 * This exception is raised in the following scenarios:
 *
 * - **READ**: Attempting to retrieve a single reference using methods like
 * {@link io.evitadb.api.requestResponse.data.ReferencesContract#getReference(String, int)} when
 * the reference allows duplicates. Instead, use a method that returns a collection of references.
 * - **WRITE**: Attempting to modify a reference without specifying which instance to target, when
 * duplicates exist. Use a method that accepts a predicate to identify the specific instance.
 * - **WRITE_MULTIPLE_MATCHES**: A predicate-based modification matched multiple reference
 * instances. The predicate must be refined to match exactly one instance.
 * - **CREATE**: Internal inconsistency — attempting to create a new reference when duplicates are
 * not allowed but one already exists. This should not occur under normal circumstances.
 * - **MUTATE**: Attempting to apply a mutation without specifying the internal primary key of the
 * target reference instance when duplicates are allowed. The mutation must uniquely identify the
 * reference by its internal primary key.
 *
 * References that allow duplicates require more specific identification beyond just the referenced
 * entity name and ID. Clients must use collection-based accessors or specify disambiguating
 * criteria such as internal primary keys or predicates.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceAllowsDuplicatesException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -5464745624918378010L;

	/**
	 * Constructs a new exception indicating that a reference operation cannot proceed because
	 * the reference schema allows duplicates, and the requested operation assumes uniqueness.
	 *
	 * @param referenceName the name of the reference that allows duplicates
	 * @param entitySchema  the schema of the entity containing the reference
	 * @param operation     the type of operation that was attempted
	 */
	public ReferenceAllowsDuplicatesException(
		@Nonnull String referenceName, @Nonnull EntitySchemaContract entitySchema, @Nonnull Operation operation) {
		super(
			switch (operation) {
				case CREATE ->
					"Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` doesn't allow duplicates, but there is already one present - this is not expected at this moment!";
				case READ ->
					"Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates, you need to use a method returning a collection of references.";
				case WRITE ->
					"Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates, you need to use a method accepting predicate to select the correct instance.";
				case WRITE_MULTIPLE_MATCHES ->
					"Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates and there are multiple ones matching your predicate. Please narrow the predicate logic to match exactly one reference.";
				case MUTATE ->
					"Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates, you need to exactly specify reference using its internal primary key.";
			}
		);
	}

	/**
	 * Enumerates the types of operations that can be attempted on a reference, each of which
	 * may trigger a {@link ReferenceAllowsDuplicatesException} when the reference allows
	 * duplicates.
	 */
	public enum Operation {
		/**
		 * Reading a single reference instance when multiple duplicates may exist.
		 */
		READ,
		/**
		 * Writing/updating a reference without specifying which duplicate instance to target.
		 */
		WRITE,
		/**
		 * Writing/updating a reference with a predicate that matches multiple instances.
		 */
		WRITE_MULTIPLE_MATCHES,
		/**
		 * Creating a reference when duplicates are not allowed but one already exists.
		 */
		CREATE,
		/**
		 * Applying a mutation without specifying the internal primary key when duplicates exist.
		 */
		MUTATE
	}

}
