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

import quasar.physical.mongo.{Aggregator, Version, MongoExpression => E}, E.ProjectionStep.Field

object Wrap {
  def apply(
      uniqueKey: String,
      version: Version,
      wrap: Field)
      : List[Aggregator] = {

    val tmpKey = uniqueKey.concat("_wrap")
    List(
      Aggregator.project(E.Object(tmpKey -> E.Object(wrap.name -> E.key(uniqueKey)))),
      Aggregator.project(E.Object(uniqueKey -> E.key(tmpKey))))
    }
}