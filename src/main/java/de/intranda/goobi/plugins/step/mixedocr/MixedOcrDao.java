package de.intranda.goobi.plugins.step.mixedocr;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import de.sub.goobi.persistence.managers.MySQLHelper;

public class MixedOcrDao {
    public static long addJob(int stepId) throws SQLException {
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            String sql = "INSERT INTO ocrjobs (step_id, fracture_done, antiqua_done) VALUES (?,?,?)";
            QueryRunner run = new QueryRunner();
            return run.insert(sql, new ScalarHandler<Long>(), stepId, false, false);
        }
    }

    public static void setJobDone(long jobId, boolean fracture) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ocrjobs SET ");
        if (fracture) {
            sql.append("fracture_done=true");
        } else {
            sql.append("antiqua_done=true");
        }
        sql.append(" WHERE ocrjob_id=?;");
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            QueryRunner run = new QueryRunner();
            run.update(conn, sql.toString(), jobId);
        }
    }

    public static boolean isJobDone(long jobId) throws SQLException {
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            QueryRunner run = new QueryRunner();
            return run.query(conn, "SELECT * FROM ocrjobs WHERE ocrjob_id=? AND antiqua_done=true AND fracture_done=true;",
                    new ResultSetHandler<Boolean>() {
                        @Override
                        public Boolean handle(ResultSet rs) throws SQLException {
                            return rs.next();
                        }
                    }, jobId);
        }
    }

    public static int getStepIdForJob(long jobId) throws SQLException {
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            String sql = "SELECT step_id from ocrjobs WHERE ocrjob_id=?";
            QueryRunner run = new QueryRunner();
            return run.insert(sql, new ScalarHandler<Integer>(), jobId);
        }
    }
}
