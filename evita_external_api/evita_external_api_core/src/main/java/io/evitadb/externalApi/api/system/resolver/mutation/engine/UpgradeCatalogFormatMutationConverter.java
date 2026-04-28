/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.api.system.resolver.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link EngineMutationConverter} for resolving {@link UpgradeCatalogFormatMutation}.
 *
 * This converter handles the conversion between the external API representation and the engine mutation that drives
 * a per-catalog lazy storage-protocol upgrade.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class UpgradeCatalogFormatMutationConverter
	extends EngineMutationConverter<UpgradeCatalogFormatMutation> {

	public UpgradeCatalogFormatMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<UpgradeCatalogFormatMutation> getMutationClass() {
		return UpgradeCatalogFormatMutation.class;
	}
}
