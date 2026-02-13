/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Thrown when attempting to define an attribute or sortable attribute compound whose name, when converted
 * to a specific naming convention, conflicts with an existing attribute or sortable attribute compound in
 * the same entity or reference schema.
 *
 * evitaDB supports multiple naming conventions (camelCase, snake_case, UPPER_CASE, etc.) and automatically
 * generates names in all conventions from a canonical name. This exception is thrown when two different
 * canonical names produce the same name in at least one naming convention, which would make them
 * indistinguishable in that convention. The exception handles four conflict scenarios:
 * 1. Attribute vs. Attribute
 * 2. Attribute vs. Sortable Attribute Compound
 * 3. Sortable Attribute Compound vs. Attribute
 * 4. Sortable Attribute Compound vs. Sortable Attribute Compound
 *
 * **When this is thrown:**
 * - During entity/reference schema evolution when adding/modifying attributes or sortable compounds
 * - When two names map to the same name in a specific naming convention
 * - Thrown by `InternalEntitySchemaBuilder`, `ReferenceSchemaBuilder`, and `InternalSchemaBuilderHelper`
 *
 * **Example conflict:**
 * - Canonical name `user-name` produces `userName` in camelCase
 * - Canonical name `userName` also produces `userName` in camelCase
 * - These two would be indistinguishable when accessed via camelCase convention
 *
 * **Resolution:**
 * - Choose a different canonical name that doesn't conflict in any naming convention
 * - Check all naming convention variants of your proposed name against existing schemas
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeAlreadyPresentInEntitySchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 3705168736892244697L;
	/**
	 * Name of the catalog containing the entity schema (may be null for reference-level conflicts).
	 */
	@Getter private final String catalogName;
	/**
	 * The existing attribute schema that conflicts (null if conflict is with a sortable compound).
	 */
	@Getter private final AttributeSchemaContract existingAttributeSchema;
	/**
	 * The existing sortable attribute compound that conflicts (null if conflict is with an attribute).
	 */
	@Getter private final SortableAttributeCompoundSchemaContract existingAttributeCompoundSchema;

	/**
	 * Creates exception for attribute-vs-attribute name conflict.
	 */
	public AttributeAlreadyPresentInEntitySchemaException(
		@Nonnull AttributeSchemaContract existingAttribute,
		@Nonnull AttributeSchemaContract updatedAttribute,
		@Nullable NamingConvention convention,
		@Nonnull String conflictingName) {
		super("Attribute `" + updatedAttribute.getName() + "` and existing attribute `" + existingAttribute.getName() + "` produce the same name `" + conflictingName + "`" + (convention == null ? "" : " in `" + convention + "` convention") + "! Please choose different attribute name.");
		this.catalogName = null;
		this.existingAttributeSchema = existingAttribute;
		this.existingAttributeCompoundSchema = null;
	}

	/**
	 * Creates exception for sortable compound-vs-attribute name conflict.
	 */
	public AttributeAlreadyPresentInEntitySchemaException(
		@Nonnull SortableAttributeCompoundSchemaContract existingAttributeCompound,
		@Nonnull AttributeSchemaContract updatedAttribute,
		@Nullable NamingConvention convention,
		@Nonnull String conflictingName) {
		super("Attribute `" + updatedAttribute.getName() + "` and existing sortable attribute compound `" + existingAttributeCompound.getName() + "` produce the same name `" + conflictingName + "`" + (convention == null ? "" : " in `" + convention + "` convention") + "! Please choose different attribute name.");
		this.catalogName = null;
		this.existingAttributeSchema = null;
		this.existingAttributeCompoundSchema = existingAttributeCompound;
	}

	/**
	 * Creates exception for attribute-vs-sortable compound name conflict.
	 */
	public AttributeAlreadyPresentInEntitySchemaException(
		@Nonnull AttributeSchemaContract existingAttribute,
		@Nonnull SortableAttributeCompoundSchemaContract updatedAttributeCompound,
		@Nullable NamingConvention convention,
		@Nonnull String conflictingName) {
		super("Sortable attribute compound `" + updatedAttributeCompound.getName() + "` and existing attribute `" + existingAttribute.getName() + "` produce the same name `" + conflictingName + "`" + (convention == null ? "" : " in `" + convention + "` convention") + "`! Please choose different attribute name.");
		this.catalogName = null;
		this.existingAttributeSchema = existingAttribute;
		this.existingAttributeCompoundSchema = null;
	}

	/**
	 * Creates exception for sortable compound-vs-sortable compound name conflict.
	 */
	public AttributeAlreadyPresentInEntitySchemaException(
		@Nonnull SortableAttributeCompoundSchemaContract existingAttributeCompound,
		@Nonnull SortableAttributeCompoundSchemaContract updatedAttributeCompound,
		@Nullable NamingConvention convention,
		@Nonnull String conflictingName) {
		super("Sortable attribute compound `" + updatedAttributeCompound.getName() + "` and existing sortable attribute compound `" + existingAttributeCompound.getName() + "` produce the same name `" + conflictingName + "`" + (convention == null ? "" : " in `" + convention + "` convention") + "! Please choose different attribute name.");
		this.catalogName = null;
		this.existingAttributeSchema = null;
		this.existingAttributeCompoundSchema = existingAttributeCompound;
	}

}
