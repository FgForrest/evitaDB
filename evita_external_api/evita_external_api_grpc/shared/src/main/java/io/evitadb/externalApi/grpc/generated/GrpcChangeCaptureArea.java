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
// source: GrpcChangeCapture.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * The enum defines what catalog area is covered by the capture.
 * </pre>
 *
 * Protobuf enum {@code io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureArea}
 */
public enum GrpcChangeCaptureArea
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <pre>
   * Changes in the schema are captured.
   * </pre>
   *
   * <code>SCHEMA = 0;</code>
   */
  SCHEMA(0),
  /**
   * <pre>
   * Changes in the data are captured.
   * </pre>
   *
   * <code>DATA = 1;</code>
   */
  DATA(1),
  /**
   * <pre>
   * Infrastructural mutations that are neither schema nor data.
   * </pre>
   *
   * <code>INFRASTRUCTURE = 2;</code>
   */
  INFRASTRUCTURE(2),
  UNRECOGNIZED(-1),
  ;

  /**
   * <pre>
   * Changes in the schema are captured.
   * </pre>
   *
   * <code>SCHEMA = 0;</code>
   */
  public static final int SCHEMA_VALUE = 0;
  /**
   * <pre>
   * Changes in the data are captured.
   * </pre>
   *
   * <code>DATA = 1;</code>
   */
  public static final int DATA_VALUE = 1;
  /**
   * <pre>
   * Infrastructural mutations that are neither schema nor data.
   * </pre>
   *
   * <code>INFRASTRUCTURE = 2;</code>
   */
  public static final int INFRASTRUCTURE_VALUE = 2;


  public final int getNumber() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalArgumentException(
          "Can't get the number of an unknown enum value.");
    }
    return value;
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   * @deprecated Use {@link #forNumber(int)} instead.
   */
  @java.lang.Deprecated
  public static GrpcChangeCaptureArea valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static GrpcChangeCaptureArea forNumber(int value) {
    switch (value) {
      case 0: return SCHEMA;
      case 1: return DATA;
      case 2: return INFRASTRUCTURE;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<GrpcChangeCaptureArea>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      GrpcChangeCaptureArea> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<GrpcChangeCaptureArea>() {
          public GrpcChangeCaptureArea findValueByNumber(int number) {
            return GrpcChangeCaptureArea.forNumber(number);
          }
        };

  public final com.google.protobuf.Descriptors.EnumValueDescriptor
      getValueDescriptor() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalStateException(
          "Can't get the descriptor of an unrecognized enum value.");
    }
    return getDescriptor().getValues().get(ordinal());
  }
  public final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptorForType() {
    return getDescriptor();
  }
  public static final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcChangeCapture.getDescriptor().getEnumTypes().get(0);
  }

  private static final GrpcChangeCaptureArea[] VALUES = values();

  public static GrpcChangeCaptureArea valueOf(
      com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
    if (desc.getType() != getDescriptor()) {
      throw new java.lang.IllegalArgumentException(
        "EnumValueDescriptor is not for this type.");
    }
    if (desc.getIndex() == -1) {
      return UNRECOGNIZED;
    }
    return VALUES[desc.getIndex()];
  }

  private final int value;

  private GrpcChangeCaptureArea(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureArea)
}

