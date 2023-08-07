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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcOpenTransactionResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcOpenTransactionResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * True, if the transaction was opened before corresponding request.
   * </pre>
   *
   * <code>bool alreadyOpenedBefore = 1;</code>
   * @return The alreadyOpenedBefore.
   */
  boolean getAlreadyOpenedBefore();

  /**
   * <pre>
   * The id of the opened transaction.
   * </pre>
   *
   * <code>int64 transactionId = 2;</code>
   * @return The transactionId.
   */
  long getTransactionId();
}
