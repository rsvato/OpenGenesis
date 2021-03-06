/*
 * Copyright (c) 2010-2012 Grid Dynamics Consulting Services, Inc, All Rights Reserved
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
 *   Description:  Continuous Delivery Platform
 */

package com.griddynamics.genesis.service.impl

import com.griddynamics.genesis.service
import com.griddynamics.genesis.api._
import com.griddynamics.genesis.repository.{DatabagTemplateRepository, ConfigurationRepository}
import org.springframework.security.core.context.SecurityContextHolder
import java.security.Principal
import com.griddynamics.genesis.validation.{Validation, ConfigValueValidator}
import com.griddynamics.genesis.api.Configuration
import com.griddynamics.genesis.api.Success
import com.griddynamics.genesis.api.Failure
import scala.Some

class EnvironmentConfigurationServiceImpl(repository: ConfigurationRepository, accessService: service.EnvironmentAccessService,
                                          override val templates: DatabagTemplateRepository, override val validations: Map[String, ConfigValueValidator])
  extends service.EnvironmentConfigurationService with TemplateValidator with Validation[Configuration] {

  private def getAuth = SecurityContextHolder.getContext.getAuthentication

  // TODO: how should we properly detect that call is out of security context(i. e. from dispatcher)?
  private def noAuth = (getAuth == null)

  private def getCurrentUser = getAuth match {
    case p: Principal => p.getName
    case _ => throw new IllegalStateException("User must have been authenticated already")
  }

  private def getCurrentUserAuthorities = {
    import scala.collection.JavaConversions._
    getAuth.getAuthorities.map (_.getAuthority)
  }

  private def checkAccess(projectId: Int, conf: Configuration) = {
    val confId = conf.id.getOrElse(throw new IllegalArgumentException("Configuration must have id"))
    if(noAuth || accessService.hasAccessToConfig(projectId, confId, getCurrentUser, getCurrentUserAuthorities))
      Option(conf)
    else None
  }

  private def hasAccessToAllEnvs(projectId: Int) = noAuth || accessService.hasAccessToAllConfigs(projectId, getCurrentUser, getCurrentUserAuthorities)

  def getDefault(projectId: Int) = list(projectId).headOption

  def list(projectId: Int) = {
    val allConfigs = repository.list(projectId)
    if (!hasAccessToAllEnvs(projectId)) {
      val permittedIds = accessService.listAccessibleConfigurations(getCurrentUser, getCurrentUserAuthorities).toSet

      allConfigs.filter { _.id.map (permittedIds.contains).getOrElse(false) }
    } else {
      allConfigs
    }
  }

  def get(projectId: Int, configId: Int) = repository.get(projectId, configId).map(checkAccess(projectId, _))
  .getOrElse(None)

  protected def validateUpdate(c: Configuration) = {
    commonValidation(c)
  }

  protected def validateCreation(c: Configuration) = {
    commonValidation(c)
  }

  protected def commonValidation(c: Configuration) = {
    valid(c) ++ validAccordingToTemplate(c).flatMap({case s: TemplateBased => Success(c)})
  }

  private def valid(config: Configuration): ExtendedResult[Configuration] = {
    val exist = repository.findByName(config.projectId, config.name)
    exist match {
      case None => Success(config)
      case Some(c) if c.id.isDefined && config.id == c.id => Success(config)
      case _ => Failure(compoundServiceErrors = Seq("Environment configuration with name %s already exists in project".format(config.name)))
    }
  }

  def save(c: Configuration) = {
    validCreate(c, c => repository.save(c))
  }

  def update(c: Configuration) = {
    validUpdate(c, c => repository.save(c))
  }
}
