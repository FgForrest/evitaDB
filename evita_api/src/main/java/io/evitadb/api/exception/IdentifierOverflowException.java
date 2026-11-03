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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when an identifier sequence of evitaDB has handed out the last value its type can represent. The
 * sequence refuses to wrap around instead: a wrapped identifier collides with one that is still in use, or with a
 * sentinel such as "no value", and silently corrupts whatever is keyed by it. The refusal comes before the sequence or
 * anything it guards is changed, so the request that hit the limit fails and nothing else is affected.
 *
 * **Every sequence states its own remedy.** The public message names the exhausted identifier space and says what an
 * operator can do about it, because the answer differs from one sequence to another:
 *
 * - **A sequence that lives in memory only** - the transactional object version sequence, say - starts from the
 *   beginning after the server restarts, so a restart is the whole remedy.
 * - **A sequence recomputed on load from the identifiers still in use** resumes one above the largest of them, so a
 *   restart helps only when the identifiers at the top of the space have been released in the meantime.
 * - **A sequence persisted with the data it numbers** - entity, index and internal price primary keys, the key
 *   dictionary of a storage file, value ids of an attribute index - survives a restart unchanged and never reuses an
 *   identifier. Only rebuilding the data helps: inserting it into a new entity collection or catalog, which starts
 *   numbering from the beginning, and swapping that one in for the original.
 *
 * Reaching any of these limits takes billions of identifiers, so the exception usually signals a workload that burns
 * identifiers far faster than the data grows - repeatedly creating and dropping indexes or values, for instance - and
 * is worth investigating even after the remedy has been applied.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class IdentifierOverflowException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -9111617180591842555L;

	/**
	 * Creates the exception with a diagnostic message for the server log and a message for the client.
	 *
	 * There is deliberately no constructor with a default public message: no single piece of advice is correct for
	 * every sequence (see the class JavaDoc), so each refusal has to say what helps for the sequence it guards.
	 *
	 * @param privateMessage diagnostic message for the server log - the exhausted sequence, its position and its
	 *                       owner as precisely as the refusing code knows them
	 * @param publicMessage  message for the client naming the exhausted identifier space and what the operator can do
	 *                       about it
	 */
	public IdentifierOverflowException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}
}
