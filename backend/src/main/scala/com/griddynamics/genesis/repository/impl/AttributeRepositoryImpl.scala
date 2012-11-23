/**
 *   Copyright (c) 2010-2012 Grid Dynamics Consulting Services, Inc, All Rights Reserved
 *   http://www.griddynamics.com
 *
 *   This library is free software; you can redistribute it and/or modify it under the terms of
 *   the GNU Lesser General Public License as published by the Free Software Foundation; either
 *   version 2.1 of the License, or any later version.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 *   AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 *   IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *   DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 *   FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 *   DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *   SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 *   OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 *   OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *   Project:     Genesis
 *   Description: Continuous Delivery Platform
 */
package com.griddynamics.genesis.repository.impl

import com.griddynamics.genesis.model.{SquerylEntityAttr, GenesisEntity}
import org.squeryl.Table
import org.springframework.transaction.annotation.Transactional
import org.squeryl.PrimitiveTypeMode._


trait AttributeRepository {
  def loadAttrs(entity: GenesisEntity, table: Table[SquerylEntityAttr]): Map[String, String]
  def setAttrs(entity: GenesisEntity, table: Table[SquerylEntityAttr], attrs: Map[String, String])
}


class AttributeRepositoryImpl extends AttributeRepository {

  @Transactional(readOnly = true)
  def loadAttrs(entity: GenesisEntity, table: Table[SquerylEntityAttr]) = {
    val attrs = from(table)(a => where(a.entityId === entity.id) select (a)).toList
    attrs.map(a => (a.name, a.value)).toMap
  }

  @Transactional
  def setAttrs(entity: GenesisEntity, table: Table[SquerylEntityAttr], attrs: Map[String, String]) {
    table.deleteWhere(a => a.entityId === entity.id)
    attrs.foreach{ case (name, value) => table.insert(new SquerylEntityAttr(entity.id, name, value)) }
  }
}