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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api;

/**
 * Indicates actual state in which Evita operates. See detailed information for each state.
 *
 * @author Stɇvɇn Kamenik (kamenik.stepan@gmail.cz) (c) 2021
 **/
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
     * This phase is meant to quickly fill initial state of the catalog from the external primary data store. This state
     * is also planned to be used when new replica is created and needs to quickly catch up with the master.
     */
    WARMING_UP,

    /**
     * Standard "serving" state of the Evita catalog.
     * All operations are executed transactionally and leave the date in consistent state even if any error occurs.
     * Multiple readers and writers can work with the catalog simultaneously.
     */
    ALIVE

}
