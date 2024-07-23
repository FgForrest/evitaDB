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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Internal implementation of {@link AssociatedDataSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public class AssociatedDataSchema implements Serializable, AssociatedDataSchemaContract {
	@Serial private static final long serialVersionUID = -995599294301442064L;

	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nonnull private final Class<? extends Serializable> plainType;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AssociatedDataSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static AssociatedDataSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable
	) {
		return new AssociatedDataSchema(
			name, description, deprecationNotice, type, localized, nullable
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AssociatedDataSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static AssociatedDataSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable
	) {
		return new AssociatedDataSchema(
			name, nameVariants,
			description, deprecationNotice, type, localized, nullable
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AssociatedDataSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static AssociatedDataSchemaContract _internalBuild(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type
	) {
		return new AssociatedDataSchema(
			name, null, null, type, false, false
		);
	}

	private AssociatedDataSchema(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable) {
		this(
			name, NamingConvention.generate(name),
			description, deprecationNotice, type, localized, nullable
		);
	}

	private AssociatedDataSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable
	) {
		this.name = name;
		this.nameVariants = nameVariants;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.type = EvitaDataTypes.isSupportedTypeOrItsArray(type) ?
			EvitaDataTypes.toWrappedForm(type) :
			ComplexDataObject.class;
		//noinspection unchecked
		this.plainType = (Class<? extends Serializable>) (this.type.isArray() ? this.type.getComponentType() : this.type);
		this.localized = localized;
		this.nullable = nullable;
	}

	@Override
	@Nonnull
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

}
