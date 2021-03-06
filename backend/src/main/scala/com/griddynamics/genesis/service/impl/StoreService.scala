/**
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

import org.squeryl.PrimitiveTypeMode._
import com.griddynamics.genesis.{model, service}
import com.griddynamics.genesis.model.{GenesisSchema => GS}
import com.griddynamics.genesis.model._
import com.griddynamics.genesis.model.EnvStatus._
import com.griddynamics.genesis.common.Mistake
import com.griddynamics.genesis.common.Mistake.throwableToLeft
import org.springframework.transaction.annotation.{Propagation, Transactional}
import org.squeryl.{StaleUpdateException, Query, Table}
import java.sql.Timestamp
import com.griddynamics.genesis.model.WorkflowStepStatus._
import com.griddynamics.genesis.util.Logging
import com.griddynamics.genesis.repository.AbstractOrderingMapper
import com.griddynamics.genesis.api.Ordering
import scala.collection.mutable
import com.griddynamics.genesis.annotation.RemoteGateway
import EnvStatus._
import com.griddynamics.genesis.cache.{CacheManager, Cache}
import com.griddynamics.genesis.service.LoggerService
import scala.collection.mutable.ListBuffer
import com.griddynamics.genesis.logs.{ActionBasedLog, Log}

object EnvOrdering {
  val ID = "id"
  val NAME = "name"
}

//TODO think about ids as vars
@RemoteGateway("Genesis database access: StoreService")
class StoreService(val cacheManager: CacheManager) extends service.StoreService with Logging with Cache {

  import StoreService._

  object EnvOrderingMapper extends AbstractOrderingMapper[model.Environment] {
    import EnvOrdering._

    protected def mapFieldsAstField(model: Environment) = Map(
      ID -> model.id.~,
      NAME -> model.name.~
    )
  }

  val availableEnvs = from(GS.envs, GS.projects) { (env, project) =>
    where(env.projectId === project.id and project.isDeleted === false) select(env)
  }

  @Transactional(readOnly = true)
  def listEnvs(projectId: Int, statusFilter: Option[Seq[EnvStatus]] = None, ordering: Option[Ordering] = None): Seq[Environment] = {
    val filterId = statusFilter.toSeq.flatten(_.map(_.id))
    val key = "?projectId=" + projectId.toString + "&filter=" + filterId.mkString("~") + ordering.map(o => s"&orderBy=${o.field}&direction=${o.direction.toString}").getOrElse("")
    fromCache(StoreService.EnvCache, key){
      //TODO: change Option[Seq] to just Seq[]
      val envsWithAttrs = join(GS.envs, GS.envAttrs.leftOuter)((env, attrs) =>
        where((if (statusFilter.nonEmpty) env.status.id in filterId else 1===1) and
          env.projectId === projectId)
          select(env, attrs)
          orderBy EnvOrderingMapper.order(env, ordering.getOrElse(Ordering.asc(EnvOrdering.ID)))
          on(env.id === attrs.map(_.entityId))
      ).toSeq

      val envToEnvAttr = new mutable.LinkedHashMap[Environment, Seq[(Environment, Option[SquerylEntityAttr])]]()
      envsWithAttrs foreach { case (env, attr) =>
        envToEnvAttr.put( env, envToEnvAttr.getOrElse(env, Seq()) ++ Seq((env, attr)) )
      }

      (for {(env, envAttrs) <- envToEnvAttr
            attrs = envAttrs.collect { case (env, Some(attr) ) => attr.name -> attr.value}
      } yield {
        env.importAttrs(attrs.toMap)
        env
      }).toSeq
    }
  }

  @Transactional(readOnly = true)
  def countEnvs(projectId: Int) = listEnvQuery(projectId).size

  @Transactional(readOnly = true)
  def countEnvs(projectId: Int, statuses: Seq[EnvStatus]) = listEnvs(projectId, Option(statuses)).size

  private def listEnvQuery(projectId: Int): Query[Environment] = {
    GS.envs.where(env => env.projectId === projectId)
  }

  @Transactional(readOnly = true)
  def isEnvExist(projectId: Int, envId: Int): Boolean = {
    !from(GS.envs)(env => where(env.projectId === projectId and env.id === envId) select (env.id)).headOption.isEmpty
  }

  private def findEnvQuery(envId: Int, projectId: Int) =
    from(GS.envs)(e => where(e.id === envId and e.projectId === projectId) select (e))

    private def findEnvQuery(envName: String, projectId: Int) =
        from(GS.envs)(e => where(e.name === envName and e.projectId === projectId) select (e))

  @Transactional(readOnly = true)
  def findEnv(envId: Int, projectId: Int) = {
    val result = findEnvQuery(envId, projectId)
    val envs = if (result.size == 1) Some(result.single) else None
    envs.foreach(loadAttrs(_, GS.envAttrs))
    envs
  }

    @Transactional(readOnly = true)
    def findEnv(envName: String, projectId: Int) = {
        val result = findEnvQuery(envName, projectId)
        val envs = if (result.size == 1) Some(result.single) else None
        envs.foreach(loadAttrs(_, GS.envAttrs))
        envs
    }

  //TODO switch to join query
  @Transactional(readOnly = true)
  def findEnvWithWorkflow(envId: Int, projectId: Int) = {
    findEnv(envId).map( env =>
      (env, listWorkflows(env).find(_.status == WorkflowStatus.Executing))
    )
  }

  @Transactional(readOnly = true)
  def findEnv(id: Int) = {
    val result = from(GS.envs)(e => where(e.id === id) select (e))
    val envs = if (result.size == 1) Some(result.single) else None
    envs.foreach(loadAttrs(_, GS.envAttrs))
    envs
  }

  @Transactional(readOnly = true)
  def getVm(instanceId: String) = {
    from(GS.envs, GS.vms)((env, vm) =>
      where(vm.envId === env.id and
          vm.instanceId === Some(instanceId))
          select ((env, vm))
    ).single
  }

  @Transactional(readOnly = true)
  def listServers(env: Environment): Seq[BorrowedMachine] = {
    val vms = from(GS.borrowedMachines)(server => where(server.envId === env.id) select (server)).toList
    vms.foreach(loadAttrsCached(_, GS.serverAttrs))
    vms
  }

  @Transactional(readOnly = true)
  def listVms(env: Environment): Seq[VirtualMachine] = {
    val vms = from(GS.vms)(vm => where(vm.envId === env.id) select (vm)).toList
    vms.foreach(loadAttrsCached(_, GS.vmAttrs))
    vms
  }

  @Transactional(readOnly = true)
  def listWorkflows(env: Environment) = {
    from(GS.workflows)(w =>
      where(w.envId === env.id)
          select (w.id)
          orderBy(w.id desc)
    ).toList flatMap { findWorkflow }
  }

  @Transactional(readOnly = true)
  def listWorkflows(env: Environment, pageOffset: Int, pageLength: Int) = {
    val list: List[Int] = from(GS.workflows)(w =>
      where(w.envId === env.id)
          select (w.id)
          orderBy(w.id desc)
    ).page(pageOffset, pageLength).toList
    list.flatMap { findWorkflow  }
  }

  @Transactional(readOnly = true)
  def countWorkflows(env: Environment): Int =
    from(GS.workflows)(w =>
      where(w.envId === env.id)
          compute(count)
    ).single.measures.toInt

  //TODO switch to join query
  @Transactional(readOnly = true)
  def listEnvsWithWorkflow(projectId: Int, statusFilter: Option[Seq[EnvStatus]] = None, ordering: Option[Ordering] = None) = {
    listEnvs(projectId, statusFilter, ordering).map(env => {
      (env, listWorkflows(env).find(w => w.status == WorkflowStatus.Executing))
    })
  }

  @Transactional(readOnly = true)
  def workflowsHistory(env : Environment, pageOffset: Int, pageLength: Int): Seq[(Workflow, Seq[WorkflowStep])] =
    for(workflow <- listWorkflows(env, pageOffset, pageLength)) yield
      (workflow, listWorkflowSteps(workflow))

  @Transactional(readOnly = true)
  def listWorkflowSteps(workflow : Workflow): Seq[WorkflowStep] =
    from(GS.steps)(step =>
      where(workflow.id === step.workflowId)
          select(step)
          orderBy(step.id asc)
    ).toList

  @Transactional(readOnly = true)
  def countFinishedActionsForCurrentWorkflow(env: Environment): Int =
    from(GS.workflows, GS.steps, GS.actionTracking)((wf, step, action) =>
      where(wf.envId === env.id and
        wf.status === WorkflowStatus.Executing and
        step.workflowId === wf.id and
        action.workflowStepId === step.id and
        action.status <> ActionTrackingStatus.Executing)
        compute(count)
    ).single.measures.toInt

  @Transactional
  def createEnv(env: Environment, workflow: Workflow) = withEvict(StoreService.EnvCache){
    throwableToLeft {
      env.status = EnvStatus.Busy
      val cenv = GS.envs.insert(env)
      updateAttrs(cenv, GS.envAttrs)

      var cworkflow = new Workflow(cenv.id, workflow.name, workflow.startedBy,
        WorkflowStatus.Requested, 0, 0,
        workflow.variables, workflow.displayVariables, None, None)
      cworkflow = GS.workflows.insert(cworkflow)

      (cenv, cworkflow)
    }
  }

  @Transactional
  def updateEnv(env: Environment) {
    withEvict(StoreService.EnvCache) {
      GS.envs.update(env)
      updateAttrs(env, GS.envAttrs)
    }
  }

   @Transactional
  def setEnvStatus(env: Environment, status: EnvStatus) : Option[Mistake] = withEvict(StoreService.EnvCache){
    lazy val mistake = Mistake(s"Can't update status of instance [${env.name}]")

    val updatedRowCount = (env.status, status) match {
      case (Broken, Ready) => updateEnvStatus(env.id, Broken, Ready)
      case (s, Destroyed) if s != Busy => updateEnvStatus(env.id, s, Destroyed)
      case _ => 0
    }
    if (updatedRowCount != 1) Option(mistake) else None
  }

  private def updateEnvStatus(envId: Int, oldStatus: EnvStatus, status: EnvStatus) =
    update(GS.envs)(e =>
      where(e.id === envId and e.status === oldStatus)
        set (e.status := status)
    )

  @Transactional
  def createVm(vm: VirtualMachine) = {
    val cvm = GS.vms.insert(vm)
    updateAttrs(cvm, GS.vmAttrs)
    cvm
  }

  @Transactional
  def updateServer(resource: EnvResource) {
    resource match {
      case vm: VirtualMachine => {
        GS.vms.update(vm)
        updateAttrs(vm, GS.vmAttrs)
      }
      case server: BorrowedMachine => {
        GS.borrowedMachines.update(server)
        updateAttrs(server, GS.serverAttrs)
      }
    }
  }

  @Transactional
  def createBM(bm: BorrowedMachine) = {
    val cbm = GS.borrowedMachines.insert(bm)
    updateAttrs(cbm, GS.serverAttrs)
    cbm
  }

  @Transactional(propagation = Propagation.MANDATORY)
  def updateAttrs(entity: EntityWithAttrs, table: Table[SquerylEntityAttr]) {
    withEvict(AttributeCache, entityAttributeKey(entity)) {
      val (changedAttrs, removedAttrs) = entity.exportAttrs()
      if (!removedAttrs.isEmpty)
        table.deleteWhere(a => a.name in removedAttrs and a.entityId === entity.id)
      if (!changedAttrs.isEmpty) {
        table.deleteWhere(a => a.name in changedAttrs.keys and a.entityId === entity.id)
        table.insert(for ((name, value) <- changedAttrs) yield
          new SquerylEntityAttr(entity.id, name, value)
        )
      }
    }
  }

  @Transactional
  def finishWorkflow(env: Environment, workflow: Workflow, modifyEnv: Boolean = true) {
    withEvict(StoreService.WorkflowCache, workflow.id.toString) {
      val finishTime: Some[Timestamp] = Some(new Timestamp(System.currentTimeMillis()))

      GS.steps.update(step =>
        where((step.finished isNull) and (step.workflowId === workflow.id) and (step.started isNotNull)) set (
          step.finished := finishTime
          )
      )

      if (modifyEnv) {
        env.modifiedBy = Some(workflow.startedBy)
        env.modificationTime = finishTime
      }

      updateEnv(env)

      workflow.executionFinished = finishTime
      GS.workflows.update(workflow)
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  def loadAttrs(entity: EntityWithAttrs, table: Table[SquerylEntityAttr]) = {
    val attrs = from(table)(a => where(a.entityId === entity.id) select a).toList
    val asMap = attrs.map(a => (a.name, a.value)).toMap
    entity.importAttrs(asMap)
    asMap
  }

  @Transactional(propagation = Propagation.MANDATORY)
  def loadAttrsCached(entity: EntityWithAttrs, table: Table[SquerylEntityAttr]) = {
    val attrs = fromCache(AttributeCache, entityAttributeKey(entity), AttributesCacheTtl) {
      val saved = from(table)(a => where(a.entityId === entity.id) select a).toList
      saved.map(a => (a.name, a.value)).toMap
    }
    entity.importAttrs(attrs)
    attrs
  }

  private def entityAttributeKey(entity: EntityWithAttrs) = s"${entity.getClass.getName}/${entity.id}"

  @Transactional
  def requestWorkflow(env: Environment, workflow: Workflow): Either[Mistake, (Environment, Workflow)] = {
    val actualEnv = findEnv(env.name, env.projectId).get

    if (!isReadyForWorkflow(actualEnv.status))
      return Left(Mistake("Instance with status %s isn't ready for workflow request".format(actualEnv.status: EnvStatus),
        retryPossible = actualEnv.status == EnvStatus.Busy))

    actualEnv.status = EnvStatus.Busy
    workflow.status = WorkflowStatus.Requested
    try {
      updateEnv(actualEnv)
      Right((actualEnv, GS.workflows.insert(workflow)))
    } catch {
      case e: StaleUpdateException => {
        log.warn("Optimistic lock: instance's status has been updated", e)
        Left(Mistake("Optimistic lock: instance's status has been updated"))
      }
    }
  }

  @Transactional(readOnly = true)
  def retrieveWorkflow(envId: Int, projectId: Int) = {
    from(GS.envs, GS.workflows)((e, w) =>
      where(e.id === envId and
          e.projectId === projectId and
          e.id === w.envId and
          w.status === WorkflowStatus.Requested)
          select ((e, w))
    ).single
  }

  @Transactional
  def startWorkflow(envId: Int, projectId: Int) = {
    val (e, w) = retrieveWorkflow(envId, projectId)
    loadAttrs(e, GS.envAttrs)

    e.status = EnvStatus.Busy

    w.status = WorkflowStatus.Executing
    w.executionStarted = Some(new Timestamp(System.currentTimeMillis()))

    updateEnv(e)
    GS.workflows.update(w)

    (e, w, listVms(e) ++ listServers(e))
  }

  @Transactional(readOnly = true)
  def findWorkflow(id: Int) = {
    val wOpt =  fromCache(StoreService.WorkflowCache, id.toString, 900) {
      log.debug("Getting workflow " + id + " from database")
      GS.workflows.lookup(id)
    }
    wOpt.filter {wf: Workflow => wf.status != WorkflowStatus.Requested && wf.status != WorkflowStatus.Executing} orElse (GS.workflows.lookup(id))
  }


  @Transactional
  def updateWorkflow(w: Workflow) {
    withEvict(StoreService.WorkflowCache, w.id.toString) {
      GS.workflows.update(w)
    }
  }

  @Transactional
  def updateStep(step: WorkflowStep) {
    GS.steps.update(step)
  }

  @Transactional
  def updateStepStatus(stepId: Int, status: WorkflowStepStatus) {
    updateStepDetailsAndStatus(stepId, None, status)
  }


  @Transactional
  def updateStepDetailsAndStatus(stepId: Int, details: Option[String], status: WorkflowStepStatus.WorkflowStepStatus) {
    import com.griddynamics.genesis.model.WorkflowStepStatus._
    GS.steps.update(step => {
      where(step.id === stepId) set (
        step.finished := (if(status == Failed || status == Succeed) Some(new Timestamp(System.currentTimeMillis())) else step.finished),
        step.started := (if(status == Executing) Some(new Timestamp(System.currentTimeMillis())) else step.started),
        step.status := status,
        step.details := details.getOrElse(step.details)
      )
    })
  }

  @Transactional
  def insertWorkflowSteps(steps : Seq[WorkflowStep]) =
    for(step <- steps) yield insertWorkflowStep(step)

  @Transactional
  def insertWorkflowStep(step : WorkflowStep) = GS.steps.insert(step)

  @Transactional
  def allocateStepCounters(count : Int = 1) = {
    val key: String = WorkflowStep.getClass.getSimpleName
    val forUpdate: Query[NumberCounter] = from(GS.counters)(counter => {
      where(counter.id === key) select counter
    }).forUpdate
    val newCounter : NumberCounter = forUpdate.headOption.getOrElse(new NumberCounter(key))
    newCounter.increment(count)
    val last = from(GS.steps)(s => (compute(max(s.id)))).getOrElse(0)
    if (last >= newCounter.counter - count) {
      newCounter.counter = last  + count + 1
    }
    GS.counters.insertOrUpdate(newCounter)
    newCounter.counter - count
  }

  @Transactional
  def writeLog(log: Log) {
    GS.logs.insert(new StepLogEntry(log.stepId, log.message, log.timestamp))
  }

  @Transactional
  def writeLogs(logs: Seq[Log]) {
    GS.logs.insert(logs.map {log => new StepLogEntry(log.stepId, log.message, log.timestamp)})
  }

  @Transactional
  def writeActionLog(log: ActionBasedLog) {
    val stepID = from(GS.actionTracking)((action) => where (action.actionUUID === log.actionUID) select(action.workflowStepId)).headOption
    stepID.foreach { id => GS.logs.insert(new StepLogEntry(id, log.message, log.timestamp, Option(log.actionUID))) }
  }

  @Transactional
  def writeActionLogs(logs: Seq[ActionBasedLog]) {
    val groupedLogs = logs.groupBy(_.actionUID)
    groupedLogs.foreach {
      case (actionId: String, actionLogs: Seq[ActionBasedLog]) => {
        val stepID = from(GS.actionTracking)((action) => where (action.actionUUID === actionId) select(action.workflowStepId)).headOption
        stepID.foreach { id => GS.logs.insert(actionLogs.map { log: ActionBasedLog => new StepLogEntry(id, log.message, log.timestamp, Some(log.actionUID))}) }
      }
    }
  }

  private def getStepID(actionUUID: String) = {
    fromCache(StoreService.TrackingCache, actionUUID, 3600) {
      from(GS.actionTracking)((action) => where (action.actionUUID === actionUUID) select(action.workflowStepId)).headOption
    }
  }

  @Transactional
  def getLogs(stepId: Int, includeActions : Boolean) : Seq[StepLogEntry] = {
    from(GS.logs)((log) =>
      where(log.stepId === stepId and log.actionUUID.isNull.inhibitWhen(includeActions))
          select(log)
          orderBy(log.timestamp asc)
    ).toList
  }

  @Transactional
  def getLogs(actionUUID: String) = {
    from(GS.logs)((log) =>
      where(log.actionUUID === Option(actionUUID))
          select(log)
          orderBy(log.timestamp asc)
    ).toList
  }

  @Transactional
  def startAction(actionTracking: ActionTracking) = {
    GS.actionTracking.insert(actionTracking)
  }

  @Transactional
  def endAction(uuid: String, message: Option[String], status: ActionTrackingStatus.ActionStatus) {
    GS.actionTracking.update(at => {
      where(at.actionUUID === uuid) set (
          at.finished := Some(new Timestamp(System.currentTimeMillis())),
          at.description := message,
          at.status := status
          )
    })
  }

  @Transactional
  def cancelRunningActions(stepId: Int) {
    GS.actionTracking.update( at => {
      where(at.workflowStepId === stepId and at.finished === None) set (
          at.status := ActionTrackingStatus.Canceled,
          at.finished := Some(new Timestamp(System.currentTimeMillis()))
          )
    })
  }

  @Transactional
  def getActionLog(stepId : Int) = {
    from(GS.actionTracking)(at => where(at.workflowStepId === stepId) select (at) orderBy(at.started asc)).toList
  }

  @Transactional(readOnly = true)
  def stepExists(stepId : Int, envId: Int) = from(GS.steps, GS.workflows, GS.envs)((step, workflow, env) =>
    where(step.id === stepId and step.workflowId === workflow.id and workflow.envId === envId) select (step.id) ).nonEmpty

  def findBorrowedMachinesByServerId(serverId: Int) = {
    from(GS.borrowedMachines)(bm => where(bm.serverId === serverId and bm.status <> MachineStatus.Released) select (bm)).toList
  }

  @Transactional(readOnly = false)
  def updateEnvName(i: Int, s: String) = {
    withEvict(StoreService.EnvCache) {
      update(GS.envs)(e => where(e.id === i) set (e.name := s))
    }
  }

  @Transactional(readOnly = true)
  def findEnvsByConfigurationId(configId: Int): Seq[Int] = from(GS.envs) ( env =>
    where(env.configurationId === configId ) select(env.id)
  ).toList

  @Transactional(readOnly = true)
  def findLiveEnvsByConfigurationId(configId: Int): Seq[Int] = from(GS.envs) ( env =>
    where((env.configurationId === configId) and (env.status <> EnvStatus.Destroyed)) select(env.id)
  ).toList

  @Transactional(readOnly = true)
  def workflowStats = from(GS.workflows, GS.envs)((workflow, env) =>
    where((workflow.status === WorkflowStatus.Executing) and (env.id === workflow.envId))
    select (env.projectId, workflow.id)
  ).toList

  @Transactional(readOnly = true)
  def runningWorkflowsPerProject(projectId: Int) = from(GS.workflows, GS.envs)(
    (workflow, env) => where(
      (workflow.status === WorkflowStatus.Executing) and (env.id === workflow.envId) and (env.projectId === projectId)
    ) select (workflow)
  ).toList

  def iterate(to: LoggerService) = {
    val actionBuffer = mutable.ArrayBuffer[ActionBasedLog]()
    val stepBuffer = mutable.ArrayBuffer[Log]()
    from(GS.logs)((log) =>
        select(log)
    ).foreach({ logRecord: StepLogEntry => {
      logRecord.actionUUID match {
        case Some(s: String) => actionBuffer += ActionBasedLog(s, logRecord.message, logRecord.timestamp)
        case None => stepBuffer += Log(logRecord.stepId, logRecord.message, logRecord.timestamp)
      }
      if (actionBuffer.size >= 100) {
        to.writeActionLogs(actionBuffer.toSeq)
        actionBuffer.clear()
      }
      if (stepBuffer.size >= 100) {
        to.writeLogs(stepBuffer.toSeq)
        stepBuffer.clear()
      }
    } })
    to.writeActionLogs(actionBuffer.toSeq)
    actionBuffer.clear()
    to.writeLogs(stepBuffer.toSeq)
    stepBuffer.clear()
  }

  def storageMode = LoggerService.DATABASE
}


object StoreService {
  def isReadyForWorkflow(status: EnvStatus) = status match {
    case EnvStatus.Busy => false
    case EnvStatus.Destroyed => false
    case _ => true
  }

  val EnvCache = "Environments"
  val AttributeCache = "Attributes"
  val AttributesCacheTtl = Integer.MAX_VALUE
  val TrackingCache = "Tracking"
  val WorkflowCache = "Workflows"
}
