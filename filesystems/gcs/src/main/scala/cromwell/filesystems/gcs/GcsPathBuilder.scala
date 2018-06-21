package cromwell.filesystems.gcs

import java.io._
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.NoSuchFileException

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import better.files.File.OpenOptions
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.gax.retrying.RetrySettings
import com.google.auth.Credentials
import com.google.cloud.storage.Storage.{BlobSourceOption, BlobTargetOption}
import com.google.cloud.storage.contrib.nio.{CloudStorageConfiguration, CloudStorageFileSystem, CloudStoragePath}
import com.google.cloud.storage.{BlobId, BlobInfo, StorageOptions}
import com.google.common.cache.{Cache, CacheBuilder}
import com.google.common.net.UrlEscapers
import cromwell.cloudsupport.gcp.auth.GoogleAuthMode
import cromwell.cloudsupport.gcp.gcs.GcsStorage
import cromwell.core.WorkflowOptions
import cromwell.core.path.cache.BucketCache.DefaultBucketInformation
import cromwell.core.path.{NioPath, Path, PathBuilder}
import cromwell.filesystems.gcs.GcsPathBuilder._
import cromwell.filesystems.gcs.GoogleUtil._
import cromwell.filesystems.gcs.batch.{GcsBucketCache, GcsFileSystemCache}
import mouse.all._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Codec
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
object GcsPathBuilder {
  implicit class EnhancedCromwellPath(val path: Path) extends AnyVal {
    def requesterPaysProject: Option[String] = path match {
      case gcs: GcsPath => gcs.requesterPays.option(gcs.projectId)
      case _ => None
    }

    def requesterPaysGSUtilFlagList = requesterPaysProject.map(project => List("-u", project)).getOrElse(List.empty)
    def requesterPaysGSUtilFlag = requesterPaysGSUtilFlagList.mkString(" ")
  }

  /*
    * Provides some level of validation of GCS bucket names
    * This is meant to alert the user early if they mistyped a gcs path in their workflow / inputs and not to validate
    * exact bucket syntax, which is done by GCS.
    * See https://cloud.google.com/storage/docs/naming for full spec
  */
  val GcsBucketPattern =
    """
      (?x)                                      # Turn on comments and whitespace insensitivity
      ^gs://
      (                                         # Begin capturing group for gcs bucket name
        [a-z0-9][a-z0-9-_\\.]+[a-z0-9]          # Regex for bucket name - soft validation, see comment above
      )                                         # End capturing group for gcs bucket name
      (?:
        /.*                                     # No validation here
      )?
    """.trim.r

  sealed trait GcsPathValidation
  case class ValidFullGcsPath(bucket: String, path: String) extends GcsPathValidation
  case object PossiblyValidRelativeGcsPath extends GcsPathValidation
  sealed trait InvalidGcsPath extends GcsPathValidation {
    def pathString: String
    def errorMessage: String
  }
  final case class InvalidScheme(pathString: String) extends InvalidGcsPath {
    override def errorMessage = s"Cloud Storage URIs must have 'gs' scheme: $pathString"
  }
  final case class InvalidFullGcsPath(pathString: String) extends InvalidGcsPath {
    override def errorMessage = {
      s"""
         |The path '$pathString' does not seem to be a valid GCS path.
         |Please check that it starts with gs:// and that the bucket and object follow GCS naming guidelines at
         |https://cloud.google.com/storage/docs/naming.
      """.stripMargin.replace("\n", " ").trim
    }
  }
  final case class UnparseableGcsPath(pathString: String, throwable: Throwable) extends InvalidGcsPath {
    override def errorMessage: String =
      List(s"The specified GCS path '$pathString' does not parse as a URI.", throwable.getMessage).mkString("\n")
  }

  case class CromwellGcsFileSystem(filesystem: CloudStorageFileSystem, requesterPays: Boolean)

  /**
    * Tries to extract a bucket name out of the provided string using rules less strict that URI hostname,
    * as GCS allows albeit discourages.
    */
  private def softBucketParsing(string: String): Option[String] = string match {
    case GcsBucketPattern(bucket) => Option(bucket)
    case _ => None
  }

  def validateGcsPath(string: String): GcsPathValidation = {
    Try {
      val uri = URI.create(UrlEscapers.urlFragmentEscaper().escape(string))
      if (uri.getScheme == null) PossiblyValidRelativeGcsPath
      else if (uri.getScheme.equalsIgnoreCase(CloudStorageFileSystem.URI_SCHEME)) {
        if (uri.getHost == null) {
          softBucketParsing(string) map { ValidFullGcsPath(_, uri.getPath) } getOrElse InvalidFullGcsPath(string)
        } else ValidFullGcsPath(uri.getHost, uri.getPath)
      } else InvalidScheme(string)
    } recover { case t => UnparseableGcsPath(string, t) } get
  }

  def isGcsPath(nioPath: NioPath): Boolean = {
    nioPath.getFileSystem.provider().getScheme.equalsIgnoreCase(CloudStorageFileSystem.URI_SCHEME)
  }

  def fromAuthMode(authMode: GoogleAuthMode,
                   applicationName: String,
                   retrySettings: RetrySettings,
                   cloudStorageConfiguration: CloudStorageConfiguration,
                   options: WorkflowOptions,
                   defaultProject: Option[String],
                   requesterPaysCache: Cache[String, DefaultBucketInformation])(implicit as: ActorSystem, ec: ExecutionContext): Future[GcsPathBuilder] = {
    authMode.retryCredential(options) map { credentials =>
      fromCredentials(credentials,
        applicationName,
        retrySettings,
        cloudStorageConfiguration,
        options,
        defaultProject,
        requesterPaysCache
      )
    }
  }

  def fromCredentials(credentials: Credentials,
                      applicationName: String,
                      retrySettings: RetrySettings,
                      cloudStorageConfiguration: CloudStorageConfiguration,
                      options: WorkflowOptions,
                      defaultProject: Option[String],
                      requesterPaysCache: Cache[String, DefaultBucketInformation]): GcsPathBuilder = {
    // Grab the google project from Workflow Options if specified and set
    // that to be the project used by the StorageOptions Builder. If it's not
    // specified use the default project mentioned in config file
    val project: Option[String] =  options.get("google_project").toOption match {
      case Some(googleProject) => Option(googleProject)
      case None => defaultProject
    }

    val storageOptions = GcsStorage.gcsStorageOptions(credentials, retrySettings, project)

    // Create a com.google.api.services.storage.Storage
    // This is the underlying api used by com.google.cloud.storage
    // By bypassing com.google.cloud.storage, we can create low level requests that can be batched
    val apiStorage = GcsStorage.gcsStorage(applicationName, storageOptions)

    new GcsPathBuilder(apiStorage, cloudStorageConfiguration, storageOptions, requesterPaysCache)
  }
}

class GcsPathBuilder(apiStorage: com.google.api.services.storage.Storage,
                     cloudStorageConfiguration: CloudStorageConfiguration,
                     storageOptions: StorageOptions,
                     val bucketCacheGuava: Cache[String, DefaultBucketInformation]) extends PathBuilder {
  private [gcs] val bucketCache = new GcsBucketCache(cloudStorage, bucketCacheGuava)
  // We can cache filesystems per bucket objects here since they only depend on the bucket (and the credentials, which are unique per path builder)
  private [gcs] val fileSystemCache = new GcsFileSystemCache(cloudStorage, CacheBuilder.newBuilder().build[String, CloudStorageFileSystem](), cloudStorageConfiguration, storageOptions)

  private lazy val cloudStorage = storageOptions.getService

  private[gcs] val projectId = storageOptions.getProjectId

  /**
    * Tries to create a new GcsPath from a String representing an absolute gcs path: gs://<bucket>[/<path>].
    *
    * Note that this creates a new CloudStorageFileSystemProvider for every Path created, hence making it unsuitable for
    * file copying using the nio copy method. Cromwell currently uses a lower level API which works around this problem.
    *
    * If you plan on using the nio copy method make sure to take this into consideration.
    *
    * Also see https://github.com/GoogleCloudPlatform/google-cloud-java/issues/1343
    */
  def build(string: String): Try[GcsPath] = {
    validateGcsPath(string) match {
      case ValidFullGcsPath(bucket, path) =>
        Try {
          val fileSystem = fileSystemCache.getCachedValue(bucket)
          val cloudStoragePath = fileSystem.getPath(path)
          val requesterPays = bucketCache.getCachedValue(bucket).requesterPays
          GcsPath(cloudStoragePath, apiStorage, cloudStorage, projectId, requesterPays)
        }
      case PossiblyValidRelativeGcsPath => Failure(new IllegalArgumentException(s"$string does not have a gcs scheme"))
      case invalid: InvalidGcsPath => Failure(new IllegalArgumentException(invalid.errorMessage))
    }
  }

  override def name: String = "Google Cloud Storage"
}

case class GcsPath private[gcs](nioPath: NioPath,
                                apiStorage: com.google.api.services.storage.Storage,
                                cloudStorage: com.google.cloud.storage.Storage,
                                projectId: String,
                                requesterPays: Boolean) extends Path {
  lazy val blob = BlobId.of(cloudStoragePath.bucket, cloudStoragePath.toRealPath().toString)

  /**
    * Will be null if requesterPays is false 
    */
  val requesterPaysProject = requesterPays.option(projectId).orNull

  private lazy val userProjectBlobTarget: List[BlobTargetOption] = requesterPays.option(BlobTargetOption.userProject(projectId)).toList
  private lazy val userProjectBlobSource: List[BlobSourceOption] = requesterPays.option(BlobSourceOption.userProject(projectId)).toList

  override protected def newPath(nioPath: NioPath): GcsPath = GcsPath(nioPath, apiStorage, cloudStorage, projectId, requesterPays)

  override def pathAsString: String = {
    val host = cloudStoragePath.bucket().stripSuffix("/")
    val path = cloudStoragePath.toString.stripPrefix("/")
    s"${CloudStorageFileSystem.URI_SCHEME}://$host/$path"
  }

  override def writeContent(content: String)(openOptions: OpenOptions, codec: Codec) = {
    cloudStorage.create(
      BlobInfo.newBuilder(blob)
        .setContentType(ContentTypes.`text/plain(UTF-8)`.value)
        .build(),
      content.getBytes(codec.charSet),
      userProjectBlobTarget: _*
    )
    this
  }

  /***
    * This method needs to be overridden to make it work with requester pays. We need to go around Nio
    * as currently it doesn't support to set the billing project id. Google Cloud Storage already has
    * code in place to set the billing project (inside HttpStorageRpc) but Nio does not pass it even though
    * it's available. In future when it is supported, remove this method and wire billing project into code
    * wherever necessary
    */
  override def mediaInputStream: InputStream = {
    Try{
      Channels.newInputStream(cloudStorage.reader(blob, userProjectBlobSource: _*))
    } match {
      case Success(inputStream) => inputStream
      case Failure(e: GoogleJsonResponseException) if e.getStatusCode == StatusCodes.NotFound.intValue =>
        throw new NoSuchFileException(pathAsString)
      case Failure(e) => e.getMessage
        throw new IOException(s"Failed to open an input stream for $pathAsString: ${e.getMessage}", e)
    }
  }

  override def pathWithoutScheme: String = {
    val gcsPath = cloudStoragePath
    gcsPath.bucket + gcsPath.toAbsolutePath.toString
  }

  def cloudStoragePath: CloudStoragePath = nioPath match {
    case gcsPath: CloudStoragePath => gcsPath
    case _ => throw new RuntimeException(s"Internal path was not a cloud storage path: $nioPath")
  }
}
