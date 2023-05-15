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
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange}
 */
public final class GrpcIntegerNumberRange extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange)
    GrpcIntegerNumberRangeOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcIntegerNumberRange.newBuilder() to construct.
  private GrpcIntegerNumberRange(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcIntegerNumberRange() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcIntegerNumberRange();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcIntegerNumberRange(
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
          case 10: {
            com.google.protobuf.Int32Value.Builder subBuilder = null;
            if (from_ != null) {
              subBuilder = from_.toBuilder();
            }
            from_ = input.readMessage(com.google.protobuf.Int32Value.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(from_);
              from_ = subBuilder.buildPartial();
            }

            break;
          }
          case 18: {
            com.google.protobuf.Int32Value.Builder subBuilder = null;
            if (to_ != null) {
              subBuilder = to_.toBuilder();
            }
            to_ = input.readMessage(com.google.protobuf.Int32Value.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(to_);
              to_ = subBuilder.buildPartial();
            }

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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcIntegerNumberRange_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcIntegerNumberRange_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.class, io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.Builder.class);
  }

  public static final int FROM_FIELD_NUMBER = 1;
  private com.google.protobuf.Int32Value from_;
  /**
   * <code>.google.protobuf.Int32Value from = 1;</code>
   * @return Whether the from field is set.
   */
  @java.lang.Override
  public boolean hasFrom() {
    return from_ != null;
  }
  /**
   * <code>.google.protobuf.Int32Value from = 1;</code>
   * @return The from.
   */
  @java.lang.Override
  public com.google.protobuf.Int32Value getFrom() {
    return from_ == null ? com.google.protobuf.Int32Value.getDefaultInstance() : from_;
  }
  /**
   * <code>.google.protobuf.Int32Value from = 1;</code>
   */
  @java.lang.Override
  public com.google.protobuf.Int32ValueOrBuilder getFromOrBuilder() {
    return getFrom();
  }

  public static final int TO_FIELD_NUMBER = 2;
  private com.google.protobuf.Int32Value to_;
  /**
   * <code>.google.protobuf.Int32Value to = 2;</code>
   * @return Whether the to field is set.
   */
  @java.lang.Override
  public boolean hasTo() {
    return to_ != null;
  }
  /**
   * <code>.google.protobuf.Int32Value to = 2;</code>
   * @return The to.
   */
  @java.lang.Override
  public com.google.protobuf.Int32Value getTo() {
    return to_ == null ? com.google.protobuf.Int32Value.getDefaultInstance() : to_;
  }
  /**
   * <code>.google.protobuf.Int32Value to = 2;</code>
   */
  @java.lang.Override
  public com.google.protobuf.Int32ValueOrBuilder getToOrBuilder() {
    return getTo();
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
    if (from_ != null) {
      output.writeMessage(1, getFrom());
    }
    if (to_ != null) {
      output.writeMessage(2, getTo());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (from_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getFrom());
    }
    if (to_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, getTo());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange other = (io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange) obj;

    if (hasFrom() != other.hasFrom()) return false;
    if (hasFrom()) {
      if (!getFrom()
          .equals(other.getFrom())) return false;
    }
    if (hasTo() != other.hasTo()) return false;
    if (hasTo()) {
      if (!getTo()
          .equals(other.getTo())) return false;
    }
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
    if (hasFrom()) {
      hash = (37 * hash) + FROM_FIELD_NUMBER;
      hash = (53 * hash) + getFrom().hashCode();
    }
    if (hasTo()) {
      hash = (37 * hash) + TO_FIELD_NUMBER;
      hash = (53 * hash) + getTo().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange prototype) {
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
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange)
      io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcIntegerNumberRange_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcIntegerNumberRange_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.class, io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.newBuilder()
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
      if (fromBuilder_ == null) {
        from_ = null;
      } else {
        from_ = null;
        fromBuilder_ = null;
      }
      if (toBuilder_ == null) {
        to_ = null;
      } else {
        to_ = null;
        toBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcIntegerNumberRange_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange build() {
      io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange result = new io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange(this);
      if (fromBuilder_ == null) {
        result.from_ = from_;
      } else {
        result.from_ = fromBuilder_.build();
      }
      if (toBuilder_ == null) {
        result.to_ = to_;
      } else {
        result.to_ = toBuilder_.build();
      }
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange.getDefaultInstance()) return this;
      if (other.hasFrom()) {
        mergeFrom(other.getFrom());
      }
      if (other.hasTo()) {
        mergeTo(other.getTo());
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
      io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private com.google.protobuf.Int32Value from_;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder> fromBuilder_;
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     * @return Whether the from field is set.
     */
    public boolean hasFrom() {
      return fromBuilder_ != null || from_ != null;
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     * @return The from.
     */
    public com.google.protobuf.Int32Value getFrom() {
      if (fromBuilder_ == null) {
        return from_ == null ? com.google.protobuf.Int32Value.getDefaultInstance() : from_;
      } else {
        return fromBuilder_.getMessage();
      }
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    public Builder setFrom(com.google.protobuf.Int32Value value) {
      if (fromBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        from_ = value;
        onChanged();
      } else {
        fromBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    public Builder setFrom(
        com.google.protobuf.Int32Value.Builder builderForValue) {
      if (fromBuilder_ == null) {
        from_ = builderForValue.build();
        onChanged();
      } else {
        fromBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    public Builder mergeFrom(com.google.protobuf.Int32Value value) {
      if (fromBuilder_ == null) {
        if (from_ != null) {
          from_ =
            com.google.protobuf.Int32Value.newBuilder(from_).mergeFrom(value).buildPartial();
        } else {
          from_ = value;
        }
        onChanged();
      } else {
        fromBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    public Builder clearFrom() {
      if (fromBuilder_ == null) {
        from_ = null;
        onChanged();
      } else {
        from_ = null;
        fromBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    public com.google.protobuf.Int32Value.Builder getFromBuilder() {

      onChanged();
      return getFromFieldBuilder().getBuilder();
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    public com.google.protobuf.Int32ValueOrBuilder getFromOrBuilder() {
      if (fromBuilder_ != null) {
        return fromBuilder_.getMessageOrBuilder();
      } else {
        return from_ == null ?
            com.google.protobuf.Int32Value.getDefaultInstance() : from_;
      }
    }
    /**
     * <code>.google.protobuf.Int32Value from = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder>
        getFromFieldBuilder() {
      if (fromBuilder_ == null) {
        fromBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder>(
                getFrom(),
                getParentForChildren(),
                isClean());
        from_ = null;
      }
      return fromBuilder_;
    }

    private com.google.protobuf.Int32Value to_;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder> toBuilder_;
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     * @return Whether the to field is set.
     */
    public boolean hasTo() {
      return toBuilder_ != null || to_ != null;
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     * @return The to.
     */
    public com.google.protobuf.Int32Value getTo() {
      if (toBuilder_ == null) {
        return to_ == null ? com.google.protobuf.Int32Value.getDefaultInstance() : to_;
      } else {
        return toBuilder_.getMessage();
      }
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    public Builder setTo(com.google.protobuf.Int32Value value) {
      if (toBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        to_ = value;
        onChanged();
      } else {
        toBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    public Builder setTo(
        com.google.protobuf.Int32Value.Builder builderForValue) {
      if (toBuilder_ == null) {
        to_ = builderForValue.build();
        onChanged();
      } else {
        toBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    public Builder mergeTo(com.google.protobuf.Int32Value value) {
      if (toBuilder_ == null) {
        if (to_ != null) {
          to_ =
            com.google.protobuf.Int32Value.newBuilder(to_).mergeFrom(value).buildPartial();
        } else {
          to_ = value;
        }
        onChanged();
      } else {
        toBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    public Builder clearTo() {
      if (toBuilder_ == null) {
        to_ = null;
        onChanged();
      } else {
        to_ = null;
        toBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    public com.google.protobuf.Int32Value.Builder getToBuilder() {

      onChanged();
      return getToFieldBuilder().getBuilder();
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    public com.google.protobuf.Int32ValueOrBuilder getToOrBuilder() {
      if (toBuilder_ != null) {
        return toBuilder_.getMessageOrBuilder();
      } else {
        return to_ == null ?
            com.google.protobuf.Int32Value.getDefaultInstance() : to_;
      }
    }
    /**
     * <code>.google.protobuf.Int32Value to = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder>
        getToFieldBuilder() {
      if (toBuilder_ == null) {
        toBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder>(
                getTo(),
                getParentForChildren(),
                isClean());
        to_ = null;
      }
      return toBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange)
  private static final io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcIntegerNumberRange>
      PARSER = new com.google.protobuf.AbstractParser<GrpcIntegerNumberRange>() {
    @java.lang.Override
    public GrpcIntegerNumberRange parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcIntegerNumberRange(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcIntegerNumberRange> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcIntegerNumberRange> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

