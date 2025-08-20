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
import io.evitadb.dataType.NumberRange;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Increments or decrements existing numeric value by specified delta (negative number produces decremental of
 * existing number, positive one incrementation).
 *
 * Allows to specify the number range that is tolerated for the value after delta application has been finished to
 * verify for example that number of items on stock doesn't go below zero.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class ApplyDeltaAttributeMutation<T extends Number> extends AttributeSchemaEvolvingMutation {
	@Serial private static final long serialVersionUID = -107926476337012921L;
	/**
	 * Delta value that should be applied to the existing attribute.
	 * Delta is added to the existing number, negative delta decreases it, positive delta increases it.
	 */
	@Getter private final T delta;
	/**
	 * Required number inclusive range that needs to be valid for the attribute value after the delta has been applied.
	 * Might be NULL if no check is necessary. When the value is outside the requested range mutation is not applied
	 * and exception is thrown that eventually results in transaction rollback.
	 */
	@Nullable
	@Getter private final NumberRange<T> requiredRangeAfterApplication;

	public ApplyDeltaAttributeMutation(@Nonnull AttributeKey attributeKey, @Nonnull T delta) {
		super(attributeKey);
		this.delta = delta;
		this.requiredRangeAfterApplication = null;
	}

	public ApplyDeltaAttributeMutation(@Nonnull String attributeName, @Nonnull T delta) {
		super(new AttributeKey(attributeName));
		this.delta = delta;
		this.requiredRangeAfterApplication = null;
	}

	public ApplyDeltaAttributeMutation(@Nonnull String attributeName, @Nullable Locale locale, @Nonnull T delta) {
		super(new AttributeKey(attributeName, locale));
		this.delta = delta;
		this.requiredRangeAfterApplication = null;
	}

	public ApplyDeltaAttributeMutation(@Nonnull AttributeKey attributeKey, @Nonnull T delta, @Nullable NumberRange<T> requiredRangeAfterApplication) {
		super(attributeKey);
		this.delta = delta;
		this.requiredRangeAfterApplication = requiredRangeAfterApplication;
	}

	public ApplyDeltaAttributeMutation(@Nonnull String attributeName, @Nonnull T delta, @Nullable NumberRange<T> requiredRangeAfterApplication) {
		super(new AttributeKey(attributeName));
		this.delta = delta;
		this.requiredRangeAfterApplication = requiredRangeAfterApplication;
	}

	public ApplyDeltaAttributeMutation(@Nonnull String attributeName, @Nullable Locale locale, @Nonnull T delta, @Nullable NumberRange<T> requiredRangeAfterApplication) {
		super(new AttributeKey(attributeName, locale));
		this.delta = delta;
		this.requiredRangeAfterApplication = requiredRangeAfterApplication;
	}

	@Override
	@Nonnull
	public T getAttributeValue() {
		return this.delta;
	}

	@Nonnull
	@Override
	public AttributeValue mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable AttributeValue existingAttributeValue) {
		Assert.isTrue(
			existingAttributeValue != null && existingAttributeValue.exists() && existingAttributeValue.value() != null,
			"Cannot apply delta to attribute " + this.attributeKey.attributeName() + " when it doesn't exist!"
		);
		Assert.isTrue(
				existingAttributeValue.value() instanceof Number,
				"Cannot apply delta to attribute " + this.attributeKey.attributeName() + " when its value is " +
						existingAttributeValue.value().getClass().getName()
		);
		final Number existingValue = (Number) existingAttributeValue.value();
		final T newValue;
		if (existingValue instanceof BigDecimal) {
			//noinspection unchecked
			newValue = (T) ((BigDecimal) existingValue).add((BigDecimal) this.delta);
		} else if (existingValue instanceof Byte) {
			//noinspection unchecked
			newValue = (T) (Byte.valueOf((byte)((byte) existingValue + (byte) this.delta)));
		} else if (existingValue instanceof Short) {
			//noinspection unchecked
			newValue = (T) (Short.valueOf((short) ((short)existingValue + (short) this.delta)));
		} else if (existingValue instanceof Integer) {
			//noinspection unchecked
			newValue = (T) (Integer.valueOf(((int)existingValue + (int) this.delta)));
		} else if (existingValue instanceof Long) {
			//noinspection unchecked
			newValue = (T) (Long.valueOf(((long)existingValue + (long) this.delta)));
		} else {
			// this should never ever happen
			throw new InvalidMutationException("Unknown Evita data type: " + existingValue.getClass());
		}
		if (this.requiredRangeAfterApplication != null) {
			Assert.isTrue(
				this.requiredRangeAfterApplication.isWithin(newValue),
				() -> new InvalidMutationException(
					"Applying delta " + this.delta + " on " + existingValue + " produced result " + newValue +
						" which is out of specified range " + this.requiredRangeAfterApplication + "!"
				)
			);
		}
		return new AttributeValue(existingAttributeValue.version() + 1, this.attributeKey, newValue);
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "apply delta " + this.delta + " to attribute `" + this.attributeKey + "`";
	}

}
