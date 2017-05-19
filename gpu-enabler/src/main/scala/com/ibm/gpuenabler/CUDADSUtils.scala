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
package com.ibm.gpuenabler

import jcuda.driver.CUdeviceptr
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.types.StructType
import scala.collection.JavaConverters._
import scala.collection.mutable
import org.apache.spark.sql.gpuenabler.CUDAUtils._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning

case class MAPGPUExec[T, U](cf: DSCUDAFunction, constArgs : Array[Any],
                            outputArraySizes: Array[Int],
                            child: SparkPlan,
                            inputEncoder: Encoder[T], outputEncoder: Encoder[U],
                            outputObjAttr: Attribute,
                            cached: Int,
                            logPlans: Array[String])
  extends ObjectConsumerExec with ObjectProducerExec  {

  lazy val inputSchema: StructType = inputEncoder.schema
  lazy val outputSchema: StructType = outputEncoder.schema

  override def output: Seq[Attribute] = outputObjAttr :: Nil
  override def outputPartitioning: Partitioning = child.outputPartitioning

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext,
      "number of output rows"))

  protected override def doExecute(): RDD[InternalRow] = {
    val numOutputRows = longMetric("numOutputRows")

    val inexprEnc = inputEncoder.asInstanceOf[ExpressionEncoder[T]]
    val outexprEnc = outputEncoder.asInstanceOf[ExpressionEncoder[U]]

    val childRDD = child.execute()

    childRDD.mapPartitionsWithIndex{ (partNum, iter) =>
      // Generate the JCUDA program to be executed and obtain the iterator object
      val jcudaIterator = JCUDACodeGen.generate(inputSchema,
                     outputSchema,cf,constArgs, outputArraySizes)
      val list = new mutable.ListBuffer[InternalRow]

      // Get hold of hashmap for this Plan to store the GPU pointers from output parameters
      val curPlanPtrs: java.util.Map[String, CUdeviceptr] = if ((cached & 1) > 0) {
        GPUSparkEnv.get.gpuMemoryManager.getCachedGPUPointersDS.getOrElse(logPlans(0), null).asJava
      } else { // Return Empty Map
        Map[String, CUdeviceptr]().asJava
      }

      // Get hold of hashmap for the child Plan to use the GPU pointers for input parameters
      val childPlanPtrs: java.util.Map[String, CUdeviceptr] =
        if ((cached & 2) > 0) {
          GPUSparkEnv.get.gpuMemoryManager.getCachedGPUPointersDS
		.getOrElse(logPlans(1), null).asJava
        } else { // Return Empty Map
          Map[String, CUdeviceptr]().asJava
        }

      val imgpuPtrs: java.util.List[java.util.Map[String, CUdeviceptr]] =
		List(curPlanPtrs, childPlanPtrs).asJava

      val dataSize = {
        val now1 = System.nanoTime
        var size = 0
        iter.foreach(x => {
            list += inexprEnc.toRow(x.get(0, inputSchema).asInstanceOf[T]).copy()
	    size += 1
        })
        size
      } 

      // Compute the GPU Grid Dimensions based on the input data size
      // For user provided Dimensions; retrieve it along with the 
      // respective stage information.
      val (stages, userGridSizes, userBlockSizes) =
              JCUDACodeGen.getUserDimensions(cf, dataSize)

      // Initialize the auto generated code's iterator
      jcudaIterator.init(list.toIterator.asJava, constArgs,
                dataSize, cached, imgpuPtrs, partNum,
                userGridSizes, userBlockSizes, stages)

      jcudaIterator.hasNext()

      val outEnc = outexprEnc
        .resolveAndBind(getAttributes(outputEncoder.schema))

      new Iterator[InternalRow] {
        override def hasNext: Boolean = jcudaIterator.hasNext()

        override def next: InternalRow =
          InternalRow(outEnc
             .fromRow(jcudaIterator.next().copy()))
      }
    }
  }
}

object MAPGPU
{
  def apply[T: Encoder, U : Encoder](
                                      func: DSCUDAFunction,
                                      args : Array[Any],
                                      outputArraySizes: Array[Int],
                                      child: LogicalPlan) : LogicalPlan = {
    val deserialized = CatalystSerde.deserialize[T](child)
    val mapped = MAPGPU(
      func, args, outputArraySizes,
      deserialized,
      implicitly[Encoder[T]],
      implicitly[Encoder[U]],
      CatalystSerde.generateObjAttr[U]
    )

    CatalystSerde.serialize[U](mapped)
  }
}

case class MAPGPU[T: Encoder, U : Encoder](func: DSCUDAFunction,
			   args : Array[Any],
			   outputArraySizes: Array[Int],
			   child: LogicalPlan,
			   inputEncoder: Encoder[T], outputEncoder: Encoder[U],
			   outputObjAttr: Attribute)
  extends ObjectConsumer with ObjectProducer {
  override def otherCopyArgs : Seq[AnyRef] =
				inputEncoder :: outputEncoder ::  Nil
}

object GPUOperators extends Strategy {
  def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case MAPGPU(cf, args, outputArraySizes, child,inputEncoder, outputEncoder,
         outputObjAttr) =>
      // Differentiate cache by setting:
      // cached: 1 -> this logical plan is cached; 
      // cached: 2 -> child logical plan is cached;
      // cached: 0 -> NoCache;
      val DScache = GPUSparkEnv.get.gpuMemoryManager.cachedGPUDS
      var cached = if (DScache.contains(md5HashObj(plan))) 1 else 0
      val modChildPlan = child match {
        case DeserializeToObject(_, _, lp) => lp
	case _ => child
      }
      cached |= (if(DScache.contains(md5HashObj(modChildPlan))) 2 else 0)

      // Store the logical plan UID and pass it to physical plan as 
      // cached it done with logical plan UID.
      val logPlans = new Array[String](2)
      logPlans(0) = md5HashObj(plan)
      logPlans(1) = md5HashObj(modChildPlan)

      MAPGPUExec(cf, args, outputArraySizes, planLater(child),
        inputEncoder, outputEncoder, outputObjAttr, cached, logPlans) :: Nil
    case _ => Nil
  }
}

/**
  * DSCUDAFunction: This case class is used to describe the CUDA kernel and 
  *   maps the i/o parameters to the DataSet's column name on which this 
  *   function is applied. Stages & Dimensions can be specified. If the
  *   kernel is going to perform a reduce kind of operation, the output size
  *   will be different from the input size, so it must be provided by the user
  */
case class DSCUDAFunction(
                           funcName: String,
                           _inputColumnsOrder: Seq[String] = null,
                           _outputColumnsOrder: Seq[String] = null,
                           resource: Any,
                           stagesCount: Option[Long => Int] = None,
                           dimensions: Option[(Long, Int) => (Int, Int)] = None,
                           outputSize: Option[Long] = None
                         )

/**
  * Adds additional functionality to existing Dataset/DataFrame's which are
  * specific to performing computation on Nvidia GPU's attached
  * to executors. To use these additional functionality import
  * the following packages,
  *
  * {{{
  * import com.ibm.gpuenabler.cuda._
  * import com.ibm.gpuenabler.CUDADSImplicits._
  * }}}
  *
  */
object CUDADSImplicits {
  implicit class CUDADSFuncs[T: Encoder](ds: _ds[T]) extends Serializable {
    /**
      * Return a new Dataset by applying a function to all elements of this Dataset.
      *
      * @param func  Specify the lambda to apply to all elements of this Dataset
      * @param cf  Provide the ExternalFunction instance which points to the
      *                 GPU native function to be executed for each element in
      *                 this Dataset
      * @param args Specify a list of free variable that need to be
      *                           passed in to the GPU kernel function, if any
      * @param outputArraySizes If the expected result is an array folded in a linear
      *                         form, specific a sequence of the array length for every
      *                         output columns
      * @tparam U Result Dataset type
      * @return Return a new Dataset of type U after executing the user provided
      *         GPU function on all elements of this Dataset
      */
    def mapExtFunc[U:Encoder](func: T => U,
                          cf: DSCUDAFunction,
                          args: Array[Any] = Array.empty,
                          outputArraySizes: Array[Int] = Array.empty): Dataset[U] =  {

      DS[U](ds.sparkSession,
          MAPGPU[T, U](cf, args, outputArraySizes,
            getLogicalPlan(ds)))
    }

    /**
      * Trigger a reduce action on all elements of this Dataset.
      *
      * @param func Specify the lambda to apply to all elements of this Dataset
      * @param cf Provide the DSCUDAFunction instance which points to the
      *                 GPU native function to be executed for each element in
      *                 this Dataset
      * @param args Specify a list of free variable that need to be
      *                           passed in to the GPU kernel function, if any
      * @param outputArraySizes If the expected result is an array folded in a linear
      *                         form, specific a sequence of the array length for every
      *                         output columns
      * @return Return the result after performing a reduced operation on all
      *         elements of this Dataset
      */
    def reduceExtFunc(func: (T, T) => T,
                          cf: DSCUDAFunction,
                          args: Array[Any] = Array.empty,
                          outputArraySizes: Array[Int] = Array.empty): T =  {

      val ds1 = DS[T](ds.sparkSession,
        MAPGPU[T, T](cf, args, outputArraySizes,
          getLogicalPlan(ds)))

      ds1.reduce(func)

    }

    /**
      * This function is used to mark the respective Dataset's data to
      * be cached in GPU for future computation rather than cleaning it
      * up every time the DataSet is processed. 
      * 
      * By marking an DataSet to cache in GPU, huge performance gain can
      * be achieved as data movement between CPU memory and GPU 
      * memory is considered costly.
      */
    def cacheGpu(): Dataset[T] = {
      val logPlan = ds.queryExecution.optimizedPlan match {
        case SerializeFromObject(_, lp) => lp
	case _ => ds.queryExecution.optimizedPlan
      }

      GPUSparkEnv.get.gpuMemoryManager.cacheGPUSlaves(md5HashObj(logPlan))
      ds
    }

    /**
      * This function is used to clean up all the caches in GPU held
      * by the respective DataSet on the various partitions.
      */
    def unCacheGpu(): Dataset[T] = {
      val logPlan = ds.queryExecution.optimizedPlan match {
        case SerializeFromObject(_, lp) => lp
	case _ => ds.queryExecution.optimizedPlan
      }
  
      GPUSparkEnv.get.gpuMemoryManager.unCacheGPUSlaves(md5HashObj(logPlan))
      ds
    }

    ds.sparkSession.experimental.extraStrategies = GPUOperators :: Nil
  }
}