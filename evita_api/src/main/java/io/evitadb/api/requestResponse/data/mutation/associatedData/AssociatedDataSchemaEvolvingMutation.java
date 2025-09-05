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
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract parent for all associated data mutations that require schema validation / evolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true, exclude = "decisiveTimestamp")
public abstract class AssociatedDataSchemaEvolvingMutation extends AssociatedDataMutation implements SchemaEvolvingLocalMutation<AssociatedDataValue, AssociatedDataKey> {
	@Serial private static final long serialVersionUID = -1200943946647440138L;
	@Getter private final long decisiveTimestamp = System.nanoTime();

	protected AssociatedDataSchemaEvolvingMutation(@Nonnull AssociatedDataKey associatedDataKey) {
		super(associatedDataKey);
	}

	protected AssociatedDataSchemaEvolvingMutation(@Nonnull AssociatedDataKey associatedDataKey, long decisiveTimestamp) {
		super(associatedDataKey, decisiveTimestamp);
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
		return this.associatedDataKey;
	}

	@Override
	public void verifyOrEvolveSchema(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaBuilder entitySchemaBuilder
	) throws InvalidMutationException {
		verifyOrEvolveSchema(
			entitySchemaBuilder,
			getAssociatedDataValue(),
			(schemaBuilder) -> {
				if (this.associatedDataKey.localized()) {
					schemaBuilder.withLocale(this.associatedDataKey.locale());
				}
				schemaBuilder
					.withAssociatedData(
						this.associatedDataKey.associatedDataName(),
						getAssociatedDataValue().getClass(),
						whichIs -> whichIs.localized(this.associatedDataKey::localized).nullable()
					);
			}
		);
	}

	@Nonnull
	public abstract Serializable getAssociatedDataValue();

}
