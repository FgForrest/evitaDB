// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEnums.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * Protobuf enum {@code io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType}
 */
public enum GrpcCaptureResponseType
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <code>ACKNOWLEDGEMENT = 0;</code>
   */
  ACKNOWLEDGEMENT(0),
  /**
   * <code>CHANGE = 1;</code>
   */
  CHANGE(1),
  UNRECOGNIZED(-1),
  ;

  /**
   * <code>ACKNOWLEDGEMENT = 0;</code>
   */
  public static final int ACKNOWLEDGEMENT_VALUE = 0;
  /**
   * <code>CHANGE = 1;</code>
   */
  public static final int CHANGE_VALUE = 1;


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
  public static GrpcCaptureResponseType valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static GrpcCaptureResponseType forNumber(int value) {
    switch (value) {
      case 0: return ACKNOWLEDGEMENT;
      case 1: return CHANGE;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<GrpcCaptureResponseType>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      GrpcCaptureResponseType> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<GrpcCaptureResponseType>() {
          public GrpcCaptureResponseType findValueByNumber(int number) {
            return GrpcCaptureResponseType.forNumber(number);
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
    return io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor().getEnumTypes().get(22);
  }

  private static final GrpcCaptureResponseType[] VALUES = values();

  public static GrpcCaptureResponseType valueOf(
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

  private GrpcCaptureResponseType(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType)
}

