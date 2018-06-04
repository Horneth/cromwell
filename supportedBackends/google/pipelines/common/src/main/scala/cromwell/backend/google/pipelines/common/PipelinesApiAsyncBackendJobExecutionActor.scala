package cromwell.backend.google.pipelines.common

import java.net.SocketTimeoutException

import _root_.io.grpc.Status
import akka.actor.ActorRef
import akka.http.scaladsl.model.ContentTypes
import cats.data.Validated.{Invalid, Valid}
import cats.syntax.validated._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.storage.contrib.nio.CloudStorageOptions
import common.util.StringUtil._
import common.validation.ErrorOr._
import common.validation.Validation._
import cromwell.backend._
import cromwell.backend.async.{AbortedExecutionHandle, ExecutionHandle, FailedNonRetryableExecutionHandle, FailedRetryableExecutionHandle, PendingExecutionHandle}
import cromwell.backend.google.pipelines.common.api.PipelinesApiRequestFactory.{CreatePipelineParameters, DetritusInputParameters, DetritusOutputParameters, InputOutputParameters}
import cromwell.backend.google.pipelines.common.api.RunStatus.TerminalRunStatus
import cromwell.backend.google.pipelines.common.api._
import cromwell.backend.google.pipelines.common.api.clients.PipelinesApiRunCreationClient.JobAbortedException
import cromwell.backend.google.pipelines.common.api.clients.{PipelinesApiAbortClient, PipelinesApiRunCreationClient, PipelinesApiStatusRequestClient}
import cromwell.backend.google.pipelines.common.errors.FailedToDelocalizeFailure
import cromwell.backend.google.pipelines.common.io.{PipelinesApiAttachedDisk, PipelinesApiWorkingDisk, _}
import cromwell.backend.io.DirectoryFunctions
import cromwell.backend.standard.{StandardAdHocValue, StandardAsyncExecutionActor, StandardAsyncExecutionActorParams, StandardAsyncJob}
import cromwell.core._
import cromwell.core.logging.JobLogger
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.core.retry.SimpleExponentialBackoff
import cromwell.filesystems.gcs.GcsPath
import cromwell.filesystems.gcs.batch.GcsBatchCommandBuilder
import cromwell.google.pipelines.common.PreviousRetryReasons
import cromwell.services.keyvalue.KeyValueServiceActor._
import cromwell.services.keyvalue.KvClient
import org.slf4j.LoggerFactory
import shapeless.Coproduct
import wom.CommandSetupSideEffectFile
import wom.callable.AdHocValue
import wom.callable.Callable.OutputDefinition
import wom.callable.MetaValueElement.{MetaValueElementBoolean, MetaValueElementObject}
import wom.core.FullyQualifiedName
import wom.expression.{FileEvaluation, NoIoFunctionSet}
import wom.types.{WomArrayType, WomSingleFileType}
import wom.values._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Success, Try}

object PipelinesApiAsyncBackendJobExecutionActor {
  val JesOperationIdKey = "__jes_operation_id"

  type JesPendingExecutionHandle = PendingExecutionHandle[StandardAsyncJob, Run, RunStatus]

  private val ExtraConfigParamName = "__extra_config_gcs_path"

  val maxUnexpectedRetries = 2

  val JesFailedToDelocalize = 5
  val JesUnexpectedTermination = 13
  val JesPreemption = 14

  // If the JES code is 2 (UNKNOWN), this sub-string indicates preemption:
  val FailedToStartDueToPreemptionSubstring = "failed to start due to preemption"

  def StandardException(errorCode: Status,
                        message: String,
                        jobTag: String,
                        returnCodeOption: Option[Int],
                        stderrPath: Path) = {
    val returnCodeMessage = returnCodeOption match {
      case Some(returnCode) if returnCode == 0 => "Job exited without an error, exit code 0."
      case Some(returnCode) => s"Job exit code $returnCode. Check $stderrPath for more information."
      case None => "The job was stopped before the command finished."
    }

    new Exception(s"Task $jobTag failed. $returnCodeMessage PAPI error code ${errorCode.getCode.value}. $message")
  }
}

class PipelinesApiAsyncBackendJobExecutionActor(override val standardParams: StandardAsyncExecutionActorParams)
  extends BackendJobLifecycleActor with StandardAsyncExecutionActor with PipelinesApiJobCachingActorHelper
    with PipelinesApiStatusRequestClient with PipelinesApiRunCreationClient with PipelinesApiAbortClient with KvClient with PapiInstrumentation {

  override lazy val ioCommandBuilder = GcsBatchCommandBuilder

  import PipelinesApiAsyncBackendJobExecutionActor._

  val slf4jLogger = LoggerFactory.getLogger(PipelinesApiAsyncBackendJobExecutionActor.getClass)
  val logger = new JobLogger("JesRun", jobDescriptor.workflowDescriptor.id, jobDescriptor.key.tag, None, Set(slf4jLogger))
  override lazy val requestFactory = initializationData.genomicsRequestFactory

  val jesBackendSingletonActor: ActorRef =
    standardParams.backendSingletonActorOption.getOrElse(
      throw new RuntimeException("JES Backend actor cannot exist without the JES backend singleton actor"))

  override type StandardAsyncRunInfo = Run

  override type StandardAsyncRunStatus = RunStatus

  override val papiApiActor: ActorRef = jesBackendSingletonActor

  override lazy val pollBackOff = SimpleExponentialBackoff(
    initialInterval = 30 seconds, maxInterval = jesAttributes.maxPollingInterval seconds, multiplier = 1.1)

  override lazy val executeOrRecoverBackOff = SimpleExponentialBackoff(
    initialInterval = 3 seconds, maxInterval = 20 seconds, multiplier = 1.1)
  
  override lazy val runtimeEnvironment = {
    RuntimeEnvironmentBuilder(jobDescriptor.runtimeAttributes, PipelinesApiWorkingDisk.MountPoint, PipelinesApiWorkingDisk.MountPoint)(standardParams.minimumRuntimeSettings)
  }

  protected lazy val cmdInput =
    PipelinesApiFileInput(PipelinesApiJobPaths.JesExecParamName, pipelinesApiCallPaths.script, DefaultPathBuilder.get(pipelinesApiCallPaths.scriptFilename), workingDisk)

  protected lazy val dockerConfiguration = pipelinesConfiguration.dockerCredentials

  protected val previousRetryReasons: ErrorOr[PreviousRetryReasons] = PreviousRetryReasons.tryApply(jobDescriptor.prefetchedKvStoreEntries, jobDescriptor.key.attempt)

  protected lazy val jobDockerImage = jobDescriptor.maybeCallCachingEligible.dockerHash.getOrElse(runtimeAttributes.dockerImage)

  override lazy val dockerImageUsed: Option[String] = Option(jobDockerImage)

  override lazy val preemptible: Boolean = previousRetryReasons match {
    case Valid(PreviousRetryReasons(p, _)) => p < maxPreemption
    case _ => false
  }

  override def tryAbort(job: StandardAsyncJob): Unit = abortJob(job)

  override def requestsAbortAndDiesImmediately: Boolean = false

  override def receive: Receive = pollingActorClientReceive orElse runCreationClientReceive orElse abortActorClientReceive orElse kvClientReceive orElse super.receive

  private def gcsAuthParameter: Option[PipelinesApiLiteralInput] = {
    if (jesAttributes.auths.gcs.requiresAuthFile || dockerConfiguration.isDefined)
      Option(PipelinesApiLiteralInput(ExtraConfigParamName, pipelinesApiCallPaths.workflowPaths.gcsAuthFilePath.pathAsString))
    else None
  }

  /**
    * Takes two arrays of remote and local WOM File paths and generates the necessary `PipelinesApiInput`s.
    */
  protected def pipelinesApiInputsFromWomFiles(jesNamePrefix: String,
                                               remotePathArray: Seq[WomFile],
                                               localPathArray: Seq[WomFile],
                                               jobDescriptor: BackendJobDescriptor): Iterable[PipelinesApiInput] = {
    (remotePathArray zip localPathArray zipWithIndex) flatMap {
      case ((remotePath, localPath), index) =>
        Seq(PipelinesApiFileInput(s"$jesNamePrefix-$index", getPath(remotePath.valueString).get, DefaultPathBuilder.get(localPath.valueString), workingDisk))
    }
  }

  /**
    * Turns WomFiles into relative paths.  These paths are relative to the working disk.
    *
    * relativeLocalizationPath("foo/bar.txt") -> "foo/bar.txt"
    * relativeLocalizationPath("gs://some/bucket/foo.txt") -> "some/bucket/foo.txt"
    */
  protected def relativeLocalizationPath(file: WomFile): WomFile = {
    file.mapFile(value =>
      getPath(value) match {
        case Success(path) => path.pathWithoutScheme
        case _ => value
      }
    )
  }

  protected def fileName(file: WomFile): WomFile = {
    file.mapFile(value =>
      getPath(value) match {
        case Success(path) => path.name
        case _ => value
      }
    )
  }

  override lazy val inputsToNotLocalize: Set[WomFile] = {
    jobDescriptor.findInputFilesByParameterMeta {
      case MetaValueElementObject(values) => values.get("localization_optional").contains(MetaValueElementBoolean(true))
      case _ => false
    }
  }

  protected def callInputFiles: Map[FullyQualifiedName, Seq[WomFile]] = {

    jobDescriptor.fullyQualifiedInputs map {
      case (key, womFile) =>
        val arrays: Seq[WomArray] = womFile collectAsSeq {
          case womFile: WomFile if !inputsToNotLocalize.contains(womFile) =>
            val files: List[WomSingleFile] = DirectoryFunctions
              .listWomSingleFiles(womFile, pipelinesApiCallPaths.workflowPaths)
              .toTry(s"Error getting single files for $womFile").get
            WomArray(WomArrayType(WomSingleFileType), files)
        }

        key -> arrays.flatMap(_.value).collect {
          case womFile: WomFile => womFile
        }
    }
  }
  
  protected def localizationPath(f: CommandSetupSideEffectFile) = {
    val fileTransformer = if (isAdHocFile(f.file)) fileName _ else relativeLocalizationPath _
    f.relativeLocalPath.fold(ifEmpty = fileTransformer(f.file))(WomFile(f.file.womFileType, _))
  }

  private[pipelines] def generateInputs(jobDescriptor: BackendJobDescriptor): Set[PipelinesApiInput] = {
    // We need to tell PAPI about files that were created as part of command instantiation (these need to be defined
    // as inputs that will be localized down to the VM). Make up 'names' for these files that are just the short
    // md5's of their paths.
    val writeFunctionFiles = instantiatedCommand.createdFiles map { f => f.file.value.md5SumShort -> List(f) } toMap

    val writeFunctionInputs = writeFunctionFiles flatMap {
      case (name, files) => pipelinesApiInputsFromWomFiles(name, files.map(_.file), files.map(localizationPath), jobDescriptor)
    }

    val callInputInputs = callInputFiles flatMap {
      case (name, files) => pipelinesApiInputsFromWomFiles(name, files, files.map(relativeLocalizationPath), jobDescriptor)
    }

    (writeFunctionInputs ++ callInputInputs).toSet
  }

  /**
    * Given a path (relative or absolute), returns a (Path, JesAttachedDisk) tuple where the Path is
    * relative to the AttachedDisk's mount point
    *
    * @throws Exception if the `path` does not live in one of the supplied `disks`
    */
  protected def relativePathAndAttachedDisk(path: String, disks: Seq[PipelinesApiAttachedDisk]): (Path, PipelinesApiAttachedDisk) = {
    val absolutePath = DefaultPathBuilder.get(path) match {
      case p if !p.isAbsolute => PipelinesApiWorkingDisk.MountPoint.resolve(p)
      case p => p
    }

    disks.find(d => absolutePath.startsWith(d.mountPoint)) match {
      case Some(disk) => (disk.mountPoint.relativize(absolutePath), disk)
      case None =>
        throw new Exception(s"Absolute path $path doesn't appear to be under any mount points: ${disks.map(_.toString).mkString(", ")}")
    }
  }

  /**
    * If the desired reference name is too long, we don't want to break JES or risk collisions by arbitrary truncation. So,
    * just use a hash. We only do this when needed to give better traceability in the normal case.
    */
  protected def makeSafeReferenceName(referenceName: String) = {
    if (referenceName.length <= 127) referenceName else referenceName.md5Sum
  }

  protected [pipelines] def generateOutputs(jobDescriptor: BackendJobDescriptor): Set[PipelinesApiOutput] = {
    import cats.syntax.validated._
    def evaluateFiles(output: OutputDefinition): List[FileEvaluation] = {
      Try(
        output.expression.evaluateFiles(jobDescriptor.localInputs, NoIoFunctionSet, output.womType).map(_.toList)
      ).getOrElse(List.empty[FileEvaluation].validNel)
        .getOrElse(List.empty)
    }

    def relativeFileEvaluation(evaluation: FileEvaluation): FileEvaluation = {
      evaluation.copy(file = relativeLocalizationPath(evaluation.file))
    }

    val womFileOutputs = jobDescriptor.taskCall.callable.outputs.flatMap(evaluateFiles) map relativeFileEvaluation

    val outputs: Seq[PipelinesApiOutput] = womFileOutputs.distinct flatMap { fileEvaluation =>
      fileEvaluation.file.flattenFiles flatMap {
        case unlistedDirectory: WomUnlistedDirectory => generateUnlistedDirectoryOutputs(unlistedDirectory, fileEvaluation)
        case singleFile: WomSingleFile => generateSingleFileOutputs(singleFile, fileEvaluation)
        case globFile: WomGlobFile => generateGlobFileOutputs(globFile) // Assumes optional = false for globs.
      }
    }

    val additionalGlobOutput = jobDescriptor.taskCall.callable.additionalGlob.toList.flatMap(generateGlobFileOutputs).toSet

    outputs.toSet ++ additionalGlobOutput
  }

  protected def generateUnlistedDirectoryOutputs(womFile: WomUnlistedDirectory, fileEvaluation: FileEvaluation): List[PipelinesApiOutput] = {
    val directoryPath = womFile.value.ensureSlashed
    val directoryListFile = womFile.value.ensureUnslashed + ".list"
    val gcsDirDestinationPath = callRootPath.resolve(directoryPath)
    val gcsListDestinationPath = callRootPath.resolve(directoryListFile)

    val (_, directoryDisk) = relativePathAndAttachedDisk(womFile.value, runtimeAttributes.disks)

    // We need both the collection directory and the collection list:
    List(
      // The collection directory:
      PipelinesApiFileOutput(
        makeSafeReferenceName(directoryListFile),
        gcsListDestinationPath,
        DefaultPathBuilder.get(directoryListFile),
        directoryDisk,
        fileEvaluation.optional,
        fileEvaluation.secondary
      ),
      // The collection list file:
      PipelinesApiFileOutput(
        makeSafeReferenceName(directoryPath),
        gcsDirDestinationPath,
        DefaultPathBuilder.get(directoryPath + "*"),
        directoryDisk,
        fileEvaluation.optional,
        fileEvaluation.secondary
      )
    )
  }

  protected def generateSingleFileOutputs(womFile: WomSingleFile, fileEvaluation: FileEvaluation): List[PipelinesApiFileOutput] = {
    val (relpath, disk) = relativePathAndAttachedDisk(womFile.value, runtimeAttributes.disks)
    // If the file is on a custom mount point, resolve it so that the full mount path will show up in the cloud path
    // For the default one (cromwell_root), the expctation is that it does not appear
    val mountedPath = if (disk != PipelinesApiWorkingDisk.Default) disk.mountPoint.resolve(relpath) else relpath
    // Normalize the local path (to get rid of ".." and "."). Also strip any potential leading / so that it gets appended to the call root
    val normalizedPath = mountedPath.normalize().pathAsString.stripPrefix("/")
    val destination = callRootPath.resolve(normalizedPath)
    val jesFileOutput = PipelinesApiFileOutput(makeSafeReferenceName(womFile.value), destination, relpath, disk, fileEvaluation.optional, fileEvaluation.secondary)
    List(jesFileOutput)
  }

  protected def generateGlobFileOutputs(womFile: WomGlobFile): List[PipelinesApiOutput] = {
    val globName = GlobFunctions.globName(womFile.value)
    val globDirectory = globName + "/"
    val globListFile = globName + ".list"
    val gcsGlobDirectoryDestinationPath = callRootPath.resolve(globDirectory)
    val gcsGlobListFileDestinationPath = callRootPath.resolve(globListFile)

    val (_, globDirectoryDisk) = relativePathAndAttachedDisk(womFile.value, runtimeAttributes.disks)

    // We need both the glob directory and the glob list:
    List(
      // The glob directory:
      PipelinesApiFileOutput(makeSafeReferenceName(globDirectory), gcsGlobDirectoryDestinationPath, DefaultPathBuilder.get(globDirectory + "*"), globDirectoryDisk,
        optional = false, secondary = false),
      // The glob list file:
      PipelinesApiFileOutput(makeSafeReferenceName(globListFile), gcsGlobListFileDestinationPath, DefaultPathBuilder.get(globListFile), globDirectoryDisk,
        optional = false, secondary = false)
    )
  }

  lazy val jesMonitoringParamName: String = PipelinesApiJobPaths.JesMonitoringKey
  lazy val localMonitoringLogPath: Path = DefaultPathBuilder.get(pipelinesApiCallPaths.jesMonitoringLogFilename)
  lazy val localMonitoringScriptPath: Path = DefaultPathBuilder.get(pipelinesApiCallPaths.jesMonitoringScriptFilename)

  lazy val monitoringScript: Option[PipelinesApiFileInput] = {
    pipelinesApiCallPaths.workflowPaths.monitoringScriptPath map { path =>
      PipelinesApiFileInput(s"$jesMonitoringParamName-in", path, localMonitoringScriptPath, workingDisk)
    }
  }

  lazy val monitoringOutput: Option[PipelinesApiFileOutput] = monitoringScript map { _ =>
    PipelinesApiFileOutput(s"$jesMonitoringParamName-out",
      pipelinesApiCallPaths.jesMonitoringLogPath, localMonitoringLogPath, workingDisk, optional = false, secondary = false)
  }

  override lazy val commandDirectory: Path = PipelinesApiWorkingDisk.MountPoint

  private val DockerMonitoringLogPath: Path = PipelinesApiWorkingDisk.MountPoint.resolve(pipelinesApiCallPaths.jesMonitoringLogFilename)
  private val DockerMonitoringScriptPath: Path = PipelinesApiWorkingDisk.MountPoint.resolve(pipelinesApiCallPaths.jesMonitoringScriptFilename)

  override def scriptPreamble: String = {
    if (monitoringOutput.isDefined) {
      s"""|touch $DockerMonitoringLogPath
          |chmod u+x $DockerMonitoringScriptPath
          |$DockerMonitoringScriptPath > $DockerMonitoringLogPath &""".stripMargin
    } else ""
  }

  override def globParentDirectory(womGlobFile: WomGlobFile): Path = {
    val (_, disk) = relativePathAndAttachedDisk(womGlobFile.value, runtimeAttributes.disks)
    disk.mountPoint
  }

  protected def googleProject(descriptor: BackendWorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(WorkflowOptionKeys.GoogleProject, jesAttributes.project)
  }

  protected def computeServiceAccount(descriptor: BackendWorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(WorkflowOptionKeys.GoogleComputeServiceAccount, jesAttributes.computeServiceAccount)
  }

  override def isTerminal(runStatus: RunStatus): Boolean = {
    runStatus match {
      case _: TerminalRunStatus => true
      case _ => false
    }
  }

  private def createPipelineParameters(inputOutputParameters: InputOutputParameters): CreatePipelineParameters = {
    CreatePipelineParameters(
      jobDescriptor = jobDescriptor,
      runtimeAttributes = runtimeAttributes,
      dockerImage = jobDockerImage,
      cloudWorkflowRoot = workflowPaths.workflowRoot,
      cloudCallRoot = callRootPath,
      commandScriptContainerPath = cmdInput.containerPath,
      logGcsPath = jesLogPath,
      inputOutputParameters,
      googleProject(jobDescriptor.workflowDescriptor),
      computeServiceAccount(jobDescriptor.workflowDescriptor),
      backendLabels,
      preemptible,
      pipelinesConfiguration.jobShell
    )
  }

  override def isFatal(throwable: Throwable): Boolean = super.isFatal(throwable) || isFatalJesException(throwable)

  override def isTransient(throwable: Throwable): Boolean = isTransientJesException(throwable)

  override def executeAsync(): Future[ExecutionHandle] = createNewJob()

  val futureKvJobKey = KvJobKey(jobDescriptor.key.call.fullyQualifiedName, jobDescriptor.key.index, jobDescriptor.key.attempt + 1)

  override def recoverAsync(jobId: StandardAsyncJob): Future[ExecutionHandle] = reconnectToExistingJob(jobId)

  override def reconnectAsync(jobId: StandardAsyncJob): Future[ExecutionHandle] = reconnectToExistingJob(jobId)

  override def reconnectToAbortAsync(jobId: StandardAsyncJob): Future[ExecutionHandle] = reconnectToExistingJob(jobId, forceAbort = true)

  private def reconnectToExistingJob(jobForResumption: StandardAsyncJob, forceAbort: Boolean = false) = {
    if (forceAbort) tryAbort(jobForResumption)
    Future.successful(PendingExecutionHandle(jobDescriptor, jobForResumption, Option(Run(jobForResumption)), previousStatus = None))
  }

  private def createNewJob(): Future[ExecutionHandle] = {
    // Want to force runtimeAttributes to evaluate so we can fail quickly now if we need to:
    def evaluateRuntimeAttributes = Future.fromTry(Try(runtimeAttributes))

    def generateInputOutputParameters: Future[InputOutputParameters] = Future.fromTry(Try {
      val rcFileOutput = PipelinesApiFileOutput(returnCodeFilename, returnCodeGcsPath, DefaultPathBuilder.get(returnCodeFilename), workingDisk, optional = false, secondary = false)

      case class StandardStream(name: String, f: StandardPaths => Path) {
        val filename = f(pipelinesApiCallPaths.standardPaths).name
      }

      val standardStreams = List(
        StandardStream("stdout", _.output),
        StandardStream("stderr", _.error)
      ) map { s =>
        PipelinesApiFileOutput(s.name, returnCodeGcsPath.sibling(s.filename), DefaultPathBuilder.get(s.filename),
          workingDisk, optional = false, secondary = false, uploadPeriod = jesAttributes.logFlushPeriod, contentType = Option(ContentTypes.`text/plain(UTF-8)`))
      }

      InputOutputParameters(
        DetritusInputParameters(
          executionScriptInputParameter = cmdInput,
          monitoringScriptInputParameter = monitoringScript
        ),
        generateInputs(jobDescriptor).toList,
        generateOutputs(jobDescriptor).toList ++ standardStreams,
        DetritusOutputParameters(
          monitoringScriptOutputParameter = monitoringOutput,
          rcFileOutputParameter = rcFileOutput
        ),
        gcsAuthParameter.toList
      )
    })

    def uploadScriptFile = commandScriptContents.fold(
      errors => Future.failed(new RuntimeException(errors.toList.mkString(", "))),
      asyncIo.writeAsync(jobPaths.script, _, Seq(CloudStorageOptions.withMimeType("text/plain"))))

    val runPipelineResponse = for {
      _ <- evaluateRuntimeAttributes
      _ <- uploadScriptFile
      jesParameters <- generateInputOutputParameters
      createParameters = createPipelineParameters(jesParameters)
      runId <- runPipeline(workflowId, createParameters, jobLogger)
    } yield runId

    runPipelineResponse map { runId =>
      PendingExecutionHandle(jobDescriptor, runId, Option(Run(runId)), previousStatus = None)
    } recover {
      case JobAbortedException => AbortedExecutionHandle
    }
  }

  override def pollStatusAsync(handle: JesPendingExecutionHandle): Future[RunStatus] = {
    super[PipelinesApiStatusRequestClient].pollStatus(workflowId, handle.pendingJob)
  }

  override def customPollStatusFailure: PartialFunction[(ExecutionHandle, Exception), ExecutionHandle] = {
    case (_: JesPendingExecutionHandle@unchecked, JobAbortedException) =>
      AbortedExecutionHandle
    case (oldHandle: JesPendingExecutionHandle@unchecked, e: GoogleJsonResponseException) if e.getStatusCode == 404 =>
      jobLogger.error(s"JES Job ID ${oldHandle.runInfo.get.job} has not been found, failing call")
      FailedNonRetryableExecutionHandle(e)
  }

  override lazy val startMetadataKeyValues: Map[String, Any] = super[PipelinesApiJobCachingActorHelper].startMetadataKeyValues

  override def getTerminalMetadata(runStatus: RunStatus): Map[String, Any] = {
    runStatus match {
      case terminalRunStatus: TerminalRunStatus =>
        Map(
          PipelinesApiMetadataKeys.MachineType -> terminalRunStatus.machineType.getOrElse("unknown"),
          PipelinesApiMetadataKeys.InstanceName -> terminalRunStatus.instanceName.getOrElse("unknown"),
          PipelinesApiMetadataKeys.Zone -> terminalRunStatus.zone.getOrElse("unknown")
        )
      case unknown => throw new RuntimeException(s"Attempt to get terminal metadata from non terminal status: $unknown")
    }
  }

  override def mapOutputWomFile(womFile: WomFile): WomFile = {
    womFileToGcsPath(generateOutputs(jobDescriptor))(womFile)
  }

  protected [pipelines] def womFileToGcsPath(jesOutputs: Set[PipelinesApiOutput])(womFile: WomFile): WomFile = {
    womFile mapFile { path =>
      jesOutputs collectFirst {
        case jesOutput if jesOutput.name == makeSafeReferenceName(path) => jesOutput.cloudPath.pathAsString
      } getOrElse path
    }
  }

  override def isDone(runStatus: RunStatus): Boolean = {
    runStatus match {
      case _: RunStatus.Success => true
      case _: RunStatus.UnsuccessfulRunStatus => false
      case _ => throw new RuntimeException(s"Cromwell programmer blunder: isSuccess was called on an incomplete RunStatus ($runStatus).")
    }
  }

  override def getTerminalEvents(runStatus: RunStatus): Seq[ExecutionEvent] = {
    runStatus match {
      case successStatus: RunStatus.Success => successStatus.eventList
      case unknown =>
        throw new RuntimeException(s"handleExecutionSuccess not called with RunStatus.Success. Instead got $unknown")
    }
  }

  override def retryEvaluateOutputs(exception: Exception): Boolean = {
    exception match {
      case aggregated: CromwellAggregatedException =>
        aggregated.throwables.collectFirst { case s: SocketTimeoutException => s }.isDefined
      case _ => false
    }
  }

  private lazy val standardPaths = jobPaths.standardPaths

  override def handleExecutionFailure(runStatus: RunStatus,
                                      returnCode: Option[Int]): Future[ExecutionHandle] = {
    // Inner function: Handles a 'Failed' runStatus (or Preempted if preemptible was false)
    def handleFailedRunStatus(runStatus: RunStatus.UnsuccessfulRunStatus,
                              returnCode: Option[Int]): Future[ExecutionHandle] = {
      (runStatus.errorCode, runStatus.jesCode) match {
        case (Status.NOT_FOUND, Some(JesFailedToDelocalize)) => Future.successful(FailedNonRetryableExecutionHandle(FailedToDelocalizeFailure(runStatus.prettyPrintedError, jobTag, Option(standardPaths.error))))
        case (Status.ABORTED, Some(JesUnexpectedTermination)) => handleUnexpectedTermination(runStatus.errorCode, runStatus.prettyPrintedError, returnCode)
        case _ => Future.successful(FailedNonRetryableExecutionHandle(StandardException(
          runStatus.errorCode, runStatus.prettyPrintedError, jobTag, returnCode, standardPaths.error), returnCode))
      }
    }

    runStatus match {
      case preemptedStatus: RunStatus.Preempted if preemptible => handlePreemption(preemptedStatus, returnCode)
      case _: RunStatus.Cancelled => Future.successful(AbortedExecutionHandle)
      case failedStatus: RunStatus.UnsuccessfulRunStatus => handleFailedRunStatus(failedStatus, returnCode)
      case unknown => throw new RuntimeException(s"handleExecutionFailure not called with RunStatus.Failed or RunStatus.Preempted. Instead got $unknown")
    }
  }

  private def writeFuturePreemptedAndUnexpectedRetryCounts(p: Int, ur: Int): Future[Unit] = {
    val updateRequests = Seq(
      KvPut(KvPair(ScopedKey(workflowId, futureKvJobKey, PipelinesApiBackendLifecycleActorFactory.unexpectedRetryCountKey), ur.toString)),
      KvPut(KvPair(ScopedKey(workflowId, futureKvJobKey, PipelinesApiBackendLifecycleActorFactory.preemptionCountKey), p.toString))
    )

    makeKvRequest(updateRequests).map(_ => ())
  }

  private def handleUnexpectedTermination(errorCode: Status, errorMessage: String, jobReturnCode: Option[Int]): Future[ExecutionHandle] = {

    val msg = s"Retrying. $errorMessage"

    previousRetryReasons match {
      case Valid(PreviousRetryReasons(p, ur)) =>
        val thisUnexpectedRetry = ur + 1
        if (thisUnexpectedRetry <= maxUnexpectedRetries) {
          // Increment unexpected retry count and preemption count stays the same
          writeFuturePreemptedAndUnexpectedRetryCounts(p, thisUnexpectedRetry).map { _ =>
            FailedRetryableExecutionHandle(StandardException(
              errorCode, msg, jobTag, jobReturnCode, standardPaths.error), jobReturnCode)
          }
        }
        else {
          Future.successful(FailedNonRetryableExecutionHandle(StandardException(
            errorCode, errorMessage, jobTag, jobReturnCode, standardPaths.error), jobReturnCode))
        }
      case Invalid(_) =>
        Future.successful(FailedNonRetryableExecutionHandle(StandardException(
          errorCode, errorMessage, jobTag, jobReturnCode, standardPaths.error), jobReturnCode))
    }
  }

  private def handlePreemption(runStatus: RunStatus.Preempted, jobReturnCode: Option[Int]): Future[ExecutionHandle] = {
    import common.numeric.IntegerUtil._

    val errorCode: Status = runStatus.errorCode
    val prettyPrintedError: String = runStatus.prettyPrintedError
    previousRetryReasons match {
      case Valid(PreviousRetryReasons(p, ur)) =>
        val thisPreemption = p + 1
        val taskName = s"${workflowDescriptor.id}:${call.localName}"
        val baseMsg = s"Task $taskName was preempted for the ${thisPreemption.toOrdinal} time."

        writeFuturePreemptedAndUnexpectedRetryCounts(thisPreemption, ur).map { _ =>
          if (thisPreemption < maxPreemption) {
            // Increment preemption count and unexpectedRetryCount stays the same
            val msg =
              s"""$baseMsg The call will be restarted with another preemptible VM (max preemptible attempts number is $maxPreemption). Error code $errorCode.$prettyPrintedError""".stripMargin
            FailedRetryableExecutionHandle(StandardException(
              errorCode, msg, jobTag, jobReturnCode, standardPaths.error), jobReturnCode)
          }
          else {
            val msg = s"""$baseMsg The maximum number of preemptible attempts ($maxPreemption) has been reached. The call will be restarted with a non-preemptible VM. Error code $errorCode.$prettyPrintedError)""".stripMargin
            FailedRetryableExecutionHandle(StandardException(
              errorCode, msg, jobTag, jobReturnCode, standardPaths.error), jobReturnCode)
          }
        }
      case Invalid(_) =>
        Future.successful(FailedNonRetryableExecutionHandle(StandardException(
          errorCode, prettyPrintedError, jobTag, jobReturnCode, standardPaths.error), jobReturnCode))
    }
  }

  override def mapCommandLineWomFile(womFile: WomFile): WomFile = {
    womFile.mapFile(value =>
      (getPath(value), asAdHocFile(womFile)) match {
        case (Success(gcsPath: GcsPath), Some(adHocFile)) => 
          // Ad hoc files will be placed directly at the root ("/cromwell_root/ad_hoc_file.txt") unlike other input files
          // for which the full path is being propagated ("/cromwell_root/path/to/input_file.txt")
          workingDisk.mountPoint.resolve(adHocFile.alternativeName.getOrElse(gcsPath.name)).pathAsString
        case (Success(gcsPath: GcsPath), _) => 
          workingDisk.mountPoint.resolve(gcsPath.pathWithoutScheme).pathAsString
        case _ => value
      }
    )
  }

  override def mapCommandLineJobInputWomFile(womFile: WomFile): WomFile = {
    womFile.mapFile(value =>
      getPath(value) match {
        case Success(gcsPath: GcsPath) => workingDisk.mountPoint.resolve(gcsPath.pathWithoutScheme).pathAsString
        case _ => value
      }
    )
  }
  
  // No need for Cromwell-performed localization in the PAPI backend, ad hoc values are localized directly from GCS to the VM by PAPI.
  override lazy val localizeAdHocValues: List[AdHocValue] => ErrorOr[List[StandardAdHocValue]] = _.map(Coproduct[StandardAdHocValue](_)).validNel
}
