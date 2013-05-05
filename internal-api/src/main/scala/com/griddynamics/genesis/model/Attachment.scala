package com.griddynamics.genesis.model

class DBAttachment(val actionUUID: String, val attachmentType: String, val description: String, val link: Int)
  extends Attachment[Int] with GenesisEntity {
}

class DBAttachmentContent(val content: Array[Byte]) extends AttachmentContent[GenesisEntity.Id] with GenesisEntity

trait Attachment[B] {
  def actionUUID: String
  def attachmentType: String
  def description: String
  def link: B
}

trait AttachmentContent[B] {
  def id: B
  def content: Array[Byte]
}
