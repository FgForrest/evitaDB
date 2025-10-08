evita.updateCatalog(
	"evita",
	session -> {
		// get existing product in a read-only form - safe for multi-threaded use
		final Product readOnlyInstance = session.getEntity(
			Product.class, 100, entityFetchAllContent()
		).orElseThrow();

		// now create a new instance that is open for write - not safe for multi-threaded use
		final ProductEditor readWriteInstance = readOnlyInstance.openForWrite();
		/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

		// then we can alter the data in it
		readWriteInstance.setCode("updated-code");
		// we can list all the mutations recorded in this instance by calling, it'll be empty if none were recorded
		final Optional<EntityMutation> entityMutation =  readWriteInstance.toMutation();
		// we can also create new read-only instance without storing the changes to the database
		final Product readOnlyInstanceWithChanges = readWriteInstance.toInstance();
		// or we can, send the mutations to back to the database and update the entity for all other clients
		readWriteInstance.upsertVia(session);
	}
);
