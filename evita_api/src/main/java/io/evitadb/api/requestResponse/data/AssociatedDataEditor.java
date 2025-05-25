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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Contract for classes that allow creating / updating or removing information in {@link AssociatedData} instance.
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link AssociatedDataContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AssociatedDataEditor<W extends AssociatedDataEditor<W>> extends AssociatedDataContract {

	/**
	 * Removes value associated with the key or null when the associatedData is missing.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	W removeAssociatedData(@Nonnull String associatedDataName);

	/**
	 * Stores value associated with the key.
	 * Setting null value effectively removes the associated data as if the {@link #removeAssociatedData(String)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAssociatedData(@Nonnull String associatedDataName, @Nullable T associatedDataValue);

	/**
	 * Stores array of values associated with the key.
	 * Setting null value effectively removes the associated data as if the {@link #removeAssociatedData(String)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAssociatedData(@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue);

	/**
	 * Removes locale specific value associated with the key or null when the associatedData is missing.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	W removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale);

	/**
	 * Stores locale specific value associated with the key.
	 * Setting null value effectively removes the associated data as if the {@link #removeAssociatedData(String, Locale)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue);

	/**
	 * Stores array of locale specific values associated with the key.
	 * Setting null value effectively removes the associated data as if the {@link #removeAssociatedData(String, Locale)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue);

	/**
	 * Alters associatedData value in a way defined by the passed mutation implementation.
	 * There may never me multiple mutations for the same associatedData - if you need to compose mutations you must wrap
	 * them into single one, that is then handed to the builder.
	 *
	 * Remember each setAssociatedData produces a mutation itself - so you cannot set associatedData and mutate it in the same
	 * round. The latter operation would overwrite the previously registered mutation.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	W mutateAssociatedData(@Nonnull AssociatedDataMutation mutation);

	/**
	 * Interface that simply combines writer and builder contracts together.
	 */
	interface AssociatedDataBuilder extends AssociatedDataEditor<AssociatedDataBuilder>, BuilderContract<AssociatedData> {

		@Nonnull
		@Override
		Stream<? extends AssociatedDataMutation> buildChangeSet();

		/**
		 * Method creates implicit associatedData type for the associatedData value that doesn't map to any existing
		 * (known) associatedData type of the {@link EntitySchemaContract} schema.
		 */
		@Nonnull
		static AssociatedDataSchemaContract createImplicitSchema(@Nonnull AssociatedDataValue associatedDataValue) {
			return AssociatedDataSchema._internalBuild(
				associatedDataValue.key().associatedDataName(),
				null, null,
				Objects.requireNonNull(associatedDataValue.value(), "Value is required for creating implicit associated data schema.").getClass(),
				associatedDataValue.key().localized(),
				true
			);
		}

	}

}
