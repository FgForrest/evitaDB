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

/**
 * <pre>
 * Structure for representing Predecessor objects.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcPredecessor}
 */
public final class GrpcPredecessor extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcPredecessor)
    GrpcPredecessorOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcPredecessor.newBuilder() to construct.
  private GrpcPredecessor(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcPredecessor() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcPredecessor();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcPredecessor(
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

            head_ = input.readBool();
            break;
          }
          case 18: {
            com.google.protobuf.Int32Value.Builder subBuilder = null;
            if (predecessorId_ != null) {
              subBuilder = predecessorId_.toBuilder();
            }
            predecessorId_ = input.readMessage(com.google.protobuf.Int32Value.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(predecessorId_);
              predecessorId_ = subBuilder.buildPartial();
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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcPredecessor_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcPredecessor_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcPredecessor.class, io.evitadb.externalApi.grpc.generated.GrpcPredecessor.Builder.class);
  }

  public static final int HEAD_FIELD_NUMBER = 1;
  private boolean head_;
  /**
   * <pre>
   * true if predecessor is a head, false otherwise
   * </pre>
   *
   * <code>bool head = 1;</code>
   * @return The head.
   */
  @java.lang.Override
  public boolean getHead() {
    return head_;
  }

  public static final int PREDECESSORID_FIELD_NUMBER = 2;
  private com.google.protobuf.Int32Value predecessorId_;
  /**
   * <pre>
   * Value that supports storing a Predecessor.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
   * @return Whether the predecessorId field is set.
   */
  @java.lang.Override
  public boolean hasPredecessorId() {
    return predecessorId_ != null;
  }
  /**
   * <pre>
   * Value that supports storing a Predecessor.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
   * @return The predecessorId.
   */
  @java.lang.Override
  public com.google.protobuf.Int32Value getPredecessorId() {
    return predecessorId_ == null ? com.google.protobuf.Int32Value.getDefaultInstance() : predecessorId_;
  }
  /**
   * <pre>
   * Value that supports storing a Predecessor.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
   */
  @java.lang.Override
  public com.google.protobuf.Int32ValueOrBuilder getPredecessorIdOrBuilder() {
    return getPredecessorId();
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
    if (head_ != false) {
      output.writeBool(1, head_);
    }
    if (predecessorId_ != null) {
      output.writeMessage(2, getPredecessorId());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (head_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(1, head_);
    }
    if (predecessorId_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, getPredecessorId());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcPredecessor)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcPredecessor other = (io.evitadb.externalApi.grpc.generated.GrpcPredecessor) obj;

    if (getHead()
        != other.getHead()) return false;
    if (hasPredecessorId() != other.hasPredecessorId()) return false;
    if (hasPredecessorId()) {
      if (!getPredecessorId()
          .equals(other.getPredecessorId())) return false;
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
    hash = (37 * hash) + HEAD_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getHead());
    if (hasPredecessorId()) {
      hash = (37 * hash) + PREDECESSORID_FIELD_NUMBER;
      hash = (53 * hash) + getPredecessorId().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcPredecessor prototype) {
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
   * Structure for representing Predecessor objects.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcPredecessor}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcPredecessor)
      io.evitadb.externalApi.grpc.generated.GrpcPredecessorOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcPredecessor_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcPredecessor_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcPredecessor.class, io.evitadb.externalApi.grpc.generated.GrpcPredecessor.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcPredecessor.newBuilder()
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
      head_ = false;

      if (predecessorIdBuilder_ == null) {
        predecessorId_ = null;
      } else {
        predecessorId_ = null;
        predecessorIdBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.internal_static_io_evitadb_externalApi_grpc_generated_GrpcPredecessor_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcPredecessor getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcPredecessor.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcPredecessor build() {
      io.evitadb.externalApi.grpc.generated.GrpcPredecessor result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcPredecessor buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcPredecessor result = new io.evitadb.externalApi.grpc.generated.GrpcPredecessor(this);
      result.head_ = head_;
      if (predecessorIdBuilder_ == null) {
        result.predecessorId_ = predecessorId_;
      } else {
        result.predecessorId_ = predecessorIdBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcPredecessor) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcPredecessor)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcPredecessor other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcPredecessor.getDefaultInstance()) return this;
      if (other.getHead() != false) {
        setHead(other.getHead());
      }
      if (other.hasPredecessorId()) {
        mergePredecessorId(other.getPredecessorId());
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
      io.evitadb.externalApi.grpc.generated.GrpcPredecessor parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcPredecessor) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private boolean head_ ;
    /**
     * <pre>
     * true if predecessor is a head, false otherwise
     * </pre>
     *
     * <code>bool head = 1;</code>
     * @return The head.
     */
    @java.lang.Override
    public boolean getHead() {
      return head_;
    }
    /**
     * <pre>
     * true if predecessor is a head, false otherwise
     * </pre>
     *
     * <code>bool head = 1;</code>
     * @param value The head to set.
     * @return This builder for chaining.
     */
    public Builder setHead(boolean value) {

      head_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * true if predecessor is a head, false otherwise
     * </pre>
     *
     * <code>bool head = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearHead() {

      head_ = false;
      onChanged();
      return this;
    }

    private com.google.protobuf.Int32Value predecessorId_;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder> predecessorIdBuilder_;
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     * @return Whether the predecessorId field is set.
     */
    public boolean hasPredecessorId() {
      return predecessorIdBuilder_ != null || predecessorId_ != null;
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     * @return The predecessorId.
     */
    public com.google.protobuf.Int32Value getPredecessorId() {
      if (predecessorIdBuilder_ == null) {
        return predecessorId_ == null ? com.google.protobuf.Int32Value.getDefaultInstance() : predecessorId_;
      } else {
        return predecessorIdBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    public Builder setPredecessorId(com.google.protobuf.Int32Value value) {
      if (predecessorIdBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        predecessorId_ = value;
        onChanged();
      } else {
        predecessorIdBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    public Builder setPredecessorId(
        com.google.protobuf.Int32Value.Builder builderForValue) {
      if (predecessorIdBuilder_ == null) {
        predecessorId_ = builderForValue.build();
        onChanged();
      } else {
        predecessorIdBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    public Builder mergePredecessorId(com.google.protobuf.Int32Value value) {
      if (predecessorIdBuilder_ == null) {
        if (predecessorId_ != null) {
          predecessorId_ =
            com.google.protobuf.Int32Value.newBuilder(predecessorId_).mergeFrom(value).buildPartial();
        } else {
          predecessorId_ = value;
        }
        onChanged();
      } else {
        predecessorIdBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    public Builder clearPredecessorId() {
      if (predecessorIdBuilder_ == null) {
        predecessorId_ = null;
        onChanged();
      } else {
        predecessorId_ = null;
        predecessorIdBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    public com.google.protobuf.Int32Value.Builder getPredecessorIdBuilder() {

      onChanged();
      return getPredecessorIdFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    public com.google.protobuf.Int32ValueOrBuilder getPredecessorIdOrBuilder() {
      if (predecessorIdBuilder_ != null) {
        return predecessorIdBuilder_.getMessageOrBuilder();
      } else {
        return predecessorId_ == null ?
            com.google.protobuf.Int32Value.getDefaultInstance() : predecessorId_;
      }
    }
    /**
     * <pre>
     * Value that supports storing a Predecessor.
     * </pre>
     *
     * <code>.google.protobuf.Int32Value predecessorId = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder>
        getPredecessorIdFieldBuilder() {
      if (predecessorIdBuilder_ == null) {
        predecessorIdBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.google.protobuf.Int32Value, com.google.protobuf.Int32Value.Builder, com.google.protobuf.Int32ValueOrBuilder>(
                getPredecessorId(),
                getParentForChildren(),
                isClean());
        predecessorId_ = null;
      }
      return predecessorIdBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcPredecessor)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcPredecessor)
  private static final io.evitadb.externalApi.grpc.generated.GrpcPredecessor DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcPredecessor();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcPredecessor getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcPredecessor>
      PARSER = new com.google.protobuf.AbstractParser<GrpcPredecessor>() {
    @java.lang.Override
    public GrpcPredecessor parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcPredecessor(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcPredecessor> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcPredecessor> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcPredecessor getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

