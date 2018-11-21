/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.physical.mongo

import slamdata.Predef._

import cats.syntax.apply._
import cats.effect.{IO, ContextShift, Timer}
import org.specs2.mutable.Specification
import org.specs2.execute.AsResult
import org.mongodb.scala._
import org.bson.{Document => _, _}
import scala.io.Source
import scala.concurrent.ExecutionContext
import quasar.contrib.scalaz.MonadError_
import quasar.connector.ResourceError
import fs2.Stream
import shims._

class MongoSpec extends Specification {
  import MongoSpec._


  step(MongoSpec.setupDB)

  "can create client from valid connection string" >> {
    mkMongo.attempt.compile.last.unsafeRunSync() match {
      case None => AsResult(false).updateMessage("Impossible happened, Mongo.apply.attempt must return something")
      case Some(Left(thr)) => AsResult(false).updateMessage("Mongo.apply returned an error" ++ thr.getMessage())
      case Some(Right(_)) => AsResult(true)
    }
  }

  "getting databases works correctly" >> {
    val stream = for {
      mongo <- mkMongo
      databases <- mongo.databases
    } yield databases
    val evaluatedDbs = stream.compile.toList.unsafeRunSync()
    val expectedDbs = (MongoSpec.dbs ++ List("admin", "local")).map(Database(_))
    evaluatedDbs === expectedDbs
  }

  "databaseExists returns true for existing dbs" >>  {
    val stream = for {
      mongo <- mkMongo
      db <- MongoSpec.correctDbs
      exists <- mongo.databaseExists(db)
    } yield exists
    stream.fold(true)(_ && _).compile.last.unsafeRunSync().getOrElse(false)
  }

  "databaseExists returns false for non-existing dbs" >> {
    val stream = for {
      mongo <- mkMongo
      db <- MongoSpec.incorrectDbs
      exists <- mongo.databaseExists(db)
    } yield exists
    !stream.fold(false)(_ || _).compile.last.unsafeRunSync().getOrElse(true)
  }

  "collections returns correct collection lists" >> {
    def checkOneDb(client: Mongo[IO], db: Database): Stream[IO, Boolean] = {
      client
        .collections(db)
        .fold(List[Collection]())((lst, coll) => coll :: lst)
        .map(collectionList => {
          collectionList.toSet === MongoSpec.cols.map(c => Collection(db, c)).toSet
        })
    }
    val stream = for {
      mongo <- mkMongo
      db <- mongo.databases.filter(db => MongoSpec.dbs.contains(db.name))
      checked <- checkOneDb(mongo, db)
    } yield checked
    stream.fold(true)(_ && _).compile.last.unsafeRunSync().getOrElse(false)
  }

  "collections return error stream for non-existing databases" >> {
    val stream = for {
      mongo <- mkMongo
      db <- MongoSpec.incorrectDbs
      col <- mongo.collections(db)
    } yield col
    stream.compile.toList.unsafeRunSync() === List[Collection]()
  }

  "collectionExists returns true for existent collections" >> {
    val stream = for {
      mongo <- mkMongo
      col <- correctCollections
      exists <- mongo.collectionExists(col)
    } yield exists

    stream.fold(true)(_ && _).compile.last.unsafeRunSync().getOrElse(false)
  }

  "collectionExists returns false for non-existent collections" >> {

    val stream = for {
      mongo <- mkMongo
      col <- incorrectCollections
      exists <- mongo.collectionExists(col)
    } yield exists

    !stream.fold(false)(_ || _).compile.last.unsafeRunSync().getOrElse(true)
  }

  "findAll returns correct results for existing collections" >> {
    def checkFindAll(client: Mongo[IO], col: Collection): Stream[IO, Boolean] =
      client.findAll(col).fold(List[BsonValue]())((lst, col) => col :: lst).map(bsons => (bsons, col) match {
        case (((bson: BsonDocument) :: List()), Collection(Database(dbName), colName)) =>
          try {
            bson.getString(colName).getValue() === dbName
          } catch {
            case e: Throwable => false
          }
        case _ => false
      })
    val stream = for {
      mongo <- mkMongo
      col <- correctCollections
      correct <- checkFindAll(mongo, col)
    } yield correct
    stream.fold(true)(_ && _).compile.last.unsafeRunSync().getOrElse(false)
  }

  "findAll returns correct result for nonexisting collections" >> {
    def checkFindAll(client: Mongo[IO], col: Collection): Stream[IO, Boolean] =
      client
        .findAll(col)
        .attempt
        .fold(List[Either[Throwable, BsonValue]]())((lst, col) => col :: lst)
        .map(_ match {
          case Left(_) :: List() => true
          case _ => false
        })
    val stream = for {
      mongo <- mkMongo
      col <- incorrectCollections
      correct <- checkFindAll(mongo, col)
    } yield correct
    stream.fold(true)(_ && _).compile.last.unsafeRunSync().getOrElse(false)
  }
/*
  "raise errors when mongodb is unreachable" >>  {
    val unreachableURI = "mongodb://unreachable"
    Mongo[IO](MongoConfig(unreachableURI)).attempt.compile.last.unsafeRunSync() match {
      case None => AsResult(false).updateMessage("Impossible happened, Mongo.apply.attempt must return something")
      case Some(Left(_)) => AsResult(true)
      case Some(Right(_)) => AsResult(false).updateMessage("Mongo.apply.attempt worked for incorrect connection string")
    }
  }
 */

}

object MongoSpec {
  import Mongo._

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)
  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)

  val dbs = List("A", "B", "C", "D")
  val cols = List("a", "b", "c", "d")
  val nonexistentDbs = List("Z", "Y")
  val nonexistentCols = List("z", "y")

  lazy val connectionString = Source.fromFile("./datasource/src/test/resource/mongo-connection").mkString.trim

  def mkMongo: Stream[IO, Mongo[IO]] =
    Mongo[IO](MongoConfig(connectionString))

  def incorrectCollections: Stream[IO, Collection] = {
    val incorrectDbStream =
      Stream.emits(nonexistentDbs)
        .map((dbName: String) => (colName: String) => Collection(Database(dbName), colName))
        .ap(Stream.emits(cols ++ nonexistentCols))
        .covary[IO]
    val incorrectColStream =
      Stream.emits(nonexistentCols)
        .map((colName: String) => (dbName: String) => Collection(Database(dbName), colName))
        .ap(Stream.emits(dbs))
        .covary[IO]
    incorrectDbStream ++ incorrectColStream
  }

  def correctCollections: Stream[IO, Collection] = {
    Stream.emits(dbs)
      .map((dbName: String) => (colName: String) => Collection(Database(dbName), colName))
      .ap(Stream.emits(cols))
  }

  def correctDbs: Stream[IO, Database] = {
    Stream.emits(dbs).map(Database(_))
  }
  def incorrectDbs: Stream[IO, Database] = {
    Stream.emits(nonexistentDbs).map(Database(_))
  }

  def setupDB(): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val stream = for {
      client <- Stream.eval(IO.delay(MongoClient(connectionString)))
      dbName <- Stream.emits(dbs)
      colName <- Stream.emits(cols)
      db <- Stream.eval(IO.delay(client.getDatabase(dbName)))
      coll <- Stream.eval(IO.delay(db.getCollection(colName)))
      _ <- observableAsStream[IO, Completed](coll.drop).attempt
      _ <- observableAsStream[IO, Completed](coll.insertOne(Document(colName -> dbName)))
    } yield ()

    stream.compile.drain.unsafeRunSync()
  }
}
