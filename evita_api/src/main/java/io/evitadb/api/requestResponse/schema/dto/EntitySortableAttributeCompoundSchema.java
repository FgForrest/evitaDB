/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal implementation of {@link EntitySortableAttributeCompoundSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(callSuper = true)
public class EntitySortableAttributeCompoundSchema
	extends SortableAttributeCompoundSchema
	implements EntitySortableAttributeCompoundSchemaContract {

	@Serial private static final long serialVersionUID = 3279157595697674518L;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static EntitySortableAttributeCompoundSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Scope[] indexedInScopes,
		@Nonnull List<AttributeElement> attributeElements
	) {
		return new EntitySortableAttributeCompoundSchema(
			name,
			NamingConvention.generate(name),
			description,
			deprecationNotice,
			ArrayUtils.toEnumSet(Scope.class, indexedInScopes),
			attributeElements
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static EntitySortableAttributeCompoundSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Set<Scope> indexedInScopes,
		@Nonnull List<AttributeElement> attributeElements
	) {
		return new EntitySortableAttributeCompoundSchema(
			name,
			nameVariants,
			description,
			deprecationNotice,
			indexedInScopes,
			attributeElements
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static EntitySortableAttributeCompoundSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Scope[] indexedInScopes,
		@Nonnull List<AttributeElement> attributeElements
	) {
		return new EntitySortableAttributeCompoundSchema(
			name,
			nameVariants,
			description,
			deprecationNotice,
			ArrayUtils.toEnumSet(Scope.class, indexedInScopes),
			attributeElements
		);
	}

	public EntitySortableAttributeCompoundSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description, @Nullable String deprecationNotice,
		@Nullable Set<Scope> indexedInScopes,
		@Nonnull List<AttributeElement> attributeElements
	) {
		super(name, nameVariants, description, deprecationNotice, indexedInScopes, attributeElements);
	}
}
