/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.engine;

/**
 * What a directory sitting under the storage root turns out to be, once the engine state has been consulted.
 *
 * The states are mutually exclusive by construction — {@link CatalogFolderClassifier} assigns exactly one — and
 * only two of them permit deletion. That asymmetry is the point: destroying a folder requires **positive evidence
 * that evitaDB itself owns it**, either a marker it wrote or a tombstone it recorded. Absence of evidence is never
 * enough, because the folder may be an operator's hand-placed import and its removal is unrecoverable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum CatalogFolderState {

	/**
	 * The engine state binds a catalog to this folder. It is loaded, and it is never removed regardless of what
	 * else the folder happens to contain — the binding is the sole authority on where a catalog lives.
	 */
	REFERENCED(false),

	/**
	 * Unreferenced, and carrying the marker written before a folder's contents exist and cleared before the
	 * binding commits. An operation died while materialising it, so nothing reachable was ever stored here.
	 */
	PROVISIONAL(true),

	/**
	 * Unreferenced, and named by a tombstone in the engine state. Its catalog moved on or was dropped; the delete
	 * was either deferred or previously failed, and is retried now.
	 */
	RETIRED(true),

	/**
	 * Unreferenced, suffix-free and holding a bootstrap file — the documented shape for hand-placing a catalog,
	 * and equally what an older evitaDB version left behind. Offered for adoption, never destroyed.
	 */
	FOREIGN(false),

	/**
	 * Unreferenced, holding a bootstrap file, but carrying a generation suffix — so it is shaped like something
	 * evitaDB allocated while nothing claims it. **Warned about and left alone**: it is most likely a folder
	 * copied in from another instance, and deleting it would be unrecoverable data loss.
	 */
	UNCLAIMED(false),

	/**
	 * Unreferenced and holding no bootstrap file, so no catalog can be read from it. Still left alone — the
	 * absence of a bootstrap proves only that *we* cannot use the folder, not that nobody else needs it.
	 */
	JUNK(false);

	/**
	 * Whether boot-time cleanup is permitted to remove a folder in this state.
	 */
	private final boolean deletable;

	CatalogFolderState(boolean deletable) {
		this.deletable = deletable;
	}

	/**
	 * Returns true when boot-time cleanup may delete a folder in this state.
	 *
	 * @return true if the folder may be removed
	 */
	public boolean isDeletable() {
		return this.deletable;
	}

}
