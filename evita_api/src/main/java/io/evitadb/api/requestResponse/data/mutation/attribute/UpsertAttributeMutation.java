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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.mutation.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Upsert attribute mutation will either update existing attribute or create new one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UpsertAttributeMutation extends AttributeSchemaEvolvingMutation {
	@Serial private static final long serialVersionUID = 4274174996930002364L;
	@Nonnull private final Serializable value;

	public UpsertAttributeMutation(@Nonnull AttributeKey attributeKey, @Nonnull Serializable value) {
		super(attributeKey);
		this.value = value;
	}

	public UpsertAttributeMutation(@Nonnull String attributeName, @Nonnull Serializable value) {
		super(new AttributeKey(attributeName));
		this.value = value;
	}

	public UpsertAttributeMutation(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Serializable value) {
		super(new AttributeKey(attributeName, locale));
		this.value = value;
	}

	@Override
	@Nonnull
	public Serializable getAttributeValue() {
		return value;
	}

	@Nonnull
	@Override
	public AttributeValue mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable AttributeValue existingValue) {
		if (existingValue == null) {
			// create new attribute value
			return new AttributeValue(attributeKey, value);
		} else if (!Objects.equals(existingValue.value(), this.value) || existingValue.dropped()) {
			// update attribute version (we changed it) and return mutated value
			return new AttributeValue(existingValue.version() + 1, attributeKey, this.value);
		} else {
			return existingValue;
		}
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;

		UpsertAttributeMutation that = (UpsertAttributeMutation) o;

		return value.getClass().isArray() ?
			that.value.getClass().isArray() && ArrayUtils.equals(value, that.value) : value.equals(that.value);
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result +
			(value.getClass().isArray() ? ArrayUtils.hashCode(value) : value.hashCode());
		return result;
	}

	@Override
	public String toString() {
		return "upsert attribute `" + attributeKey + "` with value: " + StringUtils.toString(value);
	}

}
