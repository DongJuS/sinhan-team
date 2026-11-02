package com.sinhan.benefit.infrastructure;

import com.sinhan.benefit.domain.BenefitCalculator.Rule;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BenefitRepository {
  private final JdbcTemplate db;

  public BenefitRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Rule> rules() {
    return db.query(
        "select * from rules order by method",
        (r, n) ->
            new Rule(
                r.getString("method"),
                r.getString("version"),
                r.getInt("instant_bps"),
                r.getInt("statement_bps"),
                r.getInt("cashback_bps"),
                r.getInt("points_bps"),
                r.getInt("point_numerator"),
                r.getInt("point_denominator"),
                r.getInt("per_order_cap"),
                r.getInt("monthly_cap")));
  }

  public boolean eligible(String user, String method) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "select exists(select 1 from profiles p join rules r on r.method=? where p.user_id=?"
                + " and p.previous_month_spend_krw>=r.required_previous_spend_krw)",
            Boolean.class,
            method,
            user));
  }

  public void lock(String user) {
    db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", "benefit:" + user);
  }

  public int used(String user, String method, String exclude) {
    return db.queryForObject(
        "select coalesce(sum(value_krw),0)::integer from reservations where user_id=? and method=?"
            + " and period=to_char(now() at time zone 'Asia/Seoul','YYYY-MM') and status in"
            + " ('RESERVED','COMMITTED') and execution_id<>?",
        Integer.class,
        user,
        method,
        exclude == null ? "" : exclude);
  }

  public Map<String, Object> reservation(String id) {
    var rows = db.queryForList("select * from reservations where execution_id=?", id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void reserve(String executionId, String user, String method, int value, String digest) {
    db.update(
        "insert into reservations(execution_id,user_id,method,value_krw,digest,period,status)"
            + " values(?,?,?,?,?,to_char(now() at time zone 'Asia/Seoul','YYYY-MM'),'RESERVED')",
        executionId,
        user,
        method,
        value,
        digest);
  }

  public void status(String id, String status) {
    db.update(
        "update reservations set status=? where execution_id=? and status<>'RELEASED'", status, id);
  }
}
