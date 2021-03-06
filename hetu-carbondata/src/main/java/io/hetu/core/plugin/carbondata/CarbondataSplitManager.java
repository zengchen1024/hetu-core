/*
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
package io.hetu.core.plugin.carbondata;

import com.google.common.collect.ImmutableList;
import io.hetu.core.plugin.carbondata.impl.CarbondataLocalMultiBlockSplit;
import io.hetu.core.plugin.carbondata.impl.CarbondataTableCacheModel;
import io.hetu.core.plugin.carbondata.impl.CarbondataTableReader;
import io.prestosql.plugin.hive.CoercionPolicy;
import io.prestosql.plugin.hive.DirectoryLister;
import io.prestosql.plugin.hive.ForHive;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveConfig;
import io.prestosql.plugin.hive.HivePartitionManager;
import io.prestosql.plugin.hive.HiveSplit;
import io.prestosql.plugin.hive.HiveSplitManager;
import io.prestosql.plugin.hive.HiveSplitWrapper;
import io.prestosql.plugin.hive.HiveTableHandle;
import io.prestosql.plugin.hive.HiveTransactionHandle;
import io.prestosql.plugin.hive.NamenodeStats;
import io.prestosql.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.VersionEmbedder;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.FixedSplitSource;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.dynamicfilter.DynamicFilter;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.resourcegroups.QueryType;
import org.apache.carbondata.core.scan.expression.Expression;
import org.apache.carbondata.core.stats.QueryStatistic;
import org.apache.carbondata.core.stats.QueryStatisticsConstants;
import org.apache.carbondata.core.stats.QueryStatisticsRecorder;
import org.apache.carbondata.core.util.CarbonTimeStatisticsFactory;
import org.apache.carbondata.core.util.ThreadLocalSessionInfo;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

import javax.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;

/**
 * Build Carbontable splits
 * filtering irrelevant blocks
 */
public class CarbondataSplitManager
        extends HiveSplitManager
{
    private final CarbondataTableReader carbonTableReader;
    private final Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider;
    private final HdfsEnvironment hdfsEnvironment;

    @Inject
    public CarbondataSplitManager(
            HiveConfig hiveConfig,
            Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider,
            HivePartitionManager partitionManager,
            NamenodeStats namenodeStats,
            HdfsEnvironment hdfsEnvironment,
            DirectoryLister directoryLister,
            @ForHive ExecutorService executorService,
            VersionEmbedder versionEmbedder,
            CoercionPolicy coercionPolicy,
            CarbondataTableReader reader)
    {
        super(hiveConfig, metastoreProvider, partitionManager, namenodeStats, hdfsEnvironment,
                directoryLister, executorService, versionEmbedder, coercionPolicy);
        this.carbonTableReader = requireNonNull(reader, "client is null");
        this.metastoreProvider = requireNonNull(metastoreProvider, "metastore is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
    }

    private static List<HostAddress> getHostAddresses(String[] hosts)
    {
        return Arrays.stream(hosts).map(HostAddress::fromString).collect(toImmutableList());
    }

    @Override
    public ConnectorSplitSource getSplits(ConnectorTransactionHandle transactionHandle,
            ConnectorSession session, ConnectorTableHandle tableHandle,
            SplitSchedulingStrategy splitSchedulingStrategy)
    {
        HiveTableHandle hiveTable = (HiveTableHandle) tableHandle;
        SchemaTableName schemaTableName = hiveTable.getSchemaTableName();

        // get table metadata
        SemiTransactionalHiveMetastore metastore =
                metastoreProvider.apply((HiveTransactionHandle) transactionHandle);
        Table table =
                metastore.getTable(schemaTableName.getSchemaName(), schemaTableName.getTableName())
                        .orElseThrow(() -> new TableNotFoundException(schemaTableName));
        if (!table.getStorage().getStorageFormat().getInputFormat().contains("carbon")) {
            return super.getSplits(transactionHandle, session, tableHandle, splitSchedulingStrategy);
        }

        return hdfsEnvironment.doAs(session.getUser(), () -> {
            String location = table.getStorage().getLocation();

            String queryId = System.nanoTime() + "";
            QueryStatistic statistic = new QueryStatistic();
            QueryStatisticsRecorder statisticRecorder = CarbonTimeStatisticsFactory.createDriverRecorder();
            statistic.addStatistics(QueryStatisticsConstants.BLOCK_ALLOCATION, System.currentTimeMillis());
            statisticRecorder.recordStatisticsForDriver(statistic, queryId);
            statistic = new QueryStatistic();

            carbonTableReader.setQueryId(queryId);
            TupleDomain<HiveColumnHandle> predicate =
                    (TupleDomain<HiveColumnHandle>) hiveTable.getCompactEffectivePredicate();
            Configuration configuration = this.hdfsEnvironment.getConfiguration(
                    new HdfsEnvironment.HdfsContext(session, schemaTableName.getSchemaName(),
                            schemaTableName.getTableName()), new Path(location));
            configuration = carbonTableReader.updateS3Properties(configuration);
            // set the hadoop configuration to thread local, so that FileFactory can use it.
            ThreadLocalSessionInfo.setConfigurationToCurrentThread(configuration);
            CarbondataTableCacheModel cache =
                    carbonTableReader.getCarbonCache(schemaTableName, location, configuration);
            Expression filters = CarbondataHetuFilterUtil.parseFilterExpression(predicate);
            try {
                List<CarbondataLocalMultiBlockSplit> splits =
                        carbonTableReader.getInputSplits(cache, filters, predicate, configuration);

                ImmutableList.Builder<ConnectorSplit> cSplits = ImmutableList.builder();
                long index = 0;
                for (CarbondataLocalMultiBlockSplit split : splits) {
                    index++;
                    Properties properties = new Properties();
                    for (Map.Entry<String, String> entry : table.getStorage().getSerdeParameters().entrySet()) {
                        properties.setProperty(entry.getKey(), entry.getValue());
                    }
                    properties.setProperty("tablePath", cache.getCarbonTable().getTablePath());
                    properties.setProperty("carbonSplit", split.getJsonString());
                    properties.setProperty("queryId", queryId);
                    properties.setProperty("index", String.valueOf(index));
                    cSplits.add(HiveSplitWrapper.wrap(new HiveSplit(schemaTableName.getSchemaName(), schemaTableName.getTableName(),
                            schemaTableName.getTableName(), cache.getCarbonTable().getTablePath(),
                            0, 0, 0, 0,
                            properties, new ArrayList(), getHostAddresses(split.getLocations()),
                            OptionalInt.empty(), false, new HashMap<>(),
                            Optional.empty(), false, Optional.empty(), Optional.empty(), false)));
                    /* Todo(Neeraj/Aman): Make this part aligned with rest of the HiveSlipt loading flow...
                     *   and figure out how to pass valid transaction Ids to CarbonData? */
                }

                statisticRecorder.logStatisticsAsTableDriver();

                statistic
                        .addStatistics(QueryStatisticsConstants.BLOCK_IDENTIFICATION, System.currentTimeMillis());
                statisticRecorder.recordStatisticsForDriver(statistic, queryId);
                statisticRecorder.logStatisticsAsTableDriver();
                return new FixedSplitSource(cSplits.build());
            }
            catch (IOException ex) {
                throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed while trying to get splits ", ex);
            }
        });
    }

    @Override
    public ConnectorSplitSource getSplits(ConnectorTransactionHandle transactionHandle,
                                          ConnectorSession session, ConnectorTableHandle tableHandle,
                                          SplitSchedulingStrategy splitSchedulingStrategy,
                                          Supplier<Set<DynamicFilter>> dynamicFilterSupplier,
                                          Optional<QueryType> queryType,
                                          Map<String, Object> queryInfo,
                                          Set<TupleDomain<ColumnMetadata>> userDefinedCachePredicates)
    {
        return this.getSplits(transactionHandle, session, tableHandle, splitSchedulingStrategy);
    }
}
