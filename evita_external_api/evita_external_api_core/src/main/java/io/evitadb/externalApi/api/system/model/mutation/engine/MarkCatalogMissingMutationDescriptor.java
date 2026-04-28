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

package io.evitadb.externalApi.api.system.model.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Descriptor for {@link MarkCatalogMissingMutation}.
 *
 * The mutation has no payload beyond the inherited `catalogName` property — it records the engine-side decision to
 * flip a catalog into the `MISSING` state once its on-disk folder disappears.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface MarkCatalogMissingMutationDescriptor extends EngineMutationDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(MarkCatalogMissingMutation.class)
		.description("""
			Mutation that marks a catalog as MISSING because its on-disk folder is no longer present.
			The catalog remains registered in the engine's on-disk state so that the divergence between what the
			engine knows about and what is actually on disk is visible to operators. The catalog cannot be used until
			it is either restored manually (by putting the expected folder back in place and restarting the engine)
			or removed explicitly via the normal catalog-removal mutation.
			""")
		.staticProperty(CATALOG_NAME)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("MarkCatalogMissingMutationInput")
		.build();
}
