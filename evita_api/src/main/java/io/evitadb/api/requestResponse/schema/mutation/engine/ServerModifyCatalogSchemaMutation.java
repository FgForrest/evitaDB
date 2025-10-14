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


import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This internal mutation wrapper is used to distinguish between client modify catalog schema mutation and the one that
 * has been already executed via transaction and should not be executed again on engine level.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ServerModifyCatalogSchemaMutation extends ModifyCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -7092436218734140016L;
	@Getter @Delegate private final ModifyCatalogSchemaMutation delegate;
	@Getter private final long catalogVersion;
	@Getter private final int schemaVersion;

	public ServerModifyCatalogSchemaMutation(
		long catalogVersion,
		int schemaVersion,
		@Nonnull ModifyCatalogSchemaMutation delegate
	) {
		super(delegate.getCatalogName(), delegate.getSessionId(), delegate.getSchemaMutations());
		this.catalogVersion = catalogVersion;
		this.schemaVersion = schemaVersion;
		this.delegate = delegate;
	}

}
