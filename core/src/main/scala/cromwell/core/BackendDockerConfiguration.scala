package cromwell.core

import com.typesafe.config.Config
import cromwell.core.ConfigUtil._

object DockerCredentials {
  def unapply(arg: DockerCredentials): Option[(String, String)] = Option(arg.account -> arg.token)
}

/**
  * Encapsulate docker credential information.
  */
class DockerCredentials(val account: String, val token: String)

case class BackendDockerConfiguration(dockerCredentials: Option[DockerCredentials])

/**
  * Singleton encapsulating a DockerConf instance.
  */
object BackendDockerConfiguration {

  private val dockerKeys = Set("account", "token")

  def build(config: Config) = {
    import net.ceedubs.ficus.Ficus._
    val dockerConf: Option[DockerCredentials] = for {
      dockerConf <- config.as[Option[Config]]("dockerhub")
      _ = dockerConf.warnNotRecognized(dockerKeys, "dockerhub")
      account <- dockerConf.validateString("account").toOption
      token <- dockerConf.validateString("token").toOption
    } yield new DockerCredentials(account, token)

    new BackendDockerConfiguration(dockerConf)
  }
}
