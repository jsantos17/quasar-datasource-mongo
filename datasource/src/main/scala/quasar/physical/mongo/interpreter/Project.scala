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

package quasar.physical.mongo.interpreter

import slamdata.Predef._

import cats.syntax.order._

import quasar.common.CPath
import quasar.physical.mongo.{Aggregator, Version, MongoExpression => E}

import org.bson._

import matryoshka.birecursiveIso
import matryoshka.data.Fix

import shims._

object Project {
  def apply(
      uniqueKey: String,
      version: Version,
      path: CPath)
      : Option[List[Aggregator]] = {

    E.cpathToProjection(path) flatMap { (fld: E.Projection) =>
      if (fld.minVersion > version) None else Some {
        val tmpKey =
          uniqueKey.concat("_project")
        val projection =
          E.key(uniqueKey) +/ fld
        val match_ =
          Aggregator.filter(E.Object(projection.toKey -> E.Object("$exists" -> E.Bool(true))))
        val move =
          Aggregator.project(E.Object(tmpKey -> projection))
        val project =
          Aggregator.project(E.Object(uniqueKey -> E.key(tmpKey)))
        List(match_, move, project)
      }
    }
  }

  import quasar.physical.mongo.{Expression, Optics, CustomPipeline, MongoPipeline, Pipeline, Projection}, Expression._
  def apply0(uniqueKey: String, path: CPath): Option[List[Pipeline[Fix[Projected]]]] = {
    val O = Optics.full(birecursiveIso[Fix[Projected], Projected].reverse.asPrism)

    Projection.fromCPath(path) map { (fld: Projection) =>
      val tmpKey = uniqueKey.concat("_project")
      val projection = Projection.key(uniqueKey) + fld
      val match_ = Pipeline.$match(Map(projection.toKey -> O.$exists(O.bool(true))))
      val move = Pipeline.$project(Map(tmpKey -> O.projection(projection)))
      val project = Pipeline.$project(Map(uniqueKey -> O.key(tmpKey)))
      List(match_, move, project)
    }
  }
}
