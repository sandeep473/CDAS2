package nasa.nccs.wps

import nasa.nccs.cdapi.data.RDDPartition
import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.cds2.utilities.appParameters
import nasa.nccs.esgf.process.{DataFragmentSpec, TargetGrid}
import nasa.nccs.utilities.Loggable

object WPSExecuteResponse {
  def merge( responses: List[WPSExecuteResponse] ): WPSExecuteResponse = new MergedWPSExecuteResponse( responses )
}

trait WPSResponse {
  def toXml: xml.Elem
}

abstract class WPSExecuteResponse( val processes: List[WPSProcess] ) extends WPSResponse {
  val serviceInstance = appParameters("wps.server.result.href","")
  val statusLocation =  appParameters("wps.server.status.href","")
  def this( process: WPSProcess ) = this( List(process) )

  def toXml: xml.Elem =
    <wps:ExecuteResponse xmlns:wps="http://www.opengis.net/wps/1.0.0" xmlns:ows="http://www.opengis.net/ows/1.1" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.opengis.net/wps/1.0.0 ../wpsExecute_response.xsd" service="WPS" version="1.0.0" xml:lang="en-CA" serviceInstance={serviceInstance} statusLocation={statusLocation}>
      { processes.map( _.ExecuteHeader ) }
      <wps:ProcessOutputs> { getOutputs } </wps:ProcessOutputs>
    </wps:ExecuteResponse>

  def getOutputs: List[xml.Elem] = processes.flatMap( p => p.outputs.map( output => <wps:Output> {output.getHeader} { getProcessOutputs( p.identifier, output.identifier) } </wps:Output> ) )
  def getProcessOutputs( process_id: String, output_id: String ): Iterable[xml.Elem]
  def getData( id: String, array: CDFloatArray, units: String ): xml.Elem = <wps:Data id={id}> <wps:LiteralData uom={units} shape={array.getShape.mkString(",")}>{ array.mkDataString(",") }</wps:LiteralData> </wps:Data>
}

abstract class WPSReferenceExecuteResponse( val process: WPSProcess, val optResultId: Option[String] )  extends WPSExecuteResponse(process)  {

  val result_href = serviceInstance + "/" +  optResultId.getOrElse("")
  def getReference( process_id: String, output_id: String ): xml.Elem = <wps:Reference href={result_href} mimeType="text/xml"/>
}

class MergedWPSExecuteResponse( responses: List[WPSExecuteResponse] ) extends WPSExecuteResponse( responses.flatMap(_.processes) ) {
  val process_ids: List[String] = responses.flatMap( response => response.processes.map( process => process.identifier ) )
  assert( process_ids.distinct.size == process_ids.size, "Error, non unique process IDs in process list: " + processes.mkString(", ") )
  val responseMap: Map[String,WPSExecuteResponse] = Map( responses.flatMap( response => response.processes.map( process => ( process.identifier -> response ) ) ): _* )
  def getProcessOutputs( process_id: String, response_id: String ): Iterable[xml.Elem] = responseMap.get( process_id ) match {
    case Some( response ) => response.getProcessOutputs(process_id, response_id);
    case None => throw new Exception( "Unrecognized process id: " + process_id )
  }
}

class RDDExecutionResult( process: WPSProcess, id: String, val result: RDDPartition,  optResultId: Option[String] = None ) extends WPSReferenceExecuteResponse( process, optResultId )  with Loggable {
  def getProcessOutputs( process_id: String, output_id: String  ): Iterable[xml.Elem] = {
    result.elements map { case (id, array) => getData( id, array.toCDFloatArray, array.metadata.getOrElse("units","") ) }
  }
}

abstract class WPSEventReport extends WPSResponse {
  def toXml: xml.Elem =  <EventReports>  { getReport } </EventReports>
  def getReport: Iterable[xml.Elem]
}

class UtilityExecutionResult( id: String, val report: xml.Elem )  extends WPSEventReport with Loggable {
  def getReport: Iterable[xml.Elem] =  List( <UtilityReport utilityId={id}>  { report } </UtilityReport> )
}

class WPSExceptionReport( val err: Throwable ) extends WPSEventReport with Loggable {
  override def toXml = {
    <ows:ExceptionReport xmlns:ows="http://www.opengis.net/ows/1.1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://www.opengis.net/ows/1.1 ../../../ows/1.1.0/owsExceptionReport.xsd" version="1.0.0" xml:lang="en-CA">
      { getReport }
    </ows:ExceptionReport>
  }
  def getReport: Iterable[xml.Elem] =  List(  <ows:Exception exceptionCode={err.getClass.getName}> <ows:ExceptionText>{err.getMessage}</ows:ExceptionText> </ows:Exception> )
}

class AsyncExecutionResult( process: WPSProcess, optResultId: Option[String] ) extends WPSReferenceExecuteResponse( process, optResultId )  {
  def getProcessOutputs( process_id: String, output_id: String ): Iterable[xml.Elem] = List( getReference( process_id, output_id ) )
}

class WPSMergedEventReport( val reports: List[WPSEventReport] ) extends WPSEventReport {
  def getReport: Iterable[xml.Elem] = reports.flatMap( _.getReport )
}

class BlockingExecutionResult( process: WPSProcess, id: String, val intputSpecs: List[DataFragmentSpec], val gridSpec: TargetGrid, val result_tensor: CDFloatArray,
                               optResultId: Option[String] = None ) extends WPSReferenceExecuteResponse( process, optResultId )  with Loggable {
  //  def toXml_old = {
  //    val idToks = id.split('-')
  //    logger.info( "BlockingExecutionResult-> result_tensor(" + id + "): \n" + result_tensor.toString )
  //    val inputs = intputSpecs.map( _.toXml )
  //    val grid = gridSpec.toXml
  //    val results = result_tensor.mkDataString(",")
  //    <result id={id} op={idToks.head} rid={resultId.getOrElse("")}> { inputs } { grid } <data undefined={result_tensor.getInvalid.toString}> {results}  </data>  </result>
  //  }
  def getProcessOutputs( process_id: String, output_id: String  ): Iterable[xml.Elem] = {
    getReference( process_id, output_id )
    List( getData( output_id, result_tensor, intputSpecs.head.units ) )
  }
}

