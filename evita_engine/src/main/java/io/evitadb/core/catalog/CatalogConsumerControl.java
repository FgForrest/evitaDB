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

package io.evitadb.core.catalog;

import io.evitadb.api.CatalogVersionPin;

import javax.annotation.Nonnull;

/**
 * Interface allowing to hold a particular catalog version against reclamation for as long as something outside the
 * session lifecycle - a backup copying that version - still needs to read it, and to give that hold back afterwards.
 *
 * Sessions do not come through here. `SessionRegistry` pins and releases their versions itself, as one half of the
 * read-only/read-write consumer census it keeps; this interface exposes the pin alone, without that census.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CatalogConsumerControl {

	/**
	 * Holds a catalog version against reclamation without claiming to be a session at that version.
	 *
	 * A backup needs exactly the retention half of what a session registration does, and none of the rest of it.
	 * A session registration additionally enters the version in the consumer census `SessionRegistry` keeps - and
	 * since a full backup holds the *oldest* retained version, that phantom consumer would hold back conflict-key
	 * release and offset-index purging for the whole duration of the copy.
	 *
	 * The pin is handed back as a lease rather than by a matching `unpin(version)` call: a release that resolves the
	 * catalog by name a second time lands on whatever instance holds that name *then*, which after a rename or a
	 * replace is not the one that granted it. See {@link CatalogVersionPin}.
	 *
	 * @param version the version of the catalog that must remain readable
	 * @return the lease holding that version, to be closed exactly once when it is no longer needed
	 */
	@Nonnull
	CatalogVersionPin pinCatalogVersion(long version);

}
