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

package io.evitadb.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Indicates actual state in which Evita operates. See detailed information for each state.
 *
 * @author Stɇvɇn Kamenik (kamenik.stepan@gmail.cz) (c) 2021
 **/
@RequiredArgsConstructor
public enum CatalogState {

    /**
     * Initial state of the Evita catalog.
     * This state has several limitations but also advantages.
     *
     * This state requires single threaded access - this means only single thread can read/write data to the catalog
     * in this state. No transaction are allowed in this state and there are no guarantees on consistency of the catalog
     * if any of the WRITE operations fails. If any error is encountered while writing to the catalog in this state it is
     * strongly recommended discarding entire catalog contents and starts filling it from the scratch.
     *
     * Writing to the catalog in this phase is much faster than with transactional access. Operations are executed in bulk,
     * transactional logic is disabled and doesn't slow down the writing process.
     *
     * This phase is meant to quickly fill initial state of the catalog from the external primary data store.
     */
    WARMING_UP(false, false),

    /**
     * Standard "serving" state of the Evita catalog.
     * All operations are executed transactionally and leave the date in consistent state even if any error occurs.
     * Multiple readers and writers can work with the catalog simultaneously.
     */
    ALIVE(false, false),

    /**
     * State signalizing that evitaDB engine didn't load this catalog from the file system. The catalog data are present
     * on the file system, but they are not loaded into memory and the evitaDB engine is not able to serve any requests
     * for this catalog.
     */
    INACTIVE(false, false),

    /**
     * State signalizing that evitaDB engine was not able to consistently open and load this catalog from the file system.
     */
    CORRUPTED(false, false),

    /**
     * State signalizing that evitaDB engine is transitioning catalog from {@link #WARMING_UP} to {@link #ALIVE} state.
     * Until the transition is fully completed, the catalog is not able to serve any requests.
     */
    GOING_ALIVE(true, true),

    /**
     * State signalizing that evitaDB engine is loading catalog from the file system to the memory and performing
     * initialization of the catalog. The catalog is not able to serve any requests until the initialization is fully
     * completed.
     */
    BEING_ACTIVATED(true, false),

    /**
     * State signalizing that evitaDB engine is deactivating the catalog. When the operation is completed, the catalog
     * is moved to {@link #INACTIVE} state.
     */
    BEING_DEACTIVATED(true, true),

    /**
     * State signalizing that evitaDB engine is creating a new catalog. The catalog is not able to serve any requests
     * until the creation is fully completed.
     */
    BEING_CREATED(true, false),

    /**
     * State signalizing that evitaDB engine is deleting the catalog. When the operation is completed, the catalog
     * is removed from the file system and is no longer available.
     */
    BEING_DELETED(true, true);

    /**
     * Contains true if the state is transitional and should never be stored in persistent storage.
     */
    @Getter private final boolean transitional;

    /**
     * Contains true if the state is transitional and original state of the catalog is "active" (i.e. either WARMING_UP or ALIVE).
     */
    @Getter private final boolean active;

}
