/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.mutation.associatedData;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Upsert associatedData mutation will either update existing associatedData or create new one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class UpsertAssociatedDataMutation extends AssociatedDataSchemaEvolvingMutation {
	@Serial private static final long serialVersionUID = 2106367735845445016L;
	/**
	 * The value that is going to be set to the associated data.
	 */
	@Nonnull private final Serializable value;

	public UpsertAssociatedDataMutation(@Nonnull AssociatedDataKey associatedDataKey, @Nonnull Serializable value) {
		super(associatedDataKey);
		this.value = value;
	}

	public UpsertAssociatedDataMutation(@Nonnull String associatedDataName, @Nonnull Serializable value) {
		super(new AssociatedDataKey(associatedDataName));
		this.value = value;
	}

	public UpsertAssociatedDataMutation(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Serializable value) {
		super(new AssociatedDataKey(associatedDataName, locale));
		this.value = value;
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Nonnull
	@Override
	public Serializable getAssociatedDataValue() {
		return value;
	}

	@Nonnull
	@Override
	public AssociatedDataValue mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable AssociatedDataValue existingValue) {
		if (existingValue == null) {
			// create new associatedData value
			return new AssociatedDataValue(associatedDataKey, value);
		} else if (!Objects.equals(existingValue.getValue(), this.value)) {
			// update associatedData version (we changed it) and return mutated value
			return new AssociatedDataValue(existingValue.getVersion() + 1, associatedDataKey, this.value);
		} else {
			return existingValue;
		}
	}

	@Override
	public String toString() {
		return "upsert associated data: `" + associatedDataKey + "`";
	}

}
