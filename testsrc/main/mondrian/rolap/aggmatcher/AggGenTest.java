/**
 * 
 */
package mondrian.rolap.aggmatcher;

import java.io.StringWriter;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.WriterAppender;

import mondrian.olap.MondrianProperties;
import mondrian.olap.Query;
import mondrian.rolap.RolapConnection;
import mondrian.test.FoodMartTestCase;


/**
 * 
 * Test if lookup columns are there after loading them in
 * AggGen#addCollapsedColumn(...).
 *
 */
public class AggGenTest extends FoodMartTestCase {

    public AggGenTest(String name) {
        super(name);
    }
    
    public void testCallingLoadColumnsInAddCollapsedColumnOrAddzSpecialCollapsedColumn() throws Exception {
        Logger logger = Logger.getLogger(AggGen.class);
        StringWriter writer = new StringWriter();
        Appender myAppender = new WriterAppender(new SimpleLayout(), writer);
        logger.addAppender(myAppender);
        logger.setLevel(Level.DEBUG);
        
        final String trueValue = "true";
        
        // This modifies the MondrianProperties for the whole of the
        // test run
        
        MondrianProperties props = MondrianProperties.instance();
        props.AggregateRules.setString("DefaultRules.xml"); // If run in Ant and with mondrian.jar, please comment out this line
        props.UseAggregates.setString(trueValue);
        props.ReadAggregates.setString(trueValue);
        props.GenerateAggregateSql.setString(trueValue);
        
        final RolapConnection rolapConn = (RolapConnection) getConnection();
        Query query = rolapConn.parseQuery("select {[Measures].[Count]} on columns from [HR]");
        rolapConn.execute(query);
        
        logger.removeAppender(myAppender);
        
        DatabaseMetaData dbmeta = rolapConn.getDataSource().getConnection().getMetaData();
        JdbcSchema jdbcSchema = JdbcSchema.makeDB(rolapConn.getDataSource());
        final String catalogName = jdbcSchema.getCatalogName();
        final String schemaName = jdbcSchema.getSchemaName();
        
        String log = writer.toString();
        Pattern p = Pattern.compile("DEBUG - Init: Column: [^:]+: `(\\w+)`.`(\\w+)`" + 
                System.getProperty("line.separator") + 
                "WARN - Can not find column: \\2");
        Matcher m = p.matcher(log);
        
        while (m.find()) {
            ResultSet rs = dbmeta.getColumns(catalogName, schemaName, m.group(1), m.group(2));
            assertTrue(!rs.next());
        }
    }

}