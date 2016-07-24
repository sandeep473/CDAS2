package nasa.nccs.cdapi.kernels

import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.cdapi.cdm._
import nasa.nccs.esgf.process._
import org.slf4j.LoggerFactory
import java.io.{File, IOException, PrintWriter, StringWriter}

import nasa.nccs.caching.collectionDataCache
import nasa.nccs.utilities.Loggable
import ucar.nc2.Attribute
import ucar.{ma2, nc2}

import scala.util.Random
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable

object Port {
  def apply( name: String, cardinality: String, description: String="", datatype: String="", identifier: String="" ) = {
    new Port(  name,  cardinality,  description, datatype,  identifier )
  }
}

class Port( val name: String, val cardinality: String, val description: String, val datatype: String, val identifier: String )  {

  def toXml = {
    <port name={name} cardinality={cardinality}>
      { if ( description.nonEmpty ) <description> {description} </description> }
      { if ( datatype.nonEmpty ) <datatype> {datatype} </datatype> }
      { if ( identifier.nonEmpty ) <identifier> {identifier} </identifier> }
    </port>
  }
}

trait ExecutionResult {
  val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)
  def toXml: xml.Elem
}

class UtilityExecutionResult( val id: String, val response: xml.Elem )  extends ExecutionResult {
  def toXml = <result id={id}> {response} </result>
}
class BlockingExecutionResult( val id: String, val intputSpecs: List[DataFragmentSpec], val gridSpec: TargetGrid, val result_tensor: CDFloatArray ) extends ExecutionResult {
  def toXml = {
    val idToks = id.split('~')
    logger.info( "BlockingExecutionResult-> result_tensor: \n" + result_tensor.toString )
    <result id={idToks(1)} op={idToks(0)}> { intputSpecs.map( _.toXml ) } { gridSpec.toXml } <data undefined={result_tensor.getInvalid.toString}> {result_tensor.mkDataString(",")}  </data>  </result>
  }
}

class ErrorExecutionResult( val err: Throwable ) extends ExecutionResult {

  def fatal(): String = {
    logger.error( "\nError Executing Kernel: %s\n".format(err.getMessage) )
    val sw = new StringWriter
    err.printStackTrace(new PrintWriter(sw))
    logger.error( sw.toString )
    err.getMessage
  }

  def toXml = <error> {fatal()} </error>

}

class XmlExecutionResult( val id: String,  val responseXml: xml.Node ) extends ExecutionResult {
  def toXml = {
    val idToks = id.split('~')
    <result id={idToks(1)} op={idToks(0)}> { responseXml }  </result>
  }
}

// cdsutils.cdata(

class AsyncExecutionResult( val results: List[String] )  extends ExecutionResult  {
  def this( resultOpt: Option[String]  ) { this( resultOpt.toList ) }
  def toXml = <result> {  results.mkString(",")  } </result>
}

class ExecutionResults( val results: List[ExecutionResult] ) {
  def this(err: Throwable ) = this( List( new ErrorExecutionResult( err ) ) )
  def toXml = <results> { results.map(_.toXml) } </results>
}

case class ResultManifest( val name: String, val dataset: String, val description: String, val units: String )

//class SingleInputExecutionResult( val operation: String, manifest: ResultManifest, result_data: Array[Float] ) extends ExecutionResult(result_data) {
//  val name = manifest.name
//  val description = manifest.description
//  val units = manifest.units
//  val dataset =  manifest.dataset
//
//  override def toXml =
//    <operation id={ operation }>
//      <input name={ name } dataset={ dataset } units={ units } description={ description }  />
//      { super.toXml }
//    </operation>
//}


class AxisIndices( private val axisIds: Set[Int] = Set.empty ) {
  def getAxes: Seq[Int] = axisIds.toSeq
}
// , val binArrayOpt: Option[BinnedArrayFactory], val dataManager: DataManager, val serverConfiguration: Map[String, String], val args: Map[String, String],

//class ExecutionContext( operation: OperationContext, val domains: Map[String,DomainContainer], val dataManager: DataManager ) {
//
//
//  def id: String = operation.identifier
//  def getConfiguration( cfg_type: String ): Map[String,String] = operation.getConfiguration( cfg_type )
//  def binArrayOpt = dataManager.getBinnedArrayFactory( operation )
//  def inputs: List[KernelDataInput] = for( uid <- operation.inputs ) yield new KernelDataInput( dataManager.getVariableData(uid), dataManager.getAxisIndices(uid) )
//
//
//  //  def getSubset( var_uid: String, domain_id: String ) = {
////    dataManager.getSubset( var_uid, getDomain(domain_id) )
////  }
//  def getDataSources: Map[String,OperationInputSpec] = dataManager.getDataSources
//
//  def async: Boolean = getConfiguration("run").getOrElse("async", "false").toBoolean
//
//  def getFragmentSpec( uid: String ): DataFragmentSpec = dataManager.getOperationInputSpec(uid) match {
//    case None => throw new Exception( "Missing Data Fragment Spec: " + uid )
//    case Some( inputSpec ) => inputSpec.data
//  }
//
//  def getAxisIndices( uid: String ): AxisIndices = dataManager.getAxisIndices( uid )
//}

object Kernel {
  def getResultFile( serverConfiguration: Map[String,String], resultId: String, deleteExisting: Boolean = false ): File = {
    val resultsDirPath = serverConfiguration.getOrElse("wps.results.dir", System.getProperty("user.home") + "/.wps/results")
    val resultsDir = new File(resultsDirPath); resultsDir.mkdirs()
    val resultFile = new File( resultsDirPath + s"/$resultId.nc" )
    if( deleteExisting && resultFile.exists ) resultFile.delete
    resultFile
  }
}

abstract class Kernel {
  val logger = LoggerFactory.getLogger(this.getClass)
  val identifiers = this.getClass.getName.split('$').flatMap( _.split('.') )
  def operation: String = identifiers.last.toLowerCase
  def module = identifiers.dropRight(1).mkString(".")
  def id   = identifiers.mkString(".")
  def name = identifiers.takeRight(2).mkString(".")

  val inputs: List[Port]
  val outputs: List[Port]
  val description: String = ""
  val keywords: List[String] = List()
  val identifier: String = ""
  val metadata: String = ""

  def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext   ): ExecutionResult = {
    throw new Exception( " This kernel does not have a request-execute method defined: " + id )
  }
  def execute( operationCx: OperationContext, serverCx: ServerContext   ): ExecutionResult = {
    throw new Exception( " This kernel cannot be executed without a request context: " + id )
  }
  def toXmlHeader =  <kernel module={module} name={name}> { if (description.nonEmpty) <description> {description} </description> } </kernel>

  def toXml = {
    <kernel module={module} name={name}>
      {if (description.nonEmpty) <description>{description}</description> }
      {if (keywords.nonEmpty) <keywords> {keywords.mkString(",")} </keywords> }
      {if (identifier.nonEmpty) <identifier> {identifier} </identifier> }
      {if (metadata.nonEmpty) <metadata> {metadata} </metadata> }
    </kernel>
  }

  def getStringArg( args: Map[String, String], argname: String, defaultVal: Option[String] = None ): String = {
    args.get( argname ) match {
      case Some( sval ) => sval
      case None => defaultVal match { case None => throw new Exception( s"Parameter $argname (int) is reqired for operation " + this.id ); case Some(sval) => sval }
    }
  }

  def getIntArg( args: Map[String, String], argname: String, defaultVal: Option[Int] = None ): Int = {
    args.get( argname ) match {
      case Some( sval ) => try { sval.toInt } catch { case err: NumberFormatException => throw new Exception( s"Parameter $argname must ba an integer: $sval" ) }
      case None => defaultVal match { case None => throw new Exception( s"Parameter $argname (int) is reqired for operation " + this.id ); case Some(ival) => ival }
    }
  }

  def getFloatArg( args: Map[String, String], argname: String, defaultVal: Option[Float] = None ): Float = {
    args.get( argname ) match {
      case Some( sval ) => try { sval.toFloat } catch { case err: NumberFormatException => throw new Exception( s"Parameter $argname must ba a float: $sval" ) }
      case None => defaultVal match { case None => throw new Exception( s"Parameter $argname (float) is reqired for operation " + this.id ); case Some(fval) => fval }
    }
  }

  def inputVars( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext, dataAccessMode: DataAccessMode = DataAccessMode.Read ): List[KernelDataInput] = serverCx.inputs(operationCx.inputs.map( requestCx.getInputSpec ), dataAccessMode )

  def cacheResult( maskedTensor: CDFloatArray, operation: OperationContext, request: RequestContext, server: ServerContext, resultGrid: TargetGrid, varMetadata: Map[String,nc2.Attribute], dsetMetadata: List[nc2.Attribute] ): Option[String] = {
    try {
      val result: TransientFragment = new TransientFragment(maskedTensor, request, varMetadata, dsetMetadata)
      collectionDataCache.putResult(operation.rid, result)
      Some(operation.rid)
    } catch {
      case ex: Exception => logger.error( "Can't cache result: " + ex.getMessage ); None
    }
  }


  //  def binArrayOpt = serverContext.getBinnedArrayFactory( operation )

  //
  //
  //  //  def getSubset( var_uid: String, domain_id: String ) = {
  ////    serverContext.getSubset( var_uid, getDomain(domain_id) )
  ////  }
  //  def getDataSources: Map[String,OperationInputSpec] = serverContext.getDataSources
  //
  //
  //
  //  def getFragmentSpec( uid: String ): DataFragmentSpec = serverContext.getOperationInputSpec(uid) match {
  //    case None => throw new Exception( "Missing Data Fragment Spec: " + uid )
  //    case Some( inputSpec ) => inputSpec.data
  //  }
  //
  //  def getAxisIndices( uid: String ): AxisIndices = serverContext.getAxisIndices( uid )

}

class KernelModule {
  val logger = LoggerFactory.getLogger(this.getClass)
  val identifiers = this.getClass.getName.split('$').flatMap( _.split('.') )
  logger.info( "---> new KernelModule: " + identifiers.mkString(", ") )
  def package_path = identifiers.dropRight(1).mkString(".")
  def name: String = identifiers.last
  val version = ""
  val organization = ""
  val author = ""
  val contact = ""
  val kernelMap: Map[String,Kernel] = Map(getKernelObjects.map( kernel => kernel.operation.toLowerCase -> kernel ): _*)

  def getKernelClasses = getInnerClasses.filter( _.getSuperclass.getName.split('.').last == "Kernel"  )
  def getInnerClasses = this.getClass.getClasses.toList
  def getKernelObjects: List[Kernel] = getKernelClasses.map( _.getDeclaredConstructors()(0).newInstance(this).asInstanceOf[Kernel] )

  def getKernel( kernelName: String ): Option[Kernel] = kernelMap.get( kernelName.toLowerCase )
  def getKernelNames: List[String] = kernelMap.keys.toList

  def toXml = {
    <kernelModule name={name}>
      { if ( version.nonEmpty ) <version> {version} </version> }
      { if ( organization.nonEmpty ) <organization> {organization} </organization> }
      { if ( author.nonEmpty ) <author> {author} </author> }
      { if ( contact.nonEmpty ) <contact> {contact} </contact> }
      <kernels> { kernelMap.values.map( _.toXmlHeader ) } </kernels>
    </kernelModule>
  }
}

class TransientFragment( val data: CDFloatArray, val request: RequestContext, val varMetadata: Map[String,nc2.Attribute], val dsetMetadata: List[nc2.Attribute] ) extends Loggable {
  def toXml(id: String): xml.Elem = {
    val units = varMetadata.get("units") match { case Some(attr) => attr.getStringValue; case None => "" }
    val long_name = varMetadata.getOrElse("long_name",varMetadata.getOrElse("fullname",varMetadata.getOrElse("varname", new Attribute("varname","UNDEF")))).getStringValue
    val description = varMetadata.get("description") match { case Some(attr) => attr.getStringValue; case None => "" }
    val axes = varMetadata.get("axes") match { case Some(attr) => attr.getStringValue; case None => "" }
    <result id={id} missing_value={data.getInvalid.toString} shape={data.getShape.mkString("(",",",")")} units={units} long_name={long_name} description={description} axes={axes}> { data.mkBoundedDataString( ", ", 1100 ) } </result> //
  }

}

