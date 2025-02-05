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

package io.evitadb.exception;


import javax.annotation.Nonnull;
import java.io.Serial;
import java.nio.file.Path;

/**
 * Exception thrown when a folder is already used by another process.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class FolderAlreadyUsedException extends UnexpectedIOException {
	@Serial private static final long serialVersionUID = 4060308329324745989L;

	public FolderAlreadyUsedException(@Nonnull Path folder) {
		super(
			"Folder " + folder + " is already used by another process.",
			"Folders used by another process cannot be used by the current process."
		);
	}

	public FolderAlreadyUsedException(@Nonnull Path folder, @Nonnull Throwable acquireStack) {
		super(
			"Folder " + folder + " is already used by another process.",
			"Folders used by another process cannot be used by the current process.",
			acquireStack
		);
	}

}
