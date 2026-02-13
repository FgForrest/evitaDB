/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api;

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;

import javax.annotation.Nonnull;

/**
 * Extension of {@link SchemaPostProcessor} that provides visibility into the final set of schema mutations generated
 * from the model class analysis and post-processing phase. This interface is useful for testing, auditing, or
 * forwarding schema mutations to remote evitaDB servers (e.g., in the gRPC client).
 *
 * **Purpose and Usage**
 *
 * After {@link SchemaPostProcessor#postProcess(io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder, io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder)}
 * completes, evitaDB computes the delta between the existing catalog schema and the modified builders, producing
 * a set of {@link LocalCatalogSchemaMutation} instances. This interface allows implementations to inspect or capture
 * those mutations before they are applied to the catalog.
 *
 * **When to Use**
 *
 * - **Client-Server Communication**: The {@link io.evitadb.driver.EvitaClientSession} uses this interface to capture
 * mutations generated locally and send them to the remote evitaDB server via gRPC, ensuring schema consistency
 * across client and server.
 * - **Testing**: Tests can capture mutations to verify that schema generation from annotated classes produces
 * the expected schema changes (see `ClassSchemaAnalyzerTest`).
 * - **Auditing**: Applications can log schema mutations for compliance or debugging purposes.
 *
 * **Invocation Order**
 *
 * 1. `postProcess()` is called first, allowing schema customization
 * 2. evitaDB computes schema mutations from the builders
 * 3. `captureResult()` is called with the final mutation array
 * 4. Mutations are applied to the catalog
 *
 * **Thread-Safety**
 *
 * The `captureResult()` method is invoked on the same thread as `postProcess()`, so no additional synchronization
 * is required within a single invocation.
 *
 * **Example Usage**
 *
 * ```
 * final List<LocalCatalogSchemaMutation> capturedMutations = new ArrayList<>();
 * session.defineEntitySchemaFromModelClass(
 * Product.class,
 * new SchemaPostProcessorCapturingResult() {
 * @Override
 * public void postProcess(CatalogSchemaBuilder catalogBuilder, EntitySchemaBuilder entityBuilder) {
 * entityBuilder.withAttribute("additionalField", String.class);
 * }
 *
 * @Override
 * public void captureResult(LocalCatalogSchemaMutation[] mutations) {
 * capturedMutations.addAll(Arrays.asList(mutations));
 * }
 * }
 * );
 * // capturedMutations now contains all mutations, including the additionalField attribute
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 * @see SchemaPostProcessor
 * @see io.evitadb.driver.EvitaClientSession
 * @see io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer
 */
public interface SchemaPostProcessorCapturingResult extends SchemaPostProcessor {

	/**
	 * Receives the final array of catalog schema mutations that will be applied to the catalog after the post-processing
	 * phase. This method is called after {@link #postProcess} completes and the schema delta is computed.
	 *
	 * Implementations should not modify the contents of the mutations array, as it represents the immutable set of
	 * changes to be applied. The mutations include both those generated from the annotated model class and any
	 * additional changes made in {@link #postProcess}.
	 *
	 * @param mutations array of catalog-level schema mutations (read-only — do not modify)
	 */
	void captureResult(@Nonnull LocalCatalogSchemaMutation[] mutations);

}
