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

package io.evitadb.externalApi.api.model;

import io.evitadb.externalApi.dataType.GenericObject;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * API-independent data type descriptor for {@link PropertyDescriptor} for primitive data types supported by evita.
 * There two additional types ({@link io.evitadb.externalApi.dataType.Any}, {@link GenericObject})
 * that represent more generic types needed by APIs to cover most of the type demands in descriptors.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record PrimitivePropertyDataTypeDescriptor(@Nonnull Class<? extends Serializable> javaType,
                                                  boolean nonNull) implements PropertyDataTypeDescriptor{


	@Nonnull
	public static PrimitivePropertyDataTypeDescriptor nullable(@Nonnull Class<? extends Serializable> javaType) {
		return new PrimitivePropertyDataTypeDescriptor(javaType, false);
	}

	@Nonnull
	public static PrimitivePropertyDataTypeDescriptor nonNull(@Nonnull Class<? extends Serializable> javaType) {
		return new PrimitivePropertyDataTypeDescriptor(javaType, true);
	}
}
