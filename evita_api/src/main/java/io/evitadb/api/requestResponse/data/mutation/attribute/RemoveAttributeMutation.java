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

package io.evitadb.api.requestResponse.data.mutation.attribute;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

import static java.util.Optional.ofNullable;

/**
 * Remove attribute mutation will drop existing attribute - ie.generates new version of the attribute with tombstone
 * on it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemoveAttributeMutation extends AttributeMutation {
	@Serial private static final long serialVersionUID = 7072678664245663785L;

	public RemoveAttributeMutation(@Nonnull AttributeKey attributeKey) {
		super(attributeKey);
	}

	public RemoveAttributeMutation(@Nonnull String attributeName) {
		super(new AttributeKey(attributeName));
	}

	public RemoveAttributeMutation(@Nonnull String attributeName, @Nonnull Locale locale) {
		super(new AttributeKey(attributeName, locale));
	}

	@Nonnull
	@Override
	public AttributeValue mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable AttributeValue existingValue) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException(
				"Cannot remove " + this.attributeKey.attributeName() + " attribute - it doesn't exist!"
			)
		);
		return new AttributeValue(
			existingValue.version() + 1,
			existingValue.key(),
			ofNullable(existingValue.value()).orElseThrow(),
			true
		);
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

	@Override
	public String toString() {
		return "remove attribute `" + this.attributeKey + "`";
	}

}
