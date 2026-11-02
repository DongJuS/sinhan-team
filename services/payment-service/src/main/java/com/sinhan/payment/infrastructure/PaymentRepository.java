package com.sinhan.payment.infrastructure;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
  private final JdbcTemplate db;

  public PaymentRepository(JdbcTemplate d) {
    db = d;
  }

  public void lock(String execution) {
    db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", execution);
  }

  public Map<String, Object> transaction(String id) {
    var rows = db.queryForList("select * from transactions where execution_id=?", id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Integer> balances(String user) {
    Map<String, Integer> values = new LinkedHashMap<>();
    db.query(
        "select method,balance_krw from wallets where user_id=?",
        r -> {
          values.put(r.getString(1), r.getInt(2));
        },
        user);
    return values;
  }

  public boolean debit(String user, String method, int amount) {
    return db.update(
            "update wallets set balance_krw=balance_krw-? where user_id=? and method=? and"
                + " balance_krw>=?",
            amount,
            user,
            method,
            amount)
        == 1;
  }

  public void record(String id, String user, String method, int amount, String digest) {
    db.update(
        "insert into transactions(execution_id,user_id,method,amount_krw,digest,status)"
            + " values(?,?,?,?,?,'PAID')",
        id,
        user,
        method,
        amount,
        digest);
    db.update("insert into ledger(execution_id,kind,amount_krw) values(?,'CHARGE',?)", id, -amount);
  }

  public void refund(Map<String, Object> row) {
    String id = row.get("execution_id").toString();
    db.update(
        "update wallets set balance_krw=balance_krw+? where user_id=? and method=?",
        row.get("amount_krw"),
        row.get("user_id"),
        row.get("method"));
    db.update("update transactions set status='REFUNDED' where execution_id=?", id);
    db.update(
        "insert into ledger(execution_id,kind,amount_krw) values(?,'REFUND',?)",
        id,
        row.get("amount_krw"));
  }

  public boolean takeFault(String name) {
    return db.update("update faults set remaining=remaining-1 where name=? and remaining>0", name)
        == 1;
  }

  public void fault(String name, int count) {
    db.update(
        "insert into faults(name,remaining) values(?,?) on conflict(name) do update set"
            + " remaining=excluded.remaining",
        name,
        count);
  }

  public List<Map<String, Object>> ledger() {
    return db.queryForList("select * from ledger order by id");
  }
}
