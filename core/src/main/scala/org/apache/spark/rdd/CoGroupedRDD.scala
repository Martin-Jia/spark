/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rdd

import java.io.{ObjectOutputStream, IOException}

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{InterruptibleIterator, Partition, Partitioner, SparkEnv, TaskContext}
import org.apache.spark.{Dependency, OneToOneDependency, ShuffleDependency}
import org.apache.spark.util.collection.{ExternalAppendOnlyMap, AppendOnlyMap}

private[spark] sealed trait CoGroupSplitDep extends Serializable

private[spark] case class NarrowCoGroupSplitDep(
    rdd: RDD[_],
    splitIndex: Int,
    var split: Partition
  ) extends CoGroupSplitDep {

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream) {
    // Update the reference to parent split at the time of task serialization
    split = rdd.partitions(splitIndex)
    oos.defaultWriteObject()
  }
}

private[spark] case class ShuffleCoGroupSplitDep(shuffleId: Int) extends CoGroupSplitDep

private[spark]
class CoGroupPartition(idx: Int, val deps: Array[CoGroupSplitDep])
  extends Partition with Serializable {
  override val index: Int = idx
  override def hashCode(): Int = idx
}

/**
 * A RDD that cogroups its parents. For each key k in parent RDDs, the resulting RDD contains a
 * tuple with the list of values for that key.
 *
 * @param rdds parent RDDs.
 * @param part partitioner used to partition the shuffle output.
 */
class CoGroupedRDD[K](@transient var rdds: Seq[RDD[_ <: Product2[K, _]]], part: Partitioner)
  extends RDD[(K, Seq[Seq[_]])](rdds.head.context, Nil) {

  private type CoGroup = ArrayBuffer[Any]
  private type CoGroupValue = (Any, Int)  // Int is dependency number
  private type CoGroupCombiner = Seq[CoGroup]

  private var serializerClass: String = null

  def setSerializer(cls: String): CoGroupedRDD[K] = {
    serializerClass = cls
    this
  }

  override def getDependencies: Seq[Dependency[_]] = {
    rdds.map { rdd: RDD[_ <: Product2[K, _]] =>
      if (rdd.partitioner == Some(part)) {
        logDebug("Adding one-to-one dependency with " + rdd)
        new OneToOneDependency(rdd)
      } else {
        logDebug("Adding shuffle dependency with " + rdd)
        new ShuffleDependency[Any, Any](rdd, part, serializerClass)
      }
    }
  }

  override def getPartitions: Array[Partition] = {
    val array = new Array[Partition](part.numPartitions)
    for (i <- 0 until array.size) {
      // Each CoGroupPartition will have a dependency per contributing RDD
      array(i) = new CoGroupPartition(i, rdds.zipWithIndex.map { case (rdd, j) =>
        // Assume each RDD contributed a single dependency, and get it
        dependencies(j) match {
          case s: ShuffleDependency[_, _] =>
            new ShuffleCoGroupSplitDep(s.shuffleId)
          case _ =>
            new NarrowCoGroupSplitDep(rdd, i, rdd.partitions(i))
        }
      }.toArray)
    }
    array
  }

  override val partitioner = Some(part)

  override def compute(s: Partition, context: TaskContext): Iterator[(K, CoGroupCombiner)] = {
    // e.g. for `(k, a) cogroup (k, b)`, K -> Seq(ArrayBuffer as, ArrayBuffer bs)
    val externalSorting = System.getProperty("spark.shuffle.externalSorting", "false").toBoolean
    val split = s.asInstanceOf[CoGroupPartition]
    val numRdds = split.deps.size

    // A list of (rdd iterator, dependency number) pairs
    val rddIterators = new ArrayBuffer[(Iterator[Product2[K, Any]], Int)]
    for ((dep, depNum) <- split.deps.zipWithIndex) dep match {
      case NarrowCoGroupSplitDep(rdd, _, itsSplit) => {
        // Read them from the parent
        val v = (rdd.iterator(itsSplit, context).asInstanceOf[Iterator[Product2[K, Any]]], depNum)
        rddIterators += v
      }
      case ShuffleCoGroupSplitDep(shuffleId) => {
        // Read map outputs of shuffle
        val fetcher = SparkEnv.get.shuffleFetcher
        val ser = SparkEnv.get.serializerManager.get(serializerClass)
        val v = (fetcher.fetch[Product2[K, Any]](shuffleId, split.index, context, ser), depNum)
        rddIterators += v
      }
    }

    if (!externalSorting) {
      val map = new AppendOnlyMap[K, CoGroupCombiner]
      val update: (Boolean, CoGroupCombiner) => CoGroupCombiner = (hadVal, oldVal) => {
        if (hadVal) oldVal else Array.fill(numRdds)(new ArrayBuffer[Any])
      }
      rddIterators.foreach { case (it, depNum) =>
        while (it.hasNext) {
          val kv = it.next()
          map.changeValue(kv._1, update)(depNum) += kv._2
        }
      }
      new InterruptibleIterator(context, map.iterator)
    } else {
      val map = createExternalMap(numRdds)
      rddIterators.foreach { case (it, depNum) =>
        while (it.hasNext) {
          val kv = it.next()
          map.insert(kv._1, new CoGroupValue(kv._2, depNum))
        }
      }
      new InterruptibleIterator(context, map.iterator)
    }
  }

  private def createExternalMap(numRdds: Int):
    ExternalAppendOnlyMap[K, CoGroupValue, CoGroupCombiner] = {

    val createCombiner: (CoGroupValue => CoGroupCombiner) = value => {
      val newCombiner = Array.fill(numRdds)(new CoGroup)
      value match { case(v, depNum) => newCombiner(depNum) += v }
      newCombiner
    }
    val mergeValue: (CoGroupCombiner, CoGroupValue) => CoGroupCombiner =
      (combiner, value) => {
      value match { case(v, depNum) => combiner(depNum) += v }
      combiner
    }
    val mergeCombiners: (CoGroupCombiner, CoGroupCombiner) => CoGroupCombiner =
      (combiner1, combiner2) => {
      combiner1.zipAll(combiner2, new CoGroup, new CoGroup).map {
        case (v1, v2) => v1 ++ v2
      }
    }
    new ExternalAppendOnlyMap[K, CoGroupValue, CoGroupCombiner](
      createCombiner, mergeValue, mergeCombiners)
  }

  override def clearDependencies() {
    super.clearDependencies()
    rdds = null
  }
}
