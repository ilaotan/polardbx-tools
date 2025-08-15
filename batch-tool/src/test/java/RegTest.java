import org.apache.commons.lang3.StringUtils;

/**
 * 类的描述.
 *
 * @author tan liansheng on 2025-8-15 14:50
 */
public class RegTest {

    public static void main(String[] args) {

        String tableDdl = "\t`RelaClientUserId` varchar(32) DEFAULT '' COMMENT '代金券消费时关联的车辆或钱包的ClientUserId',\n" +
                "\tPRIMARY KEY (`Id`, `CreateTime`),\n" +
                "\tKEY `index_BizOrderNo` USING BTREE (`BizOrderNo`),\n" +
                "\tKEY `index_CreateTime` (`CreateTime`, `ProjectId`, `SignFlag`),\n" +
                "\tKEY `idx_SCC` USING BTREE (`ConsumeType`, `Status`, `CreateTime`),\n" +
                "\tKEY `idx_clientUserId_createTime` (`ClientUserId`, `CreateTime`)\n" +
                ") ENGINE = InnoDB DEFAULT CHARSET = utf8\n" +
                ";";

        tableDdl = tableDdl.replace("GLOBAL INDEX", "KEY");
        tableDdl = tableDdl.replace("LOCAL KEY", "KEY");

        tableDdl = tableDdl.replaceAll("(?m)^\\s*PARTITION\\s+BY\\s+KEY\\s*\\([^)]*\\)\\s*$", "");

        // 带逗号的情况
        tableDdl = tableDdl.replaceAll("\\s*PARTITION\\b[^,]*,", ",");
        // 不带逗号(最后一条索引)的情况
        tableDdl = tableDdl.replaceAll("\\s*PARTITION\\b.*\\n\\s*.*?\\n", "\n");

        tableDdl = tableDdl.replaceAll("(?m)^\\s*PARTITION\\s+BY\\s+KEY\\s*\\([^)]*\\)\\s*$", "");

        tableDdl = StringUtils.substringBeforeLast(tableDdl, "PARTITION BY");
        tableDdl = StringUtils.substringBeforeLast(tableDdl, "SINGLE");

        tableDdl = tableDdl.replaceAll("\\s+PARTITIONS\\s+\\d+", "");

        tableDdl = tableDdl.replaceAll("(?m)^\\s*LOCAL\\s*$", "");
        tableDdl= tableDdl.replaceAll("(?m)^\\s*EXPIRE\\s+AFTER\\s+\\d+\\s*$", "");
        tableDdl = tableDdl.replaceAll("(?m)^\\s*PRE\\s+ALLOCATE\\s+\\d+\\s*$", "");
        tableDdl = tableDdl.replaceAll("(?m)^\\s*PIVOTDATE\\s+[^;\\n]+\\s*$", "");

        // 删除包含 COVERING 的整行（包括行尾可能的逗号和空格）
        tableDdl = tableDdl.replaceAll("(?m)^\\s*[^,]*COVERING[^\\n]*,?\\s*$\\n?", "");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `CreateDate`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`id`, `create_time`)", "PRIMARY KEY (`id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`id`, `CreateTime`)", "PRIMARY KEY (`id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `CreateTime`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `EndValidityPeriod`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`ID`, `EndDate`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `StatisticsTime`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `RecoveredDate`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `archive_date`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `OperationTime`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `UpdateTime`)", "PRIMARY KEY (`Id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`id`, `order_time`)", "PRIMARY KEY (`id`)");
        tableDdl = tableDdl.replace("PRIMARY KEY (`Id`, `Order_Time`)", "PRIMARY KEY (`Id`)");



        System.out.println(tableDdl);
    }
}
