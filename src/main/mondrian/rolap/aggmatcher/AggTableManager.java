/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2005-2005 Kana Software, Inc. and others.
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/

package mondrian.rolap.aggmatcher;

import mondrian.olap.*;
import mondrian.rolap.*;
import mondrian.recorder.*;
import org.apache.log4j.Logger;

import javax.sql.DataSource;
import java.util.*;
import java.sql.SQLException;

/**
 * Manages aggregate tables.
 *
 * <p>It is used as follows:<ul>
 * <li>A {@link mondrian.rolap.RolapSchema} creates an {@link AggTableManager},
 *     and stores it in a member variable to ensure that it is not
 *     garbage-collected.
 * <li>The {@link AggTableManager} creates and registers
 *     {@link MondrianProperties.Trigger} objects, so that it is notified
 *     when properties pertinent to aggregate tables change.
 * <li>The {@link mondrian.rolap.RolapSchema} calls {@link #initialize()},
 *     which scans the JDBC catalog and identifies aggregate tables.
 * <li>For each aggregate table, it creates an {@link AggStar} and calls
 *     {@link RolapStar#addAggStar(AggStar)}.
 *
 * @author <a>Richard M. Emberson</a>
 * @version
 */
public class AggTableManager {
    private static final Logger LOGGER =
            Logger.getLogger(AggTableManager.class);

    private final RolapSchema schema;

    private static final MondrianResource mres = MondrianResource.instance();

    /**
     * This is set to true if the aggregate tables have been loaded.
     */
    private boolean aggregatesLoaded;

    /**
     * This is used to create forward references to triggers (so they do not
     * get reaped until the RolapSchema is reaped).
     */
    private MondrianProperties.Trigger[] triggers;

    public AggTableManager(final RolapSchema schema) {
        this.schema = schema;
    }

    /**
     * Get the Logger.
     *
     * @return
     */
    public Logger getLogger() {
        return LOGGER;
    }

    /**
     * Initializes this object, loading all aggregate tables and associating
     * them with {@link RolapStar}s.
     * This method should only be called once.
     */
    public void initialize() {
        reLoadRolapStarAggregates();
        registerTriggers();
    }
    private void reLoadRolapStarAggregates() {
        if (MondrianProperties.instance().getUseAggregates()) {
            try {
                // NOTE: At this point all RolapStars have been made for this
                // schema (except for dynamically added cubes which I am going
                // to ignore for right now). So, All stars have their columns
                // and their BitKeys can be generated.
                loadRolapStarAggregates();

            } catch (SQLException ex) {
                throw mres.newAggLoadingError(ex);
            }
            aggregatesLoaded = true;
        }
    }

    /**
     * This method loads and/or reloads the aggregate tables.
     *
     * @throws SQLException
     */
    private void loadRolapStarAggregates() throws SQLException {
        ListRecorder msgRecorder = new ListRecorder();
        try {

        DefaultRules rules = DefaultRules.getInstance();

        DataSource dataSource = schema.getInternalConnection().getDataSource();
        // This actually just does a lookup or simple constructor invocation,
        // its not expected to fail
        JdbcSchema db = JdbcSchema.makeDB(dataSource);

        loop:
        for (Iterator it = getStars(); it.hasNext(); ) {
            RolapStar star = (RolapStar) it.next();
            // This removes any AggStars from any previous invocation of this
            // method (if any)
            star.prepareToLoadAggregates();

            List aggGroups = getAggGroups(star);
            for (Iterator git = aggGroups.iterator(); git.hasNext(); ) {
                ExplicitRules.Group group = (ExplicitRules.Group) git.next();
                group.validate(msgRecorder);
            }


            String factTableName = star.getFactTable().getAlias();

            JdbcSchema.Table dbFactTable = db.getTable(factTableName);
            if (dbFactTable == null) {
                msgRecorder.reportWarning("No Table found for fact name="
                    +factTableName);

                continue loop;
            }

            // For each column in the dbFactTable, figure out it they are
            // measure or foreign key columns
            bindToStar(dbFactTable, star, msgRecorder);
            String schema = dbFactTable.table.schema;

            // Now look at all tables in the database and per table, first see
            // if it is a match for an aggregate table for this fact table and
            // second see if its columns match foreign key and level columns.
            for (Iterator tit = db.getTables(); tit.hasNext(); ) {
                JdbcSchema.Table dbTable = (JdbcSchema.Table) tit.next();
                String name = dbTable.getName();

                // Do the catalog schema aggregate excludes, exclude this
                // table name.
                if (ExplicitRules.excludeTable(name, aggGroups)) {
                    continue;
                }

                //
                // First see if there is an ExplicitRules match. If so, then if all
                // of the columns match up, then make an AggStar.
                // On the other hand, if there is no ExplicitRules match, see if
                // there is a Default match. If so and if all the columns
                // match up, then also make an AggStar.
                //
                ExplicitRules.TableDef tableDef =
                    ExplicitRules.getIncludeByTableDef(name, aggGroups);

                boolean makeAggStar =
                    ((tableDef != null) &&
                    tableDef.columnsOK(star, dbFactTable, dbTable, msgRecorder))
                    ||
                    (rules.matchesTableName(factTableName, name) &&
                     rules.columnsOK(star, dbFactTable, dbTable, msgRecorder));

                if (makeAggStar) {
                    dbTable.setTableType(JdbcSchema.AGG_TABLE_TYPE);
                    String alias = null;
                    dbTable.table = new MondrianDef.Table(schema,
                                                          name,
                                                          alias);
                    AggStar aggStar = AggStar.makeAggStar(star,
                                                          dbTable,
                                                          msgRecorder);
                    star.addAggStar(aggStar);
                }
                // Note: if the dbTable name matches but the columnsOK does
                // not, then this is an error and the aggregate tables
                // can not be loaded.
                // We do not "reset" the column usages in the dbTable allowing
                // it maybe to match another rule.
            }
        }

        } catch (RecorderException ex) {
            throw new MondrianException(ex);

        } finally {
            msgRecorder.logWarningMessage(getLogger());
            msgRecorder.logErrorMessage(getLogger());
            if (msgRecorder.hasErrors()) {
                throw mres.newAggLoadingExceededErrorCount(
                    new Integer(msgRecorder.getErrorCount()));
            }
        }
    }

    /**
     * Registers triggers for the following properties:
     * <ul>
     *      <li>{@link MondrianProperties#ChooseAggregateByVolume}
     *      <li>{@link MondrianProperties#AggregateRules}
     *      <li>{@link MondrianProperties#AggregateRuleTag}
     *      <li>{@link MondrianProperties#UseAggregates}
     * </ul>
     */
    private void registerTriggers() {
        triggers = new MondrianProperties.Trigger[3];

        // When the ordering AggStars property is changed, we must
        // reorder them, so we create a trigger.
        // There is no need to provide equals/hashCode methods for this
        // Trigger since it is never explicitly removed.
        MondrianProperties.Trigger trigger =
            new MondrianProperties.Trigger() {
                public boolean isPersistent() {
                    return false;
                }
                public int phase() {
                    return MondrianProperties.Trigger.SECONDARY_PHASE;
                }
                public void executeTrigger(final String key,
                                           final String value)
                             throws MondrianProperties.Trigger.VetoRT {
                    reOrderAggStarList();
                }
            };
        MondrianProperties.instance().addTrigger(trigger,
            MondrianProperties.ChooseAggregateByVolume);
        triggers[0] = trigger;

        // Register to know when the Default resource/url has changed
        // so that the default aggregate table recognition rules can
        // be re-loaded.
        // There is no need to provide equals/hashCode methods for this
        // Trigger since it is never explicitly removed.
        trigger =
            new MondrianProperties.Trigger() {
                public boolean isPersistent() {
                    return false;
                }
                public int phase() {
                    return MondrianProperties.Trigger.SECONDARY_PHASE;
                }
                public void executeTrigger(final String key,
                                           final String value)
                             throws MondrianProperties.Trigger.VetoRT {
                    reLoadRolapStarAggregates();
                }
            };
        MondrianProperties.instance().addTrigger(trigger,
            MondrianProperties.AggregateRules);
        MondrianProperties.instance().addTrigger(trigger,
            MondrianProperties.AggregateRuleTag);
        triggers[1] = trigger;

        // If the system started not using aggregates, i.e., the aggregate
        // tables were not loaded, but then the property
        // was changed to use aggregates, we must then load the aggregates
        // if they were never loaded.
        trigger =
            new MondrianProperties.Trigger() {
                public boolean isPersistent() {
                    return false;
                }
                public int phase() {
                    return MondrianProperties.Trigger.SECONDARY_PHASE;
                }
                public void executeTrigger(final String key,
                                           final String value)
                             throws MondrianProperties.Trigger.VetoRT {
                    if (! aggregatesLoaded) {
                        reLoadRolapStarAggregates();
                    }
                }
            };
        MondrianProperties.instance().addTrigger(trigger,
            MondrianProperties.UseAggregates);
        triggers[2] = trigger;
    }
    private Iterator getStars() {
        return schema.getStars();
    }
    private void reOrderAggStarList() {
        for (Iterator it = getStars(); it.hasNext(); ) {
            RolapStar star = (RolapStar) it.next();
            star.reOrderAggStarList();
        }
    }

    /**
     * Returns a list containing every
     * {@link mondrian.rolap.aggmatcher.ExplicitRules.Group} in every
     * cubes in a given {@link RolapStar}.
     */
    protected List getAggGroups(RolapStar star) {
        List list = schema.getCubesWithStar(star);

        List aggGroups = Collections.EMPTY_LIST;
        for (Iterator it = list.iterator(); it.hasNext(); ) {
            RolapCube cube = (RolapCube) it.next();
            if (cube.hasAggGroup() && cube.getAggGroup().hasRules()) {
                if (aggGroups == Collections.EMPTY_LIST) {
                    aggGroups = new ArrayList();
                }
                aggGroups.add(cube.getAggGroup());
            }
        }
        return aggGroups;
    }

    /**
     * This method mines the RolapStar and annotes the JdbcSchema.Table
     * dbFactTable by creating JdbcSchema.Table.Column.Usage instances. For
     * example, a measure in the RolapStar becomes a measure usage for the
     * column with the same name and a RolapStar foreign key column becomes a
     * foreign key usage for the column with the same name.
     *
     * @param dbFactTable
     * @param star
     * @param msgRecorder
     */
    void bindToStar(final JdbcSchema.Table dbFactTable,
                    final RolapStar star,
                    final MessageRecorder msgRecorder) {
        dbFactTable.setTableType(JdbcSchema.FACT_TABLE_TYPE);

        MondrianDef.Relation relation = star.getFactTable().getRelation();
        String schema = null;
        if (relation instanceof MondrianDef.Table) {
            schema = ((MondrianDef.Table) relation).schema;
        }
        String tableName = dbFactTable.getName();
        String alias = null;
        dbFactTable.table = new MondrianDef.Table(schema, tableName, alias);

        for (Iterator it = dbFactTable.getColumns(); it.hasNext(); ) {
            JdbcSchema.Table.Column factColumn =
                (JdbcSchema.Table.Column) it.next();
            String cname = factColumn.getName();
            RolapStar.Column[] rcs =
                star.getFactTable().lookupColumns(cname);

            for (int i = 0; i < rcs.length; i++) {
                RolapStar.Column rc = rcs[i];
                // its a measure
                if (rc instanceof RolapStar.Measure) {
                    RolapStar.Measure rm = (RolapStar.Measure) rc;
                    JdbcSchema.Table.Column.Usage usage =
                            factColumn.newUsage(JdbcSchema.MEASURE_COLUMN_TYPE);
                    usage.setSymbolicName(rm.getName());

                    usage.setAggregator(rm.getAggregator());
                    usage.measure = rm;

                    // warn if not numeric (is boolean allowed??)
                    if (! factColumn.isNumeric()) {
                        String msg = mres.getNonNumericMeasure(
                            msgRecorder.getContext(),
                            dbFactTable.getName(),
                            factColumn.getName(),
                            factColumn.getTypeName());
                        msgRecorder.reportWarning(msg);
                    }
                }
            }

            // it still might be a foreign key
            RolapStar.Table rTable =
                star.getFactTable().findTableWithLeftJoinCondition(cname);
            if (rTable != null) {
                JdbcSchema.Table.Column.Usage usage =
                    factColumn.newUsage(JdbcSchema.FOREIGN_KEY_COLUMN_TYPE);
                usage.setSymbolicName("FOREIGN_KEY");
                usage.rTable = rTable;
            }

            // warn if it has not been identified
            if (! factColumn.hasUsage()) {
                String msg = mres.getUnknownFactTableColumn(
                    msgRecorder.getContext(),
                    dbFactTable.getName(),
                    factColumn.getName());
                msgRecorder.reportInfo(msg);
            }
        }
    }
}