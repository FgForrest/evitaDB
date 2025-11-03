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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Record represents a CDC event that is sent to the subscriber if it matches to the request he made.
 *
 * @param version   the version of the evitaDB where the operation was performed
 * @param index     the index of the event within the enclosed block of operation, index 0 is the lead event of the process
 * @param operation the operation that was performed
 * @param body      optional body of the operation when it is requested by the {@link ChangeSystemCaptureRequest#content()}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeSystemCapture(
	long version,
	int index,
	@Nonnull Operation operation,
	@Nullable EngineMutation<?> body
) implements ChangeCapture {

	/**
	 * Creates a new {@link ChangeSystemCapture} instance of engine level mutation capture.
	 *
	 * @param context   the context of the mutation
	 * @param operation the operation that was performed
	 * @param mutation  the mutation that was performed
	 * @return the new instance of {@link ChangeSystemCapture}
	 */
	@Nonnull
	public static ChangeSystemCapture systemCapture(
		@Nonnull MutationPredicateContext context,
		@Nonnull Operation operation,
		@Nullable EngineMutation mutation
	) {
		return new ChangeSystemCapture(
			context.getVersion(),
			context.getIndex(),
			operation,
			mutation
		);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public ChangeSystemCapture as(@Nonnull ChangeCaptureContent content) throws EvitaInternalError {
		switch (content) {
			case BODY -> {
				Assert.isPremiseValid(this.body != null, "Body must be present in the capture!");
				return this;
			}
			case HEADER -> {
				// return body-less capture
				return this.body == null ?
					this :
					new ChangeSystemCapture(
						this.version,
						this.index,
						this.operation,
						null
					);
			}
			default -> throw new GenericEvitaInternalError("Unsupported content type: " + content);
		}
	}
}
