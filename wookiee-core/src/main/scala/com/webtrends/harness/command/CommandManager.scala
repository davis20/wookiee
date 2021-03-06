/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webtrends.harness.command

import akka.pattern.{ask, pipe}
import akka.actor.{ActorRef, Props}
import akka.routing.{RoundRobinPool, FromConfig}
import com.webtrends.harness.HarnessConstants
import com.webtrends.harness.app.{PrepareForShutdown, HActor}
import com.webtrends.harness.app.HarnessActor.SystemReady
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Success, Failure}

case class AddCommandWithProps[T<:Command](name:String, props:Props)
case class AddCommand[T<:Command](name:String, actorClass:Class[T])
case class ExecuteCommand(name:String, bean:Option[CommandBean]=None)
case class ExecuteRemoteCommand(name:String, server:String, port:Int, bean:Option[CommandBean]=None)
case class CommandResponse[T](data:Option[T], responseType:String="json")

/**
 * @author Michael Cuthbert on 12/1/14.
 */
class CommandManager extends PrepareForShutdown {

  import context.dispatcher

  override def receive = super.receive orElse {
    case AddCommandWithProps(name, props) => pipe(addCommand(name, props)) to sender
    case AddCommand(name, actorClass) => pipe(addCommand(name, actorClass)) to sender
    case ExecuteCommand(name, bean) => pipe(executeCommand(name, bean)) to sender
    case ExecuteRemoteCommand(name, server, port, bean) => pipe(executeRemoteCommand(name, server, port, bean)) to sender
    case SystemReady => // ignore
  }

  /**
   * Wrapper around the addCommand function that creates a function with props, this will just get the
   * props from the actor class
   *
   * @param name name of the command you want to add
   * @param actorClass the actor class for the command
   * @tparam T
   * @return
   */
  protected def addCommand[T<:Command](name:String, actorClass:Class[T]) : Future[ActorRef] = addCommand[T](name, Props(actorClass))

  /**
   * We add commands as children to the CommandManager, based on default routing
   * or we use the setup defined for the command
   *
   * @param name
   * @param props
   */
  protected def addCommand[T<:Command](name:String, props:Props) : Future[ActorRef] = {
    // check first if the router props have been defined else
    // use the default Round Robin approach
    val config = context.system.settings.config
    val aRef = if (!config.hasPath(s"akka.actor.deployment.${HarnessConstants.CommandFullName}/$name")) {
      val nrRoutees = config.getInt(HarnessConstants.KeyCommandsNrRoutees)
      context.actorOf(RoundRobinPool(nrRoutees).props(props), name)
    } else {
      context.actorOf(FromConfig.props(props), name)
    }
    CommandManager.addCommand(name, aRef)
    Future { aRef }
  }

  /**
   * Executes a remote command and will return a commandResponse to the sender
   *
   * @param name The name of the command you want to execute
   * @param server The server that has the command on
   * @param port the port that the server is listening on
   * @param bean
   */
  protected def executeRemoteCommand[T](name:String, server:String, port:Int=2552, bean:Option[CommandBean]=None) : Future[CommandResponse[T]] = {
    val p = Promise[CommandResponse[T]]
    context.system.settings.config.getString("akka.actor.provider") match {
      case "akka.remote.RemoteActorRefProvider" =>
        context.actorSelection(CommandManager.getRemoteAkkaPath(server, port)).resolveOne() onComplete {
          case Success(ref) =>
            (ref ? ExecuteCommand(name, bean)).mapTo[CommandResponse[T]] onComplete {
              case Success(s) => p success s
              case Failure(f) => p failure f
            }
          case Failure(f) => p failure new CommandException("CommandManager", s"Failed to find remote system [$server:$port]", Some(f))
        }
      case _ => p failure new CommandException("CommandManager", s"Remote provider for akka is not enabled")
    }
    p.future
  }

  /**
   * Executes a command and will return a CommandResponse to the sender
   *
   * @param name
   * @param bean
   */
  protected def executeCommand[T](name:String, bean:Option[CommandBean]=None) : Future[CommandResponse[T]] = {
    val p = Promise[CommandResponse[T]]
    CommandManager.getCommand(name) match {
      case Some(ref) =>
        (ref ? ExecuteCommand(name, bean)).mapTo[CommandResponse[T]] onComplete {
          case Success(s) => p success s
          case Failure(f) => p failure f
        }
      case None => p failure CommandException(name, "Command not found")
    }
    p.future
  }
}

object CommandManager {
  private val externalLogger = LoggerFactory.getLogger(this.getClass)

  // map that stores the name of the command with the actor it references
  val commandMap = mutable.Map[String, ActorRef]()

  def props = Props[CommandManager]

  def addCommand(name:String, ref:ActorRef) = {
    externalLogger.debug(s"Command $name with path ${ref.path} inserted into Command Manager map.")
    commandMap += (name -> ref)
  }

  protected def removeCommand(name:String) : Boolean = {
    commandMap.get(name) match {
      case Some(n) =>
        externalLogger.debug(s"Command $name with path ${n.path} removed from Command Manager map.")
        commandMap -= name
        true
      case None => false
    }
  }

  def getCommand(name:String) : Option[ActorRef] = commandMap.get(name)

  def getRemoteAkkaPath(server:String, port:Int) : String = s"akka.tcp://server@$server:$port${HarnessConstants.CommandFullName}"
}
