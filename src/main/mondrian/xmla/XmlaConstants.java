package mondrian.xmla;


/**
 * Constants for XML/A.
 * 
 * @author Gang Chen
 */
public interface XmlaConstants {

    /* Namespaces for XML, SOAP and XML/A */
    static final String NS_XSD = "http://www.w3.org/2001/XMLSchema";
    static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";
    static final String NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String NS_SOAP_ENCODING_STYLE = "http://schemas.xmlsoap.org/soap/encoding/";
    static final String NS_XMLA = "urn:schemas-microsoft-com:xml-analysis";
    static final String NS_XMLA_MDDATASET = "urn:schemas-microsoft-com:xml-analysis:mddataset";
    static final String NS_XMLA_ROWSET = "urn:schemas-microsoft-com:xml-analysis:rowset";

    /* XMLA protocol constants */
    static final int METHOD_DISCOVER = 1;
    static final int METHOD_EXECUTE = 2;
}