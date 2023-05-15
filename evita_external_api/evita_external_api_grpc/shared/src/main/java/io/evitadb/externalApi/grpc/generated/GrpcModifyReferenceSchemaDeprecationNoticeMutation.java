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
// source: GrpcReferenceSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation}
 */
public final class GrpcModifyReferenceSchemaDeprecationNoticeMutation extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation)
    GrpcModifyReferenceSchemaDeprecationNoticeMutationOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcModifyReferenceSchemaDeprecationNoticeMutation.newBuilder() to construct.
  private GrpcModifyReferenceSchemaDeprecationNoticeMutation(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcModifyReferenceSchemaDeprecationNoticeMutation() {
    name_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcModifyReferenceSchemaDeprecationNoticeMutation();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcModifyReferenceSchemaDeprecationNoticeMutation(
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
            java.lang.String s = input.readStringRequireUtf8();

            name_ = s;
            break;
          }
          case 18: {
            com.google.protobuf.StringValue.Builder subBuilder = null;
            if (deprecationNotice_ != null) {
              subBuilder = deprecationNotice_.toBuilder();
            }
            deprecationNotice_ = input.readMessage(com.google.protobuf.StringValue.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(deprecationNotice_);
              deprecationNotice_ = subBuilder.buildPartial();
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
    return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaDeprecationNoticeMutation_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaDeprecationNoticeMutation_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.class, io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.Builder.class);
  }

  public static final int NAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object name_;
  /**
   * <code>string name = 1;</code>
   * @return The name.
   */
  @java.lang.Override
  public java.lang.String getName() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      name_ = s;
      return s;
    }
  }
  /**
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getNameBytes() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      name_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int DEPRECATIONNOTICE_FIELD_NUMBER = 2;
  private com.google.protobuf.StringValue deprecationNotice_;
  /**
   * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
   * @return Whether the deprecationNotice field is set.
   */
  @java.lang.Override
  public boolean hasDeprecationNotice() {
    return deprecationNotice_ != null;
  }
  /**
   * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
   * @return The deprecationNotice.
   */
  @java.lang.Override
  public com.google.protobuf.StringValue getDeprecationNotice() {
    return deprecationNotice_ == null ? com.google.protobuf.StringValue.getDefaultInstance() : deprecationNotice_;
  }
  /**
   * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
   */
  @java.lang.Override
  public com.google.protobuf.StringValueOrBuilder getDeprecationNoticeOrBuilder() {
    return getDeprecationNotice();
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
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, name_);
    }
    if (deprecationNotice_ != null) {
      output.writeMessage(2, getDeprecationNotice());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, name_);
    }
    if (deprecationNotice_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, getDeprecationNotice());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation other = (io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation) obj;

    if (!getName()
        .equals(other.getName())) return false;
    if (hasDeprecationNotice() != other.hasDeprecationNotice()) return false;
    if (hasDeprecationNotice()) {
      if (!getDeprecationNotice()
          .equals(other.getDeprecationNotice())) return false;
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
    hash = (37 * hash) + NAME_FIELD_NUMBER;
    hash = (53 * hash) + getName().hashCode();
    if (hasDeprecationNotice()) {
      hash = (37 * hash) + DEPRECATIONNOTICE_FIELD_NUMBER;
      hash = (53 * hash) + getDeprecationNotice().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation prototype) {
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
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation)
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutationOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaDeprecationNoticeMutation_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaDeprecationNoticeMutation_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.class, io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.newBuilder()
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
      name_ = "";

      if (deprecationNoticeBuilder_ == null) {
        deprecationNotice_ = null;
      } else {
        deprecationNotice_ = null;
        deprecationNoticeBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaDeprecationNoticeMutation_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation build() {
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation result = new io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation(this);
      result.name_ = name_;
      if (deprecationNoticeBuilder_ == null) {
        result.deprecationNotice_ = deprecationNotice_;
      } else {
        result.deprecationNotice_ = deprecationNoticeBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation.getDefaultInstance()) return this;
      if (!other.getName().isEmpty()) {
        name_ = other.name_;
        onChanged();
      }
      if (other.hasDeprecationNotice()) {
        mergeDeprecationNotice(other.getDeprecationNotice());
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
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object name_ = "";
    /**
     * <code>string name = 1;</code>
     * @return The name.
     */
    public java.lang.String getName() {
      java.lang.Object ref = name_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        name_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string name = 1;</code>
     * @return The bytes for name.
     */
    public com.google.protobuf.ByteString
        getNameBytes() {
      java.lang.Object ref = name_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        name_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string name = 1;</code>
     * @param value The name to set.
     * @return This builder for chaining.
     */
    public Builder setName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      name_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string name = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearName() {

      name_ = getDefaultInstance().getName();
      onChanged();
      return this;
    }
    /**
     * <code>string name = 1;</code>
     * @param value The bytes for name to set.
     * @return This builder for chaining.
     */
    public Builder setNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      name_ = value;
      onChanged();
      return this;
    }

    private com.google.protobuf.StringValue deprecationNotice_;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.StringValue, com.google.protobuf.StringValue.Builder, com.google.protobuf.StringValueOrBuilder> deprecationNoticeBuilder_;
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     * @return Whether the deprecationNotice field is set.
     */
    public boolean hasDeprecationNotice() {
      return deprecationNoticeBuilder_ != null || deprecationNotice_ != null;
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     * @return The deprecationNotice.
     */
    public com.google.protobuf.StringValue getDeprecationNotice() {
      if (deprecationNoticeBuilder_ == null) {
        return deprecationNotice_ == null ? com.google.protobuf.StringValue.getDefaultInstance() : deprecationNotice_;
      } else {
        return deprecationNoticeBuilder_.getMessage();
      }
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    public Builder setDeprecationNotice(com.google.protobuf.StringValue value) {
      if (deprecationNoticeBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        deprecationNotice_ = value;
        onChanged();
      } else {
        deprecationNoticeBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    public Builder setDeprecationNotice(
        com.google.protobuf.StringValue.Builder builderForValue) {
      if (deprecationNoticeBuilder_ == null) {
        deprecationNotice_ = builderForValue.build();
        onChanged();
      } else {
        deprecationNoticeBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    public Builder mergeDeprecationNotice(com.google.protobuf.StringValue value) {
      if (deprecationNoticeBuilder_ == null) {
        if (deprecationNotice_ != null) {
          deprecationNotice_ =
            com.google.protobuf.StringValue.newBuilder(deprecationNotice_).mergeFrom(value).buildPartial();
        } else {
          deprecationNotice_ = value;
        }
        onChanged();
      } else {
        deprecationNoticeBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    public Builder clearDeprecationNotice() {
      if (deprecationNoticeBuilder_ == null) {
        deprecationNotice_ = null;
        onChanged();
      } else {
        deprecationNotice_ = null;
        deprecationNoticeBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    public com.google.protobuf.StringValue.Builder getDeprecationNoticeBuilder() {

      onChanged();
      return getDeprecationNoticeFieldBuilder().getBuilder();
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    public com.google.protobuf.StringValueOrBuilder getDeprecationNoticeOrBuilder() {
      if (deprecationNoticeBuilder_ != null) {
        return deprecationNoticeBuilder_.getMessageOrBuilder();
      } else {
        return deprecationNotice_ == null ?
            com.google.protobuf.StringValue.getDefaultInstance() : deprecationNotice_;
      }
    }
    /**
     * <code>.google.protobuf.StringValue deprecationNotice = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.StringValue, com.google.protobuf.StringValue.Builder, com.google.protobuf.StringValueOrBuilder>
        getDeprecationNoticeFieldBuilder() {
      if (deprecationNoticeBuilder_ == null) {
        deprecationNoticeBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.google.protobuf.StringValue, com.google.protobuf.StringValue.Builder, com.google.protobuf.StringValueOrBuilder>(
                getDeprecationNotice(),
                getParentForChildren(),
                isClean());
        deprecationNotice_ = null;
      }
      return deprecationNoticeBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation)
  private static final io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcModifyReferenceSchemaDeprecationNoticeMutation>
      PARSER = new com.google.protobuf.AbstractParser<GrpcModifyReferenceSchemaDeprecationNoticeMutation>() {
    @java.lang.Override
    public GrpcModifyReferenceSchemaDeprecationNoticeMutation parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcModifyReferenceSchemaDeprecationNoticeMutation(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcModifyReferenceSchemaDeprecationNoticeMutation> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcModifyReferenceSchemaDeprecationNoticeMutation> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

