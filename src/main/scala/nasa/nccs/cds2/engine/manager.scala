package nasa.nccs.cds2.engine
import java.io.{IOException, PrintWriter, StringWriter}
import java.nio.FloatBuffer
import java.io.File

import nasa.nccs.cdapi.cdm.{Collection, PartitionedFragment, _}
import nasa.nccs.cds2.loaders.{Collections, Masks}
import nasa.nccs.esgf.process._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import nasa.nccs.utilities.{Loggable, cdsutils}
import nasa.nccs.cds2.kernels.{KernelMgr, KernelModule}
import nasa.nccs.cdapi.kernels._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}
import java.util.concurrent.atomic.AtomicReference

import nasa.nccs.cdapi.tensors.{CDArray, CDByteArray, CDFloatArray}
import nasa.nccs.caching._
import ucar.{ma2, nc2}
import nasa.nccs.cds2.utilities.{GeoTools, appParameters, runtime}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import java.util.concurrent._

import nasa.nccs.cds2.engine.futures.CDFuturesExecutionManager
import nasa.nccs.cds2.engine.spark.{CDSparkContext, CDSparkExecutionManager}
import nasa.nccs.wps._
import org.apache.spark.{SparkConf, SparkContext}
import ucar.nc2.dataset.NetcdfDataset

import scala.xml.Elem

class Counter(start: Int = 0) {
  private val index = new AtomicReference(start)
  def get: Int = {
    val i0 = index.get
    if(index.compareAndSet( i0, i0 + 1 )) i0 else get
  }
}

object CDS2ExecutionManager extends Loggable {
  val handler_type_key = "execution.handler.type"

  def apply(): CDS2ExecutionManager =
    appParameters( handler_type_key, "spark" ) match {
      case exeMgr if exeMgr.toLowerCase.startsWith("future") =>
        logger.info("\nExecuting Futures manager: serverConfig = " + exeMgr)
        new CDFuturesExecutionManager()
      case exeMgr if exeMgr.toLowerCase.startsWith("spark") =>
        logger.info("\nExecuting Spark manager: serverConfig = " + exeMgr)
        new CDSparkExecutionManager()
      case x => throw new Exception("Unrecognized execution.manager.type: " + x)
    }


  def getConfigParamValue( key: String, serverConfiguration: Map[String,String], default_val: String  ): String =
    serverConfiguration.get( key ) match {
      case Some( htype ) => htype
      case None => appParameters( key, default_val )
    }
}

abstract class CDS2ExecutionManager extends WPSServer {
  val serverContext = new ServerContext( collectionDataCache )
  val logger = LoggerFactory.getLogger(this.getClass)
  val kernelManager = new KernelMgr()
  private val counter = new Counter
  val nprocs: Int = CDASPartitioner.nProcessors

  def getOperationInputs( context: CDASExecutionContext ): List[OperationInput] = {
    for (uid <- context.operation.inputs) yield {
      context.request.getInputSpec(uid) match {
        case Some(inputSpec) =>
          logger.info("getInputSpec: %s -> %s ".format(uid, inputSpec.longname))
          context.server.getOperationInput(inputSpec)
        case None => collectionDataCache.getExistingRDDResult(uid) match {
          case Some(tRDDFut) =>
            val rv = Await result(tRDDFut, Duration.Inf)
            logger.info("getExistingResult: %s -> %s ".format(uid, rv.elements.values.head.metadata.mkString(",")))
            rv
          case None => throw new Exception("Unrecognized input id: " + uid)
        }
      }
    }
  }

  def describeWPSProcess( process: String ): xml.Elem = DescribeProcess( process )

  def getProcesses: Map[String,WPSProcess] = kernelManager.getKernelMap

  def getKernelModule( moduleName: String  ): KernelModule = {
    kernelManager.getModule( moduleName.toLowerCase ) match {
      case Some(kmod) => kmod
      case None => throw new Exception("Unrecognized Kernel Module %s, modules = %s ".format( moduleName, kernelManager.getModuleNames.mkString("[ ",", "," ]") ) )
    }
  }
  def getResourcePath( resource: String ): Option[String] = Option(getClass.getResource(resource)).map( _.getPath )

  def getKernel( moduleName: String, operation: String  ): Kernel = {
    val kmod = getKernelModule( moduleName )
    kmod.getKernel( operation  ) match {
      case Some(kernel) => kernel
      case None => throw new Exception( s"Unrecognized Kernel %s in Module %s, kernels = %s ".format( operation, moduleName, kmod.getKernelNames.mkString("[ ",", "," ]")) )
    }
  }
  def getKernel( kernelName: String  ): Kernel = {
    val toks = kernelName.split('.')
    getKernel( toks.dropRight(1).mkString("."), toks.last )
  }

  def fatal(err: Throwable): String = {
    logger.error( "\nError Executing Kernel: %s\n".format(err.getMessage) )
    val sw = new StringWriter
    err.printStackTrace(new PrintWriter(sw))
    logger.error( sw.toString )
    err.getMessage
  }

  def createTargetGrid( request: TaskRequest ): TargetGrid = {
    request.targetGridSpec.get("id") match {
      case Some(varId) => request.variableMap.get(varId) match {
        case Some(dataContainer: DataContainer) => serverContext.createTargetGrid( dataContainer, request.getDomain(dataContainer.getSource) )
        case None => throw new Exception( "Unrecognized variable id in Grid spec: " + varId )
      }
      case None => throw new Exception("Target grid specification method has not yet been implemented: " + request.targetGridSpec.toString)
    }
  }

  def loadInputData( request: TaskRequest, targetGrid: TargetGrid, run_args: Map[String,String] ): RequestContext = {
    val t0 = System.nanoTime
    val sourceContainers = request.variableMap.values.filter(_.isSource)
    val t1 = System.nanoTime
    val sources = for (data_container: DataContainer <- request.variableMap.values; if data_container.isSource; domainOpt = request.getDomain(data_container.getSource) )
      yield serverContext.createInputSpec(data_container, domainOpt, targetGrid )
    val t2 = System.nanoTime
    val sourceMap: Map[String,Option[DataFragmentSpec]] = Map(sources.toSeq:_*)
    val rv = new RequestContext (request.domainMap, sourceMap, targetGrid, run_args)
    val t3 = System.nanoTime
    logger.info( " LoadInputDataT: %.4f %.4f %.4f, MAXINT: %.2f G".format( (t1-t0)/1.0E9, (t2-t1)/1.0E9, (t3-t2)/1.0E9, Int.MaxValue/1.0E9 ) )
    rv
  }

  def cacheInputData( request: TaskRequest, targetGrid: TargetGrid, run_args: Map[String,String] ):  Iterable[ Option[( DataFragmentKey, Future[PartitionedFragment] )] ] = {
    val sourceContainers = request.variableMap.values.filter(_.isSource)
    for (data_container: DataContainer <- request.variableMap.values; if data_container.isSource; domainOpt = request.getDomain(data_container.getSource) )
      yield serverContext.cacheInputData(data_container, domainOpt, targetGrid )
  }

  def searchForValue(metadata: Map[String, nc2.Attribute], keys: List[String], default_val: String): String = {
    keys.length match {
      case 0 => default_val
      case x => metadata.get(keys.head) match {
        case Some(valueAttr) => valueAttr.getStringValue()
        case None => searchForValue(metadata, keys.tail, default_val)
      }
    }
  }

  def saveResultToFile( resultId: String, maskedTensor: CDFloatArray, request: RequestContext, server: ServerContext, varMetadata: Map[String,nc2.Attribute], dsetMetadata: List[nc2.Attribute] ): Option[String] = {
    val optInputSpec: Option[DataFragmentSpec] = request.getInputSpec()
    val targetGrid = request.targetGrid
    request.getDataset(server) map { dataset =>
      val varname = searchForValue(varMetadata, List("varname", "fullname", "standard_name", "original_name", "long_name"), "Nd4jMaskedTensor")
      val resultFile = Kernel.getResultFile( resultId, true )
      val writer: nc2.NetcdfFileWriter = nc2.NetcdfFileWriter.createNew(nc2.NetcdfFileWriter.Version.netcdf4, resultFile.getAbsolutePath)
      assert(targetGrid.grid.getRank == maskedTensor.getRank, "Axes not the same length as data shape in saveResult")
      val coordAxes = dataset.getCoordinateAxes
      val dims: IndexedSeq[nc2.Dimension] = targetGrid.grid.axes.indices.map(idim => writer.addDimension(null, targetGrid.grid.getAxisSpec(idim).getAxisName, maskedTensor.getShape(idim)))
      val dimsMap: Map[String, nc2.Dimension] = Map(dims.map(dim => (dim.getFullName -> dim)): _*)
      val newCoordVars: List[(nc2.Variable, ma2.Array)] = (for (coordAxis <- coordAxes) yield optInputSpec flatMap { inputSpec => inputSpec.getRange(coordAxis.getFullName) match {
        case Some(range) =>
          val coordVar: nc2.Variable = writer.addVariable(null, coordAxis.getFullName, coordAxis.getDataType, coordAxis.getFullName)
          for (attr <- coordAxis.getAttributes) writer.addVariableAttribute(coordVar, attr)
          val newRange = dimsMap.get(coordAxis.getFullName) match {
            case None => range;
            case Some(dim) => if (dim.getLength < range.length) new ma2.Range(dim.getLength) else range
          }
          Some(coordVar, coordAxis.read(List(newRange)))
        case None => None
      } }).flatten
      logger.info("Writing result %s to file '%s', varname=%s, dims=(%s), shape=[%s], coords = [%s]".format(
        resultId, resultFile.getAbsolutePath, varname, dims.map(_.toString).mkString(","), maskedTensor.getShape.mkString(","),
        newCoordVars.map { case (cvar, data) => "%s: (%s)".format(cvar.getFullName, data.getShape.mkString(",")) }.mkString(",")))
      val variable: nc2.Variable = writer.addVariable(null, varname, ma2.DataType.FLOAT, dims.toList)
      varMetadata.values.foreach(attr => variable.addAttribute(attr))
      variable.addAttribute(new nc2.Attribute("missing_value", maskedTensor.getInvalid))
      dsetMetadata.foreach(attr => writer.addGroupAttribute(null, attr))
      try {
        writer.create()
        for (newCoordVar <- newCoordVars) {
          newCoordVar match {
            case (coordVar, coordData) =>
              logger.info("Writing cvar %s: shape = [%s]".format(coordVar.getFullName, coordData.getShape.mkString(",")))
              writer.write(coordVar, coordData)
          }
        }
        writer.write(variable, maskedTensor)
        //          for( dim <- dims ) {
        //            val dimvar: nc2.Variable = writer.addVariable(null, dim.getFullName, ma2.DataType.FLOAT, List(dim) )
        //            writer.write( dimvar, dimdata )
        //          }
        writer.close()
        resultFile.getAbsolutePath
      } catch {
        case e: IOException => logger.error("ERROR creating file %s%n%s".format(resultFile.getAbsolutePath, e.getMessage()));
          return None
      }
    }
  }
  def aggCollection( dsource: DataSource ): xml.Elem = {
    val col = dsource.collection
    logger.info( "Creating collection '" + col.id + "' path: " + col.dataPath )
    val url = if ( col.dataPath.startsWith("http:") ) {
      col.dataPath
    } else {
      col.createNCML()
      col.ncmlFile.toString
    }
    _aggCollection( NetcdfDataset.openDataset(url), col )
  }

  def aggCollection( colId: String, path: File ): xml.Elem = {
    val col = Collection( colId, path.getAbsolutePath )
    logger.info("Creating collection '" + col.id + "' using path: " + col.dataPath)
    col.createNCML()
    val dataset = NetcdfDataset.openDataset(col.ncmlFile.toString)
    _aggCollection( dataset, col )
  }

  private def _aggCollection( dataset: NetcdfDataset, col: Collection ): xml.Elem = {
    val vars = dataset.getVariables.filter(!_.isCoordinateVariable).map(v => Collections.getVariableString(v) ).toList
    val title: String = Collections.findAttribute( dataset, List( "Title", "LongName" ) )
    val newCollection = new Collection( col.ctype, col.id, col.dataPath, col.fileFilter, col.scope, title, vars)
    Collections.updateCollection(newCollection)
    newCollection.toXml
  }

  def executeUtilityRequest(util_id: String, request: TaskRequest, run_args: Map[String, String]): WPSMergedEventReport = util_id match {
    case "magg" =>
      val collectionNodes =  request.variableMap.values.flatMap( ds => {
        val pcol = ds.getSource.collection
        val base_dir = new File(pcol.dataPath)
        val base_id = pcol.id
        val col_dirs: Array[File] = base_dir.listFiles
        for( col_path <- col_dirs; if col_path.isDirectory; col_id = base_id + "/" + col_path.getName ) yield {
          aggCollection( col_id, col_path )
        }
      })
      new WPSMergedEventReport( collectionNodes.map( cnode => new UtilityExecutionResult( "aggregate", cnode )).toList )
    case "agg" =>
      val collectionNodes =  request.variableMap.values.map( ds => aggCollection( ds.getSource ) )
      new WPSMergedEventReport( collectionNodes.map( cnode => new UtilityExecutionResult( "aggregate", cnode )).toList )
    case "clearCache" =>
      val fragIds = FragmentPersistence.clearCache
      new WPSMergedEventReport( List( new UtilityExecutionResult( "clearCache", <deleted fragments={fragIds.mkString(",")}/> ) ) )
    case "cache" =>
      val cached_data: Iterable[(DataFragmentKey,Future[PartitionedFragment])] = cacheInputData(request, createTargetGrid(request), run_args).flatten
      FragmentPersistence.close()
      new WPSMergedEventReport( cached_data.map( cache_result => new UtilityExecutionResult( cache_result._1.toStrRep, <cache/> ) ).toList )
    case "dcol" =>
      val colIds = request.variableMap.values.map( _.getSource.collection.id )
      val deletedCollections = Collections.removeCollections( colIds.toArray )
      new WPSMergedEventReport(List(new UtilityExecutionResult("dcol", <deleted collections={deletedCollections.mkString(",")}/> )))
    case "dfrag" =>
      val fragIds: Iterable[String] = request.variableMap.values.map( ds => Array( ds.getSource.name, ds.getSource.collection.id, ds.getSource.domain ).mkString("|") )
      logger.info( "Deleting frags: " + fragIds.mkString(", ") + "; Current Frags = " + FragmentPersistence.getFragmentIdList.mkString(", ") )
      FragmentPersistence.delete( fragIds )
      new WPSMergedEventReport(List(new UtilityExecutionResult("dfrag", <deleted fragments={fragIds.mkString(",")}/> )))
    case "dres" =>
      val resIds: Iterable[String] = request.variableMap.values.map( ds => ds.uid )
      logger.info( "Deleting results: " + resIds.mkString(", ") + "; Current Results = " + collectionDataCache.getResultIdList.mkString(", ") )
      resIds.foreach( resId => collectionDataCache.deleteResult( resId ) )
      new WPSMergedEventReport(List(new UtilityExecutionResult("dres", <deleted results={resIds.mkString(",")}/> )))
    case x if x.startsWith("gres") =>
      val resId: String = request.variableMap.values.head.uid
      collectionDataCache.getExistingRDDResult( resId ) match {
        case None => new WPSMergedEventReport( List( new WPSExceptionReport( new Exception("Unrecognized resId: " + resId + ", existing resIds: " + collectionDataCache.getResultIdList.mkString(", ") )) ) )
        case Some( fut_result ) =>
          if (fut_result.isCompleted) {
            val result = Await.result( fut_result, Duration.Inf )
            x.split(':')(1) match {
              case "xml" =>
                new WPSMergedEventReport(List(new UtilityExecutionResult(resId,result.toXml(resId))))
              case "netcdf" =>
                saveResultToFile(resId, result.dataFrag.data, result.request, serverContext, result.metadata, List.empty[nc2.Attribute]) match {
                  case Some(resultFilePath) => new WPSMergedEventReport(List(new UtilityExecutionResult(resId, <file> {resultFilePath} </file>)))
                  case None => new WPSMergedEventReport(List(new UtilityExecutionResult(resId, <error> {"Error writing resultFile"} </error>)))
                }
            }
          } else { new WPSMergedEventReport(List(new UtilityExecutionResult(resId, <error> {"Result not yet ready"} </error>))) }
      }
  }

  def futureExecute( request: TaskRequest, run_args: Map[String,String] ): Future[WPSResponse] = Future {
    logger.info("Executing task request " + request.name )
    val targetGrid: TargetGrid = createTargetGrid(request)
    val requestContext = loadInputData(request, targetGrid, run_args)
    executeWorkflows(request, requestContext)
  }

  def getRequestContext( request: TaskRequest, run_args: Map[String,String] ): RequestContext = loadInputData( request, createTargetGrid( request ), run_args )

  def blockingExecute( request: TaskRequest, run_args: Map[String,String] ): WPSResponse =  {
    logger.info("Blocking Execute { runargs: " + run_args.toString + ",  request: " + request.toString + " }")
    runtime.printMemoryUsage(logger)
    val t0 = System.nanoTime
    try {
      val req_ids = request.name.split('.')
      req_ids(0) match {
        case "util" =>
          logger.info("Executing utility request " + req_ids(1) )
          executeUtilityRequest( req_ids(1), request, run_args )
        case _ =>
          logger.info("Executing task request " + request.name )
          val targetGrid: TargetGrid = createTargetGrid (request)
          val t1 = System.nanoTime
          val requestContext = loadInputData (request, targetGrid, run_args)
          val t2 = System.nanoTime
          val rv = executeWorkflows (request, requestContext)
          val t3 = System.nanoTime
          logger.info ("Execute Completed: CreateTargetGrid> %.4f, LoadVariablesT> %.4f, ExecuteWorkflowT> %.4f, totalT> %.4f ".format ((t1 - t0) / 1.0E9, (t2 - t1) / 1.0E9, (t3 - t2) / 1.0E9, (t3 - t0) / 1.0E9) )
          rv
      }
    } catch {
      case err: Exception => new WPSExceptionReport(err)
    }
  }

//  def futureExecute( request: TaskRequest, run_args: Map[String,String] ): Future[xml.Elem] = Future {
//    try {
//      val sourceContainers = request.variableMap.values.filter(_.isSource)
//      val inputFutures: Iterable[Future[DataFragmentSpec]] = for (data_container: DataContainer <- request.variableMap.values; if data_container.isSource) yield {
//        serverContext.dataLoader.loadVariableDataFuture(data_container, request.getDomain(data_container.getSource))
//      }
//      inputFutures.flatMap( inputFuture => for( input <- inputFuture ) yield executeWorkflows(request, run_args).toXml )
//    } catch {
//      case err: Exception => fatal(err)
//    }
//  }

  def getResultFilePath( resultId: String ): Option[String] = {
    import java.io.File
    val resultFile = Kernel.getResultFile( resultId )
    if(resultFile.exists) Some(resultFile.getAbsolutePath) else None
  }

  def asyncExecute( request: TaskRequest, run_args: Map[String,String] ): WPSReferenceExecuteResponse = {
    logger.info("Execute { runargs: " + run_args.toString + ",  request: " + request.toString + " }")
    runtime.printMemoryUsage(logger)
    val jobId = collectionDataCache.addJob( request.getJobRec(run_args) )
    val async = run_args.getOrElse("async", "false").toBoolean
    val req_ids = request.name.split('.')
    req_ids(0) match {
      case "util" =>
        val util_result = executeUtilityRequest(req_ids(1), request, Map("jobId" -> jobId) ++ run_args )
        Future(util_result)
      case _ =>
        val futureResult = this.futureExecute(request, Map("jobId" -> jobId) ++ run_args)
        futureResult onSuccess { case results: WPSMergedEventReport =>
          println("Process Completed: " + results.toString)
          processAsyncResult(jobId, results)
        }
        futureResult onFailure { case e: Throwable => fatal(e); collectionDataCache.removeJob(jobId); throw e }
    }
    new AsyncExecutionResult( request.getProcess, Some(jobId) )
  }

  def processAsyncResult( jobId: String, results: WPSMergedEventReport ) = {
    collectionDataCache.removeJob( jobId )
  }

//  def execute( request: TaskRequest, runargs: Map[String,String] ): xml.Elem = {
//    val async = runargs.getOrElse("async","false").toBoolean
//    if(async) executeAsync( request, runargs ) else  blockingExecute( request, runargs )
//  }

  def getWPSCapabilities( identifier: String ): xml.Elem = identifier match {
    case x if x.startsWith("proc") => GetCapabilities
    case x if x.startsWith("frag") => FragmentPersistence.getFragmentListXml
    case x if x.startsWith("res") => collectionDataCache.getResultListXml // collectionDataCache
    case x if x.startsWith("job") => collectionDataCache.getJobListXml
    case x if x.startsWith("coll") => {
      val itToks = x.split(':')
      if( itToks.length < 2 ) Collections.toXml
      else <collection id={itToks(0)}> { Collections.getCollectionMetadata( itToks(1) ).map( attr => attrToXml( attr ) ) } </collection>
    }
    case x if x.startsWith("op") => kernelManager.getModulesXml
    case x if x.startsWith("var") => {
      println( "getCapabilities->identifier: " + identifier )
      val itToks = x.split(':')
      if( itToks.length < 2 )  <error message="Unspecified collection and variables" />
      else                     Collections.getVariableListXml( itToks(1).split(',') )
    }
    case _ => kernelManager.toXml
  }

  def attrToXml( attr: nc2.Attribute ): xml.Elem = {
    val sb = new StringBuffer()
    val svals = for( index <- (0 until attr.getLength) )  {
      if( index > 0 ) sb.append(",")
      if (attr.isString) sb.append(attr.getStringValue(index)) else sb.append(attr.getNumericValue(index))
    }
    <attr id={attr.getFullName.split("--").last}> { sb.toString } </attr>
  }

  def executeWorkflows( request: TaskRequest, requestCx: RequestContext ): WPSResponse = {
    val results = request.workflow.head.moduleName match {
      case "util" =>  new WPSMergedEventReport( request.workflow.map( utilityExecution( _, requestCx )))
      case x =>
        logger.info( "---------->>> Execute Workflows: " + request.workflow.mkString(",") )
        new MergedWPSExecuteResponse( request.workflow.map( operationExecution( _, requestCx )))
    }
    FragmentPersistence.close()
//    logger.info( "---------->>> Execute Workflows: Created XML response: " + results.toXml.toString )
    results
  }

  def executeUtility( context: CDASExecutionContext ): UtilityExecutionResult = {
    val report: xml.Elem =  <ReportText> {"Completed executing utility " + context.operation.name.toLowerCase } </ReportText>
    new UtilityExecutionResult( context.operation.name.toLowerCase + "~u0", report )
  }

  def executeProcess( context: CDASExecutionContext, kernel: Kernel  ): WPSExecuteResponse

  def operationExecution( operationCx: OperationContext, requestCx: RequestContext ): WPSExecuteResponse = {
    logger.info( " ***** Operation Execution: opName=%s >> Operation = %s ".format( operationCx.name, operationCx.toString ) )
    executeProcess( new CDASExecutionContext( operationCx, requestCx, serverContext ), getKernel( operationCx.name.toLowerCase ) )
  }
  def utilityExecution( operationCx: OperationContext, requestCx: RequestContext ): UtilityExecutionResult = {
    logger.info( " ***** Utility Execution: utilName=%s, >> Operation = %s ".format( operationCx.name, operationCx.toString ) )
    executeUtility( new CDASExecutionContext( operationCx, requestCx, serverContext ) )
  }
}

//object SampleTaskRequests {
//
//  def createTestData() = {
//    var axes = Array("time","lev","lat","lon")
//    var shape = Array(1,1,180,360)
//    val maskedTensor: CDFloatArray = CDFloatArray( shape, Array.fill[Float](180*360)(1f), Float.MaxValue)
//    val varname = "ta"
//    val resultFile = "/tmp/SyntheticTestData.nc"
//    val writer: nc2.NetcdfFileWriter = nc2.NetcdfFileWriter.createNew(nc2.NetcdfFileWriter.Version.netcdf4, resultFile )
//    val dims: IndexedSeq[nc2.Dimension] = shape.indices.map( idim => writer.addDimension(null, axes(idim), maskedTensor.getShape(idim)))
//    val variable: nc2.Variable = writer.addVariable(null, varname, ma2.DataType.FLOAT, dims.toList)
//    variable.addAttribute( new nc2.Attribute( "missing_value", maskedTensor.getInvalid ) )
//    writer.create()
//    writer.write( variable, maskedTensor )
//    writer.close()
//    println( "Writing result to file '%s'".format(resultFile) )
//  }
//
//  def getSpatialAve(collection: String, varname: String, weighting: String, level_index: Int = 0, time_index: Int = 0): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List( Map("name" -> "d0", "lev" -> Map("start" -> level_index, "end" -> level_index, "system" -> "indices"), "time" -> Map("start" -> time_index, "end" -> time_index, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> s"collection:/$collection", "name" -> s"$varname:v0", "domain" -> "d0")),
//      "operation" -> List( Map( "input"->"v0", "axes"->"xy", "weights"->weighting ) ))
//    TaskRequest( "CDSpark.average", dataInputs )
//  }
//
//  def getMaskedSpatialAve(collection: String, varname: String, weighting: String, level_index: Int = 0, time_index: Int = 0): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List( Map("name" -> "d0", "mask" -> "#ocean50m", "lev" -> Map("start" -> level_index, "end" -> level_index, "system" -> "indices"), "time" -> Map("start" -> time_index, "end" -> time_index, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> s"collection:/$collection", "name" -> s"$varname:v0", "domain" -> "d0")),
//      "operation" -> List( Map( "input"->"v0", "axes"->"xy", "weights"->weighting ) ))
//    TaskRequest( "CDSpark.average", dataInputs )
//  }
//
//  def getConstant(collection: String, varname: String, level_index: Int = 0 ): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List( Map("name" -> "d0", "lev" -> Map("start" -> level_index, "end" -> level_index, "system" -> "indices"), "time" -> Map("start" -> 10, "end" -> 10, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> s"collection:/$collection", "name" -> s"$varname:v0", "domain" -> "d0")),
//      "operation" -> List( Map( "input"->"v0") ))
//    TaskRequest( "CDSpark.const", dataInputs )
//  }
//
//  def getAnomalyTest: TaskRequest = {
//    val dataInputs = Map(
//      "domain" ->  List(Map("name" -> "d0", "lat" -> Map("start" -> -7.0854263, "end" -> -7.0854263, "system" -> "values"), "lon" -> Map("start" -> 12.075, "end" -> 12.075, "system" -> "values"), "lev" -> Map("start" -> 1000, "end" -> 1000, "system" -> "values"))),
//      "variable" -> List(Map("uri" -> "collection://merra_1/hourly/aggtest", "name" -> "t:v0", "domain" -> "d0")),  // collection://merra300/hourly/asm_Cp
//      "operation" -> List( Map( "input"->"v0", "axes"->"t" ) ))
//    TaskRequest( "CDSpark.anomaly", dataInputs )
//  }
//}

//abstract class SyncExecutor {
//  val printer = new scala.xml.PrettyPrinter(200, 3)
//
//  def main(args: Array[String]) {
//    val executionManager = getExecutionManager
//    val final_result = getExecutionManager.blockingExecute( getTaskRequest(args), getRunArgs )
//    println(">>>> Final Result: " + printer.format(final_result.toXml))
//  }
//
//  def getTaskRequest(args: Array[String]): TaskRequest
//  def getRunArgs = Map("async" -> "false")
//  def getExecutionManager = CDS2ExecutionManager(Map.empty)
//  def getCollection( id: String ): Collection = Collections.findCollection(id) match { case Some(collection) => collection; case None=> throw new Exception(s"Unknown Collection: $id" ) }
//}
//
//object TimeAveSliceTask extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 10, "end" -> 10, "system" -> "values"), "lon" -> Map("start" -> 10, "end" -> 10, "system" -> "values"), "lev" -> Map("start" -> 8, "end" -> 8, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "hur:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t")))
//    TaskRequest("CDSpark.average", dataInputs)
//  }
//}
//
//object YearlyCycleSliceTask extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 45, "end" -> 45, "system" -> "values"), "lon" -> Map("start" -> 30, "end" -> 30, "system" -> "values"), "lev" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "period" -> 1, "unit" -> "month", "mod" -> 12)))
//    TaskRequest("CDSpark.bin", dataInputs)
//  }
//}
//
////object AveTimeseries extends SyncExecutor {
////  def getTaskRequest(args: Array[String]): TaskRequest = {
////    import nasa.nccs.esgf.process.DomainAxis.Type._
////    val workflows = List[WorkflowContainer](new WorkflowContainer(operations = List( OperationContext("CDSpark.average", List("v0"), Map("axis" -> "t")))))
////    val variableMap = Map[String, DataContainer]("v0" -> new DataContainer(uid = "v0", source = Some(new DataSource(name = "hur", collection = getCollection("merra/mon/atmos"), domain = "d0"))))
////    val domainMap = Map[String, DomainContainer]("d0" -> new DomainContainer(name = "d0", axes = cdsutils.flatlist(DomainAxis(Z, 1, 1), DomainAxis(Y, 100, 100), DomainAxis(X, 100, 100)), None))
////    new TaskRequest("CDSpark.average", variableMap, domainMap, workflows, Map("id" -> "v0"))
////  }
////}
//
//object CreateVTask extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 45, "end" -> 45, "system" -> "values"), "lon" -> Map("start" -> 30, "end" -> 30, "system" -> "values"), "lev" -> Map("start" -> 3, "end" -> 3, "system" -> "indices")),
//        Map("name" -> "d1", "time" -> Map("start" -> "2010-01-16T12:00:00", "end" -> "2010-01-16T12:00:00", "system" -> "values"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t", "name" -> "CDSpark.anomaly"), Map("input" -> "v0", "period" -> 1, "unit" -> "month", "mod" -> 12, "name" -> "CDSpark.timeBin"), Map("input" -> "v0", "domain" -> "d1", "name" -> "CDSpark.subset")))
//    TaskRequest("CDSpark.workflow", dataInputs)
//  }
//}
//
//object YearlyCycleTask extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 45, "end" -> 45, "system" -> "values"), "lon" -> Map("start" -> 30, "end" -> 30, "system" -> "values"), "lev" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "period" -> 1, "unit" -> "month", "mod" -> 12)))
//    TaskRequest("CDSpark.timeBin", dataInputs)
//  }
//}
//
//object SeasonalCycleRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 45, "end" -> 45, "system" -> "values"), "lon" -> Map("start" -> 30, "end" -> 30, "system" -> "values"), "time" -> Map("start" -> 0, "end" -> 36, "system" -> "indices"), "lev" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "period" -> 3, "unit" -> "month", "mod" -> 4, "offset" -> 2)))
//    TaskRequest("CDSpark.timeBin", dataInputs)
//  }
//}
//
//object YearlyMeansRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 45, "end" -> 45, "system" -> "values"), "lon" -> Map("start" -> 30, "end" -> 30, "system" -> "values"), "lev" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "period" -> 12, "unit" -> "month")))
//    TaskRequest("CDSpark.timeBin", dataInputs)
//  }
//}
//
//object SubsetRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 45, "end" -> 45, "system" -> "values"), "lon" -> Map("start" -> 30, "end" -> 30, "system" -> "values"), "lev" -> Map("start" -> 3, "end" -> 3, "system" -> "indices")),
//        Map("name" -> "d1", "time" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "domain" -> "d1")))
//    TaskRequest("CDSpark.subset", dataInputs)
//  }
//}
//
//object TimeSliceAnomaly extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 10, "end" -> 10, "system" -> "values"), "lon" -> Map("start" -> 10, "end" -> 10, "system" -> "values"), "lev" -> Map("start" -> 8, "end" -> 8, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t")))
//    TaskRequest("CDSpark.anomaly", dataInputs)
//  }
//}
//
//object MetadataRequest extends SyncExecutor {
//  val level = 0
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs: Map[String, Seq[Map[String, Any]]] = level match {
//      case 0 => Map()
//      case 1 => Map("variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0")))
//    }
//    TaskRequest("CDSpark.metadata", dataInputs)
//  }
//}
//
//object CacheRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lev" -> Map("start" -> 0, "end" -> 0, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra300/hourly/asm_Cp", "name" -> "t:v0", "domain" -> "d0")))
//    TaskRequest("util.cache", dataInputs)
//  }
//}
//
//object AggregateAndCacheRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lev" -> Map("start" -> 0, "end" -> 0, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra_1/hourly/aggTest3", "path" -> "/Users/tpmaxwel/Dropbox/Tom/Data/MERRA/DAILY/", "name" -> "t", "domain" -> "d0")))
//    TaskRequest("util.cache", dataInputs)
//  }
//}
//
//object AggregateRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map( "variable" -> List(Map("uri" -> "collection://merra_1/hourly/aggTest37", "path" -> "/Users/tpmaxwel/Dropbox/Tom/Data/MERRA/DAILY/" ) ) )
//    TaskRequest("util.agg", dataInputs)
//  }
//}
//
//
//object MultiAggregateRequest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val baseCollectionId = args(0)
//    val baseDirectory = new java.io.File(args(1))
//    assert( baseDirectory.isDirectory, "Base directory is not a directory: " + args(1) )
//    val dataInputs = Map( "variable" -> baseDirectory.listFiles.map( dir => Map("uri" -> Array("collection:",baseCollectionId,dir.getName).mkString("/"), "path" -> dir.toString ) ).toSeq )
//    TaskRequest("util.agg", dataInputs)
//  }
//}
//
//object AggregateAndCacheRequest2 extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lev" -> Map("start" -> 0, "end" -> 0, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra/daily/aggTest", "path" -> "/Users/tpmaxwel/Dropbox/Tom/Data/MERRA/DAILY", "name" -> "t", "domain" -> "d0")))
//    TaskRequest("util.cache", dataInputs)
//  }
//}
//
//object AggregateAndCacheRequest1 extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lev" -> Map("start" -> 0, "end" -> 0, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra2/hourly/M2T1NXLND-2004-04", "path" -> "/att/pubrepo/MERRA/remote/MERRA2/M2T1NXLND.5.12.4/2004/04", "name" -> "SFMC", "domain" -> "d0")))
//    TaskRequest("util.cache", dataInputs)
//  }
//}
//
//object Max extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lev" -> Map("start" -> 20, "end" -> 20, "system" -> "indices"), "time" -> Map("start" -> 0, "end" -> 0, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "xy")))
//    TaskRequest("CDSpark.max", dataInputs)
//  }
//}
//
//object Min extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lev" -> Map("start" -> 20, "end" -> 20, "system" -> "indices"), "time" -> Map("start" -> 0, "end" -> 0, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "xy")))
//    TaskRequest("CDSpark.min", dataInputs)
//  }
//}
//
//object AnomalyTest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> -7.0854263, "end" -> -7.0854263, "system" -> "values"), "lon" -> Map("start" -> 12.075, "end" -> 12.075, "system" -> "values"), "lev" -> Map("start" -> 1000, "end" -> 1000, "system" -> "values"))),
//      "variable" -> List(Map("uri" -> "collection://merra_1/hourly/aggtest", "name" -> "t:v0", "domain" -> "d0")), // collection://merra300/hourly/asm_Cp
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t")))
//    TaskRequest("CDSpark.anomaly", dataInputs)
//  }
//}
//
//object AnomalyTest1 extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 20.0, "end" -> 20.0, "system" -> "values"), "lon" -> Map("start" -> 0.0, "end" -> 0.0, "system" -> "values"))),
//      "variable" -> List(Map("uri" -> "collection://merra2/hourly/m2t1nxlnd-2004-04", "name" -> "SFMC:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t")))
//    TaskRequest("CDSpark.anomaly", dataInputs)
//  }
//}
//object AnomalyTest2 extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d0", "lat" -> Map("start" -> 0.0, "end" -> 0.0, "system" -> "values"), "lon" -> Map("start" -> 0.0, "end" -> 0.0, "system" -> "values"), "level" -> Map("start" -> 10, "end" -> 10, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://merra/daily/aggTest", "name" -> "t:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t")))
//    TaskRequest("CDSpark.anomaly", dataInputs)
//  }
//}
//
//object AnomalyArrayTest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d1", "lat" -> Map("start" -> 3, "end" -> 3, "system" -> "indices")), Map("name" -> "d0", "lat" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"), "lon" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"), "lev" -> Map("start" -> 30, "end" -> 30, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "collection://MERRA/mon/atmos", "name" -> "ta:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t", "name" -> "CDSpark.anomaly"), Map("input" -> "v0", "domain" -> "d1", "name" -> "CDSpark.subset")))
//    TaskRequest("CDSpark.workflow", dataInputs)
//  }
//}
//
//object AnomalyArrayNcMLTest extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val dataInputs = Map(
//      "domain" -> List(Map("name" -> "d1", "lat" -> Map("start" -> 3, "end" -> 3, "system" -> "indices")), Map("name" -> "d0", "lat" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"), "lon" -> Map("start" -> 3, "end" -> 3, "system" -> "indices"), "lev" -> Map("start" -> 30, "end" -> 30, "system" -> "indices"))),
//      "variable" -> List(Map("uri" -> "file://Users/tpmaxwel/data/AConaty/comp-ECMWF/ecmwf.xml", "name" -> "Temperature:v0", "domain" -> "d0")),
//      "operation" -> List(Map("input" -> "v0", "axes" -> "t", "name" -> "CDSpark.anomaly"), Map("input" -> "v0", "domain" -> "d1", "name" -> "CDSpark.subset")))
//    TaskRequest("CDSpark.workflow", dataInputs)
//  }
//}
//
////object AveArray extends SyncExecutor {
////  def getTaskRequest(args: Array[String]): TaskRequest = {
////    import nasa.nccs.esgf.process.DomainAxis.Type._
////
////    val workflows = List[WorkflowContainer](new WorkflowContainer(operations = List( OperationContext("CDSpark.average", List("v0"), Map("axis" -> "xy")))))
////    val variableMap = Map[String, DataContainer]("v0" -> new DataContainer(uid = "v0", source = Some(new DataSource(name = "t", collection = getCollection("merra/daily"), domain = "d0"))))
////    val domainMap = Map[String, DomainContainer]("d0" -> new DomainContainer(name = "d0", axes = cdsutils.flatlist(DomainAxis(Z, 0, 0)), None))
////    new TaskRequest("CDSpark.average", variableMap, domainMap, workflows, Map("id" -> "v0"))
////  }
////}
//
//object SpatialAve1 extends SyncExecutor {
//  def getTaskRequest(args: Array[String]): TaskRequest = SampleTaskRequests.getSpatialAve("/MERRA/mon/atmos", "ta", "cosine")
//}
//
//object cdscan extends App with Loggable {
//  val printer = new scala.xml.PrettyPrinter(200, 3)
//  val executionManager = CDS2ExecutionManager(Map.empty)
//  val final_result = executionManager.blockingExecute( getTaskRequest(args), Map("async" -> "false") )
//  println(">>>> Final Result: " + printer.format(final_result.toXml))
//
//  def getTaskRequest(args: Array[String]): TaskRequest = {
//    val baseCollectionId = args(0)
//    val baseDirectory = new java.io.File(args(1))
//    logger.info( s"Running cdscan with baseCollectionId $baseCollectionId and baseDirectory $baseDirectory")
//    assert( baseDirectory.isDirectory, "Base directory is not a directory: " + args(1) )
//    val dataInputs = Map( "variable" -> baseDirectory.listFiles.filter( f => Collections.hasChildNcFile(f) ).map(
//      dir => Map("uri" -> Array("collection:",baseCollectionId,dir.getName).mkString("/"), "path" -> dir.toString ) ).toSeq )
//    TaskRequest("util.agg", dataInputs)
//  }
//}
//
//
//object IntMaxTest extends App {
//  printf( " MAXINT: %.2f G, MAXLONG: %.2f G".format( Int.MaxValue/1.0E9, Long.MaxValue/1.0E9 ) )
//}


//  TaskRequest: name= CWT.average, variableMap= Map(v0 -> DataContainer { id = hur:v0, dset = merra/mon/atmos, domain = d0 }, ivar#1 -> OperationContext { id = ~ivar#1,  name = , result = ivar#1, inputs = List(v0), optargs = Map(axis -> xy) }), domainMap= Map(d0 -> DomainContainer { id = d0, axes = List(DomainAxis { id = lev, start = 0, end = 1, system = "indices", bounds =  }) })

