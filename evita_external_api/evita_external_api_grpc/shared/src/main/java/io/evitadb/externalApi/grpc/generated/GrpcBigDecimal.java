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
// source: GrpcEvitaDataTypes.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Representation of Java's BigDecimal class with arbitrary precision.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcBigDecimal}
 */
public final class GrpcBigDecimal extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcBigDecimal)
    GrpcBigDecimalOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcBigDecimal.newBuilder() to construct.
  private GrpcBigDecimal(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcBigDecimal() {
    value_ = com.google.protobuf.ByteString.EMPTY;
    valueString_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcBigDecimal();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcBigDecimal(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 8: {

            scale_ = input.readUInt32();
            break;
          }
          case 16: {

            precision_ = input.readUInt32();
            break;
          }
          case 26: {

            value_ = input.readBytes();
            break;
          }
          case 34: {
            java.lang.String s = input.readStringRequireUtf8();

            valueString_ = s;
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcBigDecimal_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcBigDecimal_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.class, io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.Builder.class);
  }

  public static final int SCALE_FIELD_NUMBER = 1;
  private int scale_;
  /**
   * <pre>
   * The unscaled value of the BigDecimal.
   * </pre>
   *
   * <code>uint32 scale = 1;</code>
   * @return The scale.
   */
  @java.lang.Override
  public int getScale() {
    return scale_;
  }

  public static final int PRECISION_FIELD_NUMBER = 2;
  private int precision_;
  /**
   * <pre>
   * The precision of the BigDecimal.
   * </pre>
   *
   * <code>uint32 precision = 2;</code>
   * @return The precision.
   */
  @java.lang.Override
  public int getPrecision() {
    return precision_;
  }

  public static final int VALUE_FIELD_NUMBER = 3;
  private com.google.protobuf.ByteString value_;
  /**
   * <pre>
   * The byte serialized value in integer form.
   * </pre>
   *
   * <code>bytes value = 3;</code>
   * @return The value.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString getValue() {
    return value_;
  }

  public static final int VALUESTRING_FIELD_NUMBER = 4;
  private volatile java.lang.Object valueString_;
  /**
   * <pre>
   * The string serialized value.
   * </pre>
   *
   * <code>string valueString = 4;</code>
   * @return The valueString.
   */
  @java.lang.Override
  public java.lang.String getValueString() {
    java.lang.Object ref = valueString_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      valueString_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * The string serialized value.
   * </pre>
   *
   * <code>string valueString = 4;</code>
   * @return The bytes for valueString.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getValueStringBytes() {
    java.lang.Object ref = valueString_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      valueString_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (scale_ != 0) {
      output.writeUInt32(1, scale_);
    }
    if (precision_ != 0) {
      output.writeUInt32(2, precision_);
    }
    if (!value_.isEmpty()) {
      output.writeBytes(3, value_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(valueString_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 4, valueString_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (scale_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeUInt32Size(1, scale_);
    }
    if (precision_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeUInt32Size(2, precision_);
    }
    if (!value_.isEmpty()) {
      size += com.google.protobuf.CodedOutputStream
        .computeBytesSize(3, value_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(valueString_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(4, valueString_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcBigDecimal)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcBigDecimal other = (io.evitadb.externalApi.grpc.generated.GrpcBigDecimal) obj;

    if (getScale()
        != other.getScale()) return false;
    if (getPrecision()
        != other.getPrecision()) return false;
    if (!getValue()
        .equals(other.getValue())) return false;
    if (!getValueString()
        .equals(other.getValueString())) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + SCALE_FIELD_NUMBER;
    hash = (53 * hash) + getScale();
    hash = (37 * hash) + PRECISION_FIELD_NUMBER;
    hash = (53 * hash) + getPrecision();
    hash = (37 * hash) + VALUE_FIELD_NUMBER;
    hash = (53 * hash) + getValue().hashCode();
    hash = (37 * hash) + VALUESTRING_FIELD_NUMBER;
    hash = (53 * hash) + getValueString().hashCode();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcBigDecimal prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Representation of Java's BigDecimal class with arbitrary precision.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcBigDecimal}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcBigDecimal)
      io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcBigDecimal_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcBigDecimal_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.class, io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      scale_ = 0;

      precision_ = 0;

      value_ = com.google.protobuf.ByteString.EMPTY;

      valueString_ = "";

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcBigDecimal_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcBigDecimal build() {
      io.evitadb.externalApi.grpc.generated.GrpcBigDecimal result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcBigDecimal buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcBigDecimal result = new io.evitadb.externalApi.grpc.generated.GrpcBigDecimal(this);
      result.scale_ = scale_;
      result.precision_ = precision_;
      result.value_ = value_;
      result.valueString_ = valueString_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcBigDecimal) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcBigDecimal)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcBigDecimal other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcBigDecimal.getDefaultInstance()) return this;
      if (other.getScale() != 0) {
        setScale(other.getScale());
      }
      if (other.getPrecision() != 0) {
        setPrecision(other.getPrecision());
      }
      if (other.getValue() != com.google.protobuf.ByteString.EMPTY) {
        setValue(other.getValue());
      }
      if (!other.getValueString().isEmpty()) {
        valueString_ = other.valueString_;
        onChanged();
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcBigDecimal parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcBigDecimal) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private int scale_ ;
    /**
     * <pre>
     * The unscaled value of the BigDecimal.
     * </pre>
     *
     * <code>uint32 scale = 1;</code>
     * @return The scale.
     */
    @java.lang.Override
    public int getScale() {
      return scale_;
    }
    /**
     * <pre>
     * The unscaled value of the BigDecimal.
     * </pre>
     *
     * <code>uint32 scale = 1;</code>
     * @param value The scale to set.
     * @return This builder for chaining.
     */
    public Builder setScale(int value) {
      
      scale_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The unscaled value of the BigDecimal.
     * </pre>
     *
     * <code>uint32 scale = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearScale() {
      
      scale_ = 0;
      onChanged();
      return this;
    }

    private int precision_ ;
    /**
     * <pre>
     * The precision of the BigDecimal.
     * </pre>
     *
     * <code>uint32 precision = 2;</code>
     * @return The precision.
     */
    @java.lang.Override
    public int getPrecision() {
      return precision_;
    }
    /**
     * <pre>
     * The precision of the BigDecimal.
     * </pre>
     *
     * <code>uint32 precision = 2;</code>
     * @param value The precision to set.
     * @return This builder for chaining.
     */
    public Builder setPrecision(int value) {
      
      precision_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The precision of the BigDecimal.
     * </pre>
     *
     * <code>uint32 precision = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearPrecision() {
      
      precision_ = 0;
      onChanged();
      return this;
    }

    private com.google.protobuf.ByteString value_ = com.google.protobuf.ByteString.EMPTY;
    /**
     * <pre>
     * The byte serialized value in integer form.
     * </pre>
     *
     * <code>bytes value = 3;</code>
     * @return The value.
     */
    @java.lang.Override
    public com.google.protobuf.ByteString getValue() {
      return value_;
    }
    /**
     * <pre>
     * The byte serialized value in integer form.
     * </pre>
     *
     * <code>bytes value = 3;</code>
     * @param value The value to set.
     * @return This builder for chaining.
     */
    public Builder setValue(com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      value_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The byte serialized value in integer form.
     * </pre>
     *
     * <code>bytes value = 3;</code>
     * @return This builder for chaining.
     */
    public Builder clearValue() {
      
      value_ = getDefaultInstance().getValue();
      onChanged();
      return this;
    }

    private java.lang.Object valueString_ = "";
    /**
     * <pre>
     * The string serialized value.
     * </pre>
     *
     * <code>string valueString = 4;</code>
     * @return The valueString.
     */
    public java.lang.String getValueString() {
      java.lang.Object ref = valueString_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        valueString_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * The string serialized value.
     * </pre>
     *
     * <code>string valueString = 4;</code>
     * @return The bytes for valueString.
     */
    public com.google.protobuf.ByteString
        getValueStringBytes() {
      java.lang.Object ref = valueString_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        valueString_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * The string serialized value.
     * </pre>
     *
     * <code>string valueString = 4;</code>
     * @param value The valueString to set.
     * @return This builder for chaining.
     */
    public Builder setValueString(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      valueString_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The string serialized value.
     * </pre>
     *
     * <code>string valueString = 4;</code>
     * @return This builder for chaining.
     */
    public Builder clearValueString() {
      
      valueString_ = getDefaultInstance().getValueString();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The string serialized value.
     * </pre>
     *
     * <code>string valueString = 4;</code>
     * @param value The bytes for valueString to set.
     * @return This builder for chaining.
     */
    public Builder setValueStringBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      valueString_ = value;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcBigDecimal)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcBigDecimal)
  private static final io.evitadb.externalApi.grpc.generated.GrpcBigDecimal DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcBigDecimal();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcBigDecimal>
      PARSER = new com.google.protobuf.AbstractParser<GrpcBigDecimal>() {
    @java.lang.Override
    public GrpcBigDecimal parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcBigDecimal(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcBigDecimal> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcBigDecimal> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

