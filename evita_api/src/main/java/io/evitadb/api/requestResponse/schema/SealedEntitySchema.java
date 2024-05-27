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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;

/**
 * Sealed catalog schema is read only form of the schema that contains seal-breaking actions such as opening its
 * contents to write actions using {@link EntitySchemaBuilder} or accepting mutations that create
 * {@link EntitySchemaMutation} objects. All seal breaking actions don't modify {@link SealedEntitySchema} contents,
 * and only create new objects based on it. This keeps this class immutable and thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@Immutable
public interface SealedEntitySchema extends EntitySchemaContract {

	/**
	 * Opens entity for update - returns {@link EntitySchemaBuilder} that allows modification of the entity internals,
	 * and fabricates new immutable copy of the entity with altered data. Returned {@link EntitySchemaBuilder} is
	 * NOT THREAD SAFE.
	 *
	 * {@link EntitySchemaBuilder} doesn't alter contents of {@link SealedEntitySchema} but allows to create new version
	 * based on the version that is represented by this sealed entity.
	 */
	@Nonnull
	EntitySchemaBuilder openForWrite();

	/**
	 * Opens entity for update - returns {@link EntitySchemaBuilder} and incorporates the passed array of `schemaMutations`
	 * in the returned {@link EntitySchemaBuilder} right away. The builder allows modification of the entity internals and
	 * fabricates new immutable copy of the entity with altered data. Returned {@link EntitySchemaBuilder} is
	 * NOT THREAD SAFE.
	 *
	 * {@link EntitySchemaBuilder} doesn't alter contents of {@link SealedEntitySchema} but allows to create new version
	 * based on the version that is represented by this sealed entity.
	 */
	@Nonnull
	EntitySchemaBuilder withMutations(@Nonnull EntitySchemaMutation... schemaMutations);

	/**
	 * Opens entity for update - returns {@link EntitySchemaBuilder} and incorporates the passed collection
	 * of `schemaMutations` in the returned {@link EntitySchemaBuilder} right away. The builder allows modification
	 * of the entity internals and fabricates new immutable copy of the entity with altered data.
	 * Returned {@link EntitySchemaBuilder} is NOT THREAD SAFE.
	 *
	 * {@link EntitySchemaBuilder} doesn't alter contents of {@link SealedEntitySchema} but allows to create new version
	 * based on the version that is represented by this sealed entity.
	 */
	@Nonnull
	EntitySchemaBuilder withMutations(@Nonnull Collection<EntitySchemaMutation> schemaMutations);

}
