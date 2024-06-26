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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaDataTypes.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcBigDecimalNumberRangeOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRange)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The lower bound of the range.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal from = 1;</code>
   * @return Whether the from field is set.
   */
  boolean hasFrom();
  /**
   * <pre>
   * The lower bound of the range.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal from = 1;</code>
   * @return The from.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getFrom();
  /**
   * <pre>
   * The lower bound of the range.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal from = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder getFromOrBuilder();

  /**
   * <pre>
   * The upper bound of the range.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal to = 2;</code>
   * @return Whether the to field is set.
   */
  boolean hasTo();
  /**
   * <pre>
   * The upper bound of the range.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal to = 2;</code>
   * @return The to.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getTo();
  /**
   * <pre>
   * The upper bound of the range.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal to = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder getToOrBuilder();

  /**
   * <pre>
   * The number of decimal places to compare.
   * </pre>
   *
   * <code>int32 decimalPlacesToCompare = 3;</code>
   * @return The decimalPlacesToCompare.
   */
  int getDecimalPlacesToCompare();
}
