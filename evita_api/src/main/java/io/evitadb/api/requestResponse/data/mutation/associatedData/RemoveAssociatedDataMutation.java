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

package io.evitadb.api.requestResponse.data.mutation.associatedData;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * Remove associated data mutation will drop existing associatedData - ie.generates new version of the associated data with tombstone
 * on it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemoveAssociatedDataMutation extends AssociatedDataMutation {
	@Serial private static final long serialVersionUID = 3777453666285515950L;

	public RemoveAssociatedDataMutation(@Nonnull AssociatedDataKey associatedDataKey) {
		super(associatedDataKey);
	}

	public RemoveAssociatedDataMutation(@Nonnull String associatedDataName) {
		super(new AssociatedDataKey(associatedDataName));
	}

	public RemoveAssociatedDataMutation(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		super(new AssociatedDataKey(associatedDataName, locale));
	}

	private RemoveAssociatedDataMutation(@Nonnull AssociatedDataKey associatedDataKey, long decisiveTimestamp) {
		super(associatedDataKey, decisiveTimestamp);
	}

	@Nonnull
	@Override
	public AssociatedDataValue mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable AssociatedDataValue existingValue) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException(
				"Cannot remove " + this.associatedDataKey.associatedDataName() +
					" associated data - it doesn't exist!"
			)
		);
		return new AssociatedDataValue(existingValue.version() + 1, existingValue.key(), existingValue.value(), true);
	}

	@Override
	public long getPriority() {
		return PRIORITY_REMOVAL;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new RemoveAssociatedDataMutation(this.associatedDataKey, newDecisiveTimestamp);
	}

	@Override
	public String toString() {
		return "remove associated data: `" + this.associatedDataKey + "`";
	}
}
