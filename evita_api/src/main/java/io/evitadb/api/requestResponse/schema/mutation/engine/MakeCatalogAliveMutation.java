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

package io.evitadb.api.requestResponse.schema.mutation.engine;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * Mutation that transitions a catalog to the "live" state, making it transactional.
 *
 * When a catalog goes live, it becomes fully operational and can participate in transactions.
 * This is a one-way operation that changes the catalog's operational state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class MakeCatalogAliveMutation implements TopLevelCatalogMutation {
	@Serial private static final long serialVersionUID = 1L;
	@Nonnull @Getter private final String catalogName;

	/**
	 * Creates a new mutation that will transition the specified catalog to the "live" state.
	 *
	 * @param catalogName name of the catalog to transition
	 */
	public MakeCatalogAliveMutation(@Nonnull String catalogName) {
		this.catalogName = catalogName;
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (!evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
		}
		if (!evita.getCatalogState(this.catalogName).map(it -> it == CatalogState.WARMING_UP).orElse(false)) {
			throw new InvalidMutationException("Catalog `" + this.catalogName + "` is not in warming up state and cannot be transitioned to live state!");
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Transition catalog `" + this.catalogName + "` to live state";
	}
}
