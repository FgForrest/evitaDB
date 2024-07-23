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
 * Exception is thrown when client code tries to define the attribute with same name as existing catalog attribute.
 * This is not allowed and client must choose different name or reuse the already defined attribute on catalog level.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeAlreadyPresentInEntitySchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 3705168736892244697L;
	@Getter private final String catalogName;
	@Getter private final AttributeSchemaContract existingAttributeSchema;
	@Getter private final SortableAttributeCompoundSchemaContract existingAttributeCompoundSchema;

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
